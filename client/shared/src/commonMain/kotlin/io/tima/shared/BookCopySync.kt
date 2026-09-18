package io.tima.shared

import io.tima.core.database.SqlBook
import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode
import io.tima.core.encryption.BookCopyCodecOverKodium
import io.tima.core.network.AccountStoreOverHttp
import io.tima.domain.chat.CopyStep
import io.tima.domain.chat.HealStep
import io.tima.domain.chat.RevisionMemory
import io.tima.domain.chat.Settings
import io.tima.domain.chat.SyncBookCopy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Копия книги и разделов между устройствами — сборка и расписание (ПЛАН-РАЗДЕЛОВ Р2а).
 *
 * ── КЛЮЧ ────────────────────────────────────────────────────────────────────
 *
 * Спрашивается у сервера служебная группа аккаунта, дальше — тот же путь, что у любой
 * группы без ключа: `HealGroupKey` забирает обёртки, а если ключа не было ни у кого,
 * выпускает первый. Своих ключевых механизмов у копии нет — решение заказчика 2026-09-18:
 * «ключ один, в анклав как у групп, не плодить логику».
 *
 * ── РАСПИСАНИЕ ──────────────────────────────────────────────────────────────
 *
 * При запуске — забрать, потом отдать (своё, чего у сервера ещё нет). После правки книги
 * или разделов — отдать, с задержкой в две секунды: человек правит несколько записей
 * подряд, и слать блоб на каждое нажатие значило бы гнать один и тот же блоб десять раз.
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
) {
    private val memory = SettingsRevisionMemory(environment.settings, scope)

    private val sync = SyncBookCopy(
        copy = environment.bookStorage as SqlBook,
        store = store,
        codec = BookCopyCodecOverKodium,
        key = { storeKey() },
        revision = memory,
        device = { deviceId },
    )

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

    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            memory.load()
            note("забрать при запуске", sync.pull())
            note("отдать при запуске", sync.push())
        }
        // После правки — отдать. Первая пара значений потоков — это чтение при запуске, а
        // не правка: пропускается.
        combine(environment.bookStorage.list(), environment.bookStorage.sections()) { _, _ -> Unit }
            .drop(1)
            .debounce(2_000)
            .onEach { note("отдать после правки", sync.push()) }
            .launchIn(scope)
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
            is CopyStep.Pulled -> Journal.note(COPY, "копия книги: $what — принята", "ревизия" to step.revision, "с" to step.from)
            is CopyStep.Pushed -> Journal.note(COPY, "копия книги: $what — отдана", "ревизия" to step.revision)
        }
    }

    private companion object {
        const val COPY = LogCode.BOOK_COPY
    }
}

/**
 * Последняя ревизия сервера — в настройках экранов: они уже есть и переживают перезапуск.
 * Чтение — один раз при старте, дальше в памяти; запись — сразу, в фоне.
 */
private class SettingsRevisionMemory(
    private val settings: Settings,
    private val scope: CoroutineScope,
) : RevisionMemory {
    private var value = 0L

    suspend fun load() {
        value = settings.all().first()[KEY]?.toLongOrNull() ?: 0L
    }

    override fun last(): Long = value

    override fun remember(revision: Long) {
        value = revision
        scope.launch { settings.put(KEY, revision.toString()) }
    }

    private companion object {
        const val KEY = "book.copy.revision"
    }
}
