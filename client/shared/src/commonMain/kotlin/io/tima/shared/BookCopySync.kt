package io.tima.shared

import io.tima.core.database.SqlBook
import io.tima.core.database.SqlReadState
import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode
import io.tima.core.encryption.BookCopyCodecOverKodium
import io.tima.core.encryption.ReadsCopyCodecOverKodium
import io.tima.core.network.AccountStoreOverHttp
import io.tima.domain.chat.CopyStep
import io.tima.domain.chat.HealStep
import io.tima.domain.chat.ReadMarksPort
import io.tima.domain.chat.ReadsStep
import io.tima.domain.chat.RevisionMemory
import io.tima.domain.chat.Settings
import io.tima.domain.chat.SyncBookCopy
import io.tima.domain.chat.SyncReadsCopy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Копия аккаунта между устройствами — книга с разделами (ПЛАН-(РЗ)-РАЗДЕЛОВ Р2а) и отметки
 * «просмотрено до» (ПЛАН-(ЖУ)-ЖУРНАЛА-УВЕДОМЛЕНИЙ.md, ЖУ9). Сборка и расписание.
 *
 * ── КЛЮЧ ────────────────────────────────────────────────────────────────────
 *
 * Спрашивается у сервера служебная группа аккаунта, дальше — тот же путь, что у любой
 * группы без ключа: `HealGroupKey` забирает обёртки, а если ключа не было ни у кого,
 * выпускает первый. Новому устройству ключ отдаёт доверившее, в том же нажатии «Доверить»
 * (ЖУ8). Своих ключевых механизмов у копии нет — решение заказчика 2026-09-18.
 *
 * ── РАСПИСАНИЕ — ОДНО НА ОБА ВИДА (ЖУ9, заказчик 2026-09-30) ─────────────────
 *
 * - **При запуске** — забрать, потом отдать своё, если изменилось с прошлой отдачи
 *   (отпечаток переживает перезапуск).
 * - **Правка книги, просмотр переписки** — только в базу устройства, в сеть ничего.
 * - **Окно ушло с экрана** (фон, «Выйти», «Закрыть приложение») — конец сессии: отдать
 *   изменённое. Одна отправка за сессию, а не на каждый переход.
 * - **Сервер сказал «копия изменилась»** (событие `store.changed`) — в фоне только
 *   запомнить номер; на экране — забрать сразу; вышли на экран — забрать, если номер
 *   новее своего. Несколько событий склеиваются в один забор.
 *
 * Отказы не роняют ничего: копия — удобство, а не условие работы. Каждый ход пишется в
 * журнал одной строкой — по ним видно, доехало ли.
 */
