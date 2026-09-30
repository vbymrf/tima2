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
 * Копия аккаунта между устройствами — книга с разделами (ПЛАН-РАЗДЕЛОВ Р2а) и отметки
 * «просмотрено до» (ПЛАН-ЖУРНАЛА-УВЕДОМЛЕНИЙ.md, ЖУ9). Сборка и расписание.
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

    fun start() {
        scope.launch {
            memory.load()
            readsMemory.load()
            bookPrint = environment.settings.all().first()[BOOK_PRINT]?.toIntOrNull()
            // Сеть при запуске часто ещё не поднялась (Redmi 2026-09-18: UnknownHost в
            // первые секунды после пробуждения). Без сети или без ключа — повторить с
            // растущей паузой, а не ждать следующего запуска.
            var pause = 5_000L
            repeat(4) { attempt ->
                val pulled = turn.withLock { sync.pull() }
                note("забрать при запуске", pulled)
                if (pulled !is CopyStep.Offline && pulled != CopyStep.NoKey) {
                    turn.withLock {
                        note("отдать при запуске", sync.push())
                        pullReads("забрать при запуске")
                        pushReads("отдать при запуске")
                    }
                    return@launch
                }
                if (attempt < 3) delay(pause)
                pause *= 3
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