class BookCopySync(
    private val environment: Environment,
    private val store: AccountStoreOverHttp,
    private val keys: GroupKeyOrchestrator,
    private val deviceId: String,
    private val scope: CoroutineScope,
    /** Видно ли окно — сессия человека (ЖУ9). */
    private val shown: StateFlow<Boolean>? = null,
    /** Ревизии копии, о которых сказал сервер: вид → ревизия (ЖУ9). */
    private val changes: StateFlow<Map<String, Long>>? = null,
    /** Отметки «просмотрено до» и чтение прочитанного (ЖУ9). */
    private val readState: SqlReadState? = null,
    /** Переписка прочитана на другом устройстве — снять её числа. */
    private val onReadElsewhere: (String) -> Unit = {},
    /** Ячейка отметок у сервера. */
    private val readsStore: AccountStoreOverHttp? = null,
    /**
     * Просьба о ключе служебной группы, подписанная фразой (заказчик 2026-09-30, 2а).
     * Нужна устройству, вошедшему по номеру и фразе: ключей ему никто не передал.
     */
    private val keyRequest: io.tima.domain.chat.GroupKeyRecovery? = null,
) {
    private val memory = SettingsRevisionMemory(environment.settings, scope, BOOK_REVISION)
    private val readsMemory = SettingsRevisionMemory(environment.settings, scope, READS_REVISION)

    /** Отпечаток отданной книги — в настройках: переживает перезапуск. */
    @kotlin.concurrent.Volatile
    private var bookPrint: Int? = null

    private val sync = SyncBookCopy(
        copy = environment.bookStorage as SqlBook,
        store = store,
        codec = BookCopyCodecOverKodium,
        key = { storeKey() },
        revision = memory,
        device = { deviceId },
        olderKeys = { olderStoreKeys() },
        lastPrint = { bookPrint },
        rememberPrint = { print ->
            bookPrint = print
            scope.launch { environment.settings.put(BOOK_PRINT, print.toString()) }
        },
    )

    private val reads: SyncReadsCopy? = if (readState == null || readsStore == null) null else SyncReadsCopy(
        store = readsStore,
        codec = ReadsCopyCodecOverKodium,
        key = { storeKey() },
        revision = readsMemory,
        device = { deviceId },
        olderKeys = { olderStoreKeys() },
        marks = object : ReadMarksPort {
            override fun marks() = readState.marks()
            override fun dirty() = readState.dirty()
            override fun sent() {
                readState.sent()
            }
            override fun merge(remote: Map<String, Long>): Map<String, Long> {
                val mine = readState.marks()
                val moved = remote.filter { (chat, upto) -> upto > (mine[chat] ?: -1) }
                moved.forEach { (chat, upto) -> readState.mergeRemote(chat, upto) }
                return moved
            }
        },
    )

    private val _keyMissing = kotlinx.coroutines.flow.MutableStateFlow(false)

    /**
     * Ключа служебной группы нет — копия не открывается и не отдаётся. По нему «Секретная
     * фраза и устройства» показывает «Запросить ключ» (заказчик 2026-09-30).
     */
    val keyMissing: StateFlow<Boolean> = _keyMissing

    private val _keyAsk = kotlinx.coroutines.flow.MutableStateFlow<KeyAsk>(KeyAsk.Idle)

    /** Что вышло с просьбой о ключе по кнопке. */
    val keyAsk: StateFlow<KeyAsk> = _keyAsk

    /** Правили книгу в этой сессии — отдать при её конце. */
    @kotlin.concurrent.Volatile
    private var bookDirty = false

    /** Один ход копии за раз: события склеиваются, а не гонят параллельные заборы. */
    private val turn = Mutex()

    /** Ключ служебной группы: спросить группу, вылечить, взять последнюю версию. */
    private var groupId: String? = null

    private suspend fun storeKey(): ByteArray? {
        val gid = groupId ?: store.storeGroup()?.also { groupId = it } ?: return null
        when (keys.heal.heal(gid)) {
            HealStep.NotEncrypted, HealStep.Unknown, HealStep.NeedAsk -> {
                // NotEncrypted для служебной группы означало бы, что сервер завёл её не
                // private — это поломка сервера, и её надо видеть, а не обходить.
                val version = keys.keys.latestVersion(gid) ?: return null
                return keys.keys.key(gid, version)
            }
            HealStep.Issued, is HealStep.Fetched -> {
                val version = keys.keys.latestVersion(gid) ?: return null
                return keys.keys.key(gid, version)
            }
        }
    }

    /** Прежние версии ключа служебной группы, от новой к старой: копию мог запечатать ключ до смены. */
    private fun olderStoreKeys(): List<ByteArray> {
        val gid = groupId ?: return emptyList()
        val latest = keys.keys.latestVersion(gid) ?: return emptyList()
        return keys.keys.versions(gid).filter { it != latest }.sortedDescending().mapNotNull { keys.keys.key(gid, it) }
    }

    fun start() {
        scope.launch {
            memory.load()
            readsMemory.load()
            bookPrint = environment.settings.all().first()[BOOK_PRINT]?.toIntOrNull()
            // Сеть при запуске часто ещё не поднялась (Redmi 2026-09-18: UnknownHost в
            // первые секунды после пробуждения). Без сети или без ключа — повторить с
            // растущей паузой, а не ждать следующего запуска.
            var pause = 5_000L
            var tries = 4
            var attempt = 0
            while (attempt < tries) {
                val pulled = turn.withLock { sync.pull() }
                note("забрать при запуске", pulled)
                // Ключа нет, а вошли только что по фразе — попросить его у своих устройств,
                // подписав словами (2а). Ответят те, что на связи; ждём дольше обычного.
                if (pulled == CopyStep.NoKey && askKeyByPhrase()) {
                    tries = 12
                    pause = 15_000L
                }
                if (pulled !is CopyStep.Offline && pulled != CopyStep.NoKey) {
                    turn.withLock {
                        note("отдать при запуске", sync.push())
                        pullReads("забрать при запуске")
                        pushReads("отдать при запуске")
                    }
                    return@launch
                }
                attempt++
                if (attempt < tries) delay(pause)
                if (tries == 4) pause *= 3
            }
        }
        // Правка книги — только отметка «изменено»: отдаётся в конце сессии (ЖУ9), а не
        // через две секунды после каждой правки, как было до 2026-09-30. Первая пара значений
        // потоков — чтение при запуске, а не правка: пропускается.
        // `everyone`, а не `list`: копия везёт и убранных с заблокированными.
        combine(environment.bookStorage.everyone(), environment.bookStorage.sections()) { _, _ -> Unit }
            .drop(1)
            .onEach { bookDirty = true }
            .launchIn(scope)

        // Сессия: ушли с экрана — отдать; вышли на экран — забрать, если есть новее.
        shown?.drop(1)?.onEach { visible -> if (visible) catchUp("по выходу на экран") else sessionEnded() }?.launchIn(scope)

        // Сервер сказал «копия изменилась»: на экране — забрать сразу, в фоне — ждать экрана.
        changes?.drop(1)?.onEach { if (shown?.value != false) catchUp("по событию сервера") }?.launchIn(scope)
    }

    /**
     * Конец сессии — отдать изменённое. Если в сессии и так уходит сообщение, зовут и отсюда:
     * сеть уже поднята (ЖУ9).
     */
    fun sessionEnded() {
        scope.launch {
            turn.withLock {
                if (bookDirty) {
                    bookDirty = false
                    note("отдать в конце сессии", sync.push())
                }
                pushReads("отдать в конце сессии")
            }
        }
    }

    /**
     * Попросить ключ служебной группы, подписав фразой, — один раз после входа по фразе.
     *
     * @return `true` — просьба ушла: ответа стоит подождать.
     */
    private suspend fun askKeyByPhrase(): Boolean {
        val words = PhraseOnce.take() ?: return false
        return askKey(words)?.let { it is io.tima.domain.chat.RecoveryStep.Requested && it.helpers > 0 } ?: false
    }

    /**
     * «Запросить ключ» в «Секретная фраза и устройства» (заказчик 2026-09-30): просьба,
     * подписанная фразой, и ожидание ответа — забор копии раз в 15 с, пока ключ не придёт.
     *
     * Фраза проверяется на месте: опечатка в слове — не повод тревожить сеть и чужие
     * устройства. Слова дальше этого вызова не живут.
     */
    fun requestKey(phrase: String) {
        val words = phrase.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        scope.launch {
            _keyAsk.value = KeyAsk.Sending
            if (io.tima.core.encryption.AccountIdentitiesOverKodium.fromWords(words) == null) {
                _keyAsk.value = KeyAsk.WrongPhrase
                return@launch
            }
            val step = askKey(words)
            _keyAsk.value = when (step) {
                is io.tima.domain.chat.RecoveryStep.Requested ->
                    if (step.helpers > 0) KeyAsk.Asked(step.helpers) else KeyAsk.NoHelpers
                io.tima.domain.chat.RecoveryStep.NeedsSecretPhrase -> KeyAsk.WrongPhrase
                null -> KeyAsk.Failed("просьба не ушла")
                else -> KeyAsk.Failed(step.toString())
            }
            if (_keyAsk.value !is KeyAsk.Asked) return@launch
            // Ответит устройство на связи — обёртки ляжут на сервер, отсюда их забирает
            // лечение группы при заборе копии.
            repeat(ASK_TRIES) {
                delay(ASK_PAUSE_MS)
                val pulled = turn.withLock { sync.pull() }
                note("забрать после просьбы о ключе", pulled)
                if (pulled != CopyStep.NoKey && pulled !is CopyStep.Offline) {
                    _keyAsk.value = KeyAsk.Got
                    turn.withLock {
                        pullReads("забрать после просьбы о ключе")
                        pushReads("отдать после просьбы о ключе")
                    }
                    return@launch
                }
            }
            _keyAsk.value = KeyAsk.NoAnswer
        }
    }

    /** Одна просьба о ключе служебной группы, подписанная словами. `null` — не ушла. */
    private suspend fun askKey(words: List<String>): io.tima.domain.chat.RecoveryStep? {
        val request = keyRequest ?: return null
        val gid = groupId ?: store.storeGroup()?.also { groupId = it } ?: return null
        val step = request.request(gid, words)
        when (step) {
            is io.tima.domain.chat.RecoveryStep.Requested -> {
                if (step.helpers == 0) {
                    Journal.trouble(COPY, "ключ служебной группы попрошен фразой — ответить некому: других устройств на связи нет")
                } else {
                    Journal.note(COPY, "ключ служебной группы попрошен фразой", "версий" to step.versions, "устройств" to step.helpers)
                }
            }
            io.tima.domain.chat.RecoveryStep.NeedsSecretPhrase ->
                Journal.trouble(COPY, "ключ служебной группы: сервер не принял подпись фразой")
            else ->
                Journal.trouble(COPY, "ключ служебной группы: просьба не ушла", "исход" to step.toString())
        }
        return step
    }

    /** Забрать то, о чём сервер сказал, что оно новее нашего. */
    private fun catchUp(why: String) {
        val known = changes?.value ?: return
        val bookNewer = (known[KIND_BOOK] ?: 0) > memory.last()
        val readsNewer = (known[KIND_READS] ?: 0) > readsMemory.last()
        if (!bookNewer && !readsNewer) return
        scope.launch {
            turn.withLock {
                if (bookNewer && (changes.value[KIND_BOOK] ?: 0) > memory.last()) note("забрать $why", sync.pull())
                if (readsNewer && (changes.value[KIND_READS] ?: 0) > readsMemory.last()) pullReads("забрать $why")
            }
        }
    }

    private suspend fun pullReads(what: String) {
        val r = reads ?: return
        val step = r.pull()
        noteReads(what, step)
        if (step is ReadsStep.Pulled) applyReads(step.advanced)
    }

    private suspend fun pushReads(what: String) {
        val r = reads ?: return
        val step = r.push()
        noteReads(what, step)
        if (step is ReadsStep.Pushed) applyReads(step.advanced)
    }

    /**
     * Прочитано на другом устройстве — отметить прочитанным до отметки и снять числа, если
     * непрочитанного в переписке не осталось. Пришедшее позже отметки остаётся новым: там
     * его ещё не видели.
     */
    private fun applyReads(advanced: Map<String, Long>) {
        val state = readState ?: return
        for ((chatId, upto) in advanced) {
            state.markReadUpTo(chatId, upto)
            if (state.unread(chatId) == 0L) onReadElsewhere(chatId)
        }
    }

    private fun note(what: String, step: CopyStep) {
        // Ключа нет — «Запросить ключ» в «Секретная фраза и устройства»; есть — кнопка уходит.
        when (step) {
            CopyStep.NoKey -> _keyMissing.value = true
            CopyStep.Offline -> Unit
            is CopyStep.Refused -> Unit
            else -> _keyMissing.value = false
        }
        when (step) {
            // Сервер без ручек копии (до выкатки 0051) отвечает 404 — это не беда, а
            // возраст сервера: отмечается, но бедой не считается.
            is CopyStep.Refused -> if (step.reason == "http_404") {
                Journal.note(COPY, "копия книги: $what — сервер копии не держит")
            } else {
                Journal.trouble(COPY, "копия книги: $what — отказ", "причина" to step.reason)
            }
            CopyStep.Offline -> Journal.note(COPY, "копия книги: $what — без сети")
            CopyStep.NoKey -> Journal.note(COPY, "копия книги: $what — ключа служебной группы нет")
            CopyStep.Nothing -> Journal.note(COPY, "копия книги: $what — у сервера копии ещё нет")
            // Ничего не менялось — в журнале не нужно: строка на каждую сверку с сервером
            // и была бы тем шумом, ради которого отпечаток заведён.
            CopyStep.Unchanged -> Unit
            is CopyStep.Pulled -> Journal.note(COPY, "копия книги: $what — принята", "ревизия" to step.revision, "с" to step.from)
            is CopyStep.Pushed -> Journal.note(COPY, "копия книги: $what — отдана", "ревизия" to step.revision)
        }
    }

    private fun noteReads(what: String, step: ReadsStep) {
        when (step) {
            // Сервер до ЖУ9 вида `reads` не знает — это возраст сервера, не беда.
            is ReadsStep.Refused -> if (step.reason == "http_404" || step.reason.contains("unknown_kind")) {
                Journal.note(COPY, "отметки прочтения: $what — сервер вида не держит")
            } else {
                Journal.trouble(COPY, "отметки прочтения: $what — отказ", "причина" to step.reason)
            }
            ReadsStep.Offline -> Journal.note(COPY, "отметки прочтения: $what — без сети")
            ReadsStep.NoKey -> Journal.note(COPY, "отметки прочтения: $what — ключа служебной группы нет")
            ReadsStep.Nothing, ReadsStep.Unchanged -> Unit
            is ReadsStep.Pulled -> Journal.note(
                COPY, "отметки прочтения: $what — приняты",
                "ревизия" to step.revision, "с" to step.from, "сдвинуто" to step.advanced.size,
            )
            is ReadsStep.Pushed -> Journal.note(COPY, "отметки прочтения: $what — отданы", "ревизия" to step.revision)
        }
    }

    private companion object {
        /** Ответа на просьбу о ключе ждём до трёх минут: 12 раз по 15 с. */
        const val ASK_TRIES = 12
        const val ASK_PAUSE_MS = 15_000L
        const val COPY = LogCode.BOOK_COPY
        const val KIND_BOOK = "book"
        const val KIND_READS = "reads"
        const val BOOK_REVISION = "book.copy.revision"
        const val READS_REVISION = "reads.copy.revision"
        const val BOOK_PRINT = "book.copy.print"
    }
}

/**
 * Последняя ревизия сервера — в настройках экранов: они уже есть и переживают перезапуск.
 * Чтение — один раз при старте, дальше в памяти; запись — сразу, в фоне.
 */
private class SettingsRevisionMemory(
    private val settings: Settings,
    private val scope: CoroutineScope,
    private val key: String,
) : RevisionMemory {
    @kotlin.concurrent.Volatile
    private var value = 0L

    suspend fun load() {
        value = settings.all().first()[key]?.toLongOrNull() ?: 0L
    }

    override fun last(): Long = value

    override fun remember(revision: Long) {
        value = revision
        scope.launch { settings.put(key, revision.toString()) }
    }
}

/** Что вышло с просьбой о ключе служебной группы по кнопке «Запросить ключ». */
sealed interface KeyAsk {
    data object Idle : KeyAsk
    data object Sending : KeyAsk

    /** Ушла; ответят [helpers] устройств на связи — ждём. */
    data class Asked(val helpers: Int) : KeyAsk
    data object NoHelpers : KeyAsk
    data object WrongPhrase : KeyAsk
    data object Got : KeyAsk

    /** Просьба ушла, а ключ за три минуты так и не пришёл. */
    data object NoAnswer : KeyAsk
    data class Failed(val reason: String) : KeyAsk
}
