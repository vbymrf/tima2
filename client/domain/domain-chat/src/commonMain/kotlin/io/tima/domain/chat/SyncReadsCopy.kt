package io.tima.domain.chat

/**
 * Отметки «просмотрено до» между устройствами человека — копия аккаунта, вид `reads`
 * (ПЛАН-(ЖУ)-ЖУРНАЛА-УВЕДОМЛЕНИЙ.md, ЖУ9, решение заказчика 2026-09-30).
 *
 * ── ЗАЧЕМ ───────────────────────────────────────────────────────────────────
 *
 * Прочитанное на телефоне снимает числа на ПК. До этого «прочитано» жило только в базе
 * своего устройства: прочитали на телефоне — на ПК число висело.
 *
 * ── КАК УСТРОЕНО ────────────────────────────────────────────────────────────
 *
 * Та же ячейка, что у книги ([SyncBookCopy]): блоб под ключом служебной группы, ревизия,
 * устройство. Внутри — «переписка → просмотрено до» (время последнего входящего,
 * `client_ts`). **Слияние — большее по каждой переписке**: порядок прихода неважен, двух
 * правд быть не может. Поэтому и 409 решается просто: слить чужое со своим и отправить.
 *
 * Когда ходить — решает вызывающий: одна отправка за сессию, забор — по событию сервера
 * при выходе на экран. Здесь только сами ходы.
 */
class SyncReadsCopy(
    private val store: AccountStorePort,
    private val codec: ReadsCopyCodec,
    private val key: suspend () -> ByteArray?,
    private val revision: RevisionMemory,
    private val device: () -> String,
    private val marks: ReadMarksPort,
    /** Прежние версии ключа служебной группы, от новой к старой, — как у [SyncBookCopy]. */
    private val olderKeys: suspend () -> List<ByteArray> = { emptyList() },
) {

    suspend fun pull(): ReadsStep {
        val k = key() ?: return ReadsStep.NoKey
        return when (val fetched = store.fetch()) {
            AccountStoreStep.Empty -> {
                revision.remember(0)
                ReadsStep.Nothing
            }
            is AccountStoreStep.Blob -> applyRemote(k, fetched)
            is AccountStoreStep.Offline -> ReadsStep.Offline
            is AccountStoreStep.Refused -> ReadsStep.Refused(fetched.reason)
            is AccountStoreStep.Conflict, AccountStoreStep.Stored -> ReadsStep.Refused("неожиданный ответ на чтение")
        }
    }

    /**
     * Отдать свои отметки, если они изменились с прошлой отправки.
     *
     * @return [ReadsStep.Pushed] — отдано; [ReadsStep.Unchanged] — отдавать нечего. Если при
     *   отправке пришлось слить чужое, сдвинутые им переписки — в [ReadsStep.Pushed.advanced].
     */
    /** Начать отметки заново поверх серверных, которые нечем открыть (Р52), — как [SyncBookCopy.restart]. */
    suspend fun restart(): ReadsStep {
        val k = key() ?: return ReadsStep.NoKey
        val base = when (val fetched = store.fetch()) {
            AccountStoreStep.Empty -> 0L
            is AccountStoreStep.Blob -> fetched.revision
            is AccountStoreStep.Offline -> return ReadsStep.Offline
            is AccountStoreStep.Refused -> return ReadsStep.Refused(fetched.reason)
            is AccountStoreStep.Conflict, AccountStoreStep.Stored -> return ReadsStep.Refused("неожиданный ответ на чтение")
        }
        val next = base + 1
        val sealed = codec.seal(k, ReadsCopy(next, device(), marks.marks())) ?: return ReadsStep.Refused("не удалось закрыть отметки")
        return when (val sent = store.put(next, sealed)) {
            AccountStoreStep.Stored -> {
                revision.remember(next)
                marks.sent()
                ReadsStep.Pushed(next, emptyMap())
            }
            is AccountStoreStep.Conflict -> ReadsStep.Refused("отметки переписали во время перезапуска")
            is AccountStoreStep.Offline -> ReadsStep.Offline
            is AccountStoreStep.Refused -> ReadsStep.Refused(sent.reason)
            AccountStoreStep.Empty, is AccountStoreStep.Blob -> ReadsStep.Refused("неожиданный ответ на запись")
        }
    }

    suspend fun push(): ReadsStep {
        // Сначала — есть ли что отдавать, и только потом ключ: ключ служебной группы — запрос к
        // серверу, и до 2026-10-06 он уходил при каждом уходе в фон, даже без новых отметок
        // (отчёт DGAR: 51 запрос ключей группы за сутки).
        if (!marks.dirty()) return ReadsStep.Unchanged
        val k = key() ?: return ReadsStep.NoKey
        var advanced = emptyMap<String, Long>()
        var attempts = 0
        while (true) {
            val next = revision.last() + 1
            val sealed = codec.seal(k, ReadsCopy(next, device(), marks.marks()))
                ?: return ReadsStep.Refused("не удалось закрыть отметки")
            when (val sent = store.put(next, sealed)) {
                AccountStoreStep.Stored -> {
                    revision.remember(next)
                    marks.sent()
                    return ReadsStep.Pushed(next, advanced)
                }
                is AccountStoreStep.Conflict -> {
                    val applied = applyRemote(k, sent.current)
                    if (applied !is ReadsStep.Pulled) return applied
                    advanced = advanced + applied.advanced
                    if (++attempts >= 2) return ReadsStep.Refused("ревизия расходится дважды подряд")
                }
                is AccountStoreStep.Offline -> return ReadsStep.Offline
                is AccountStoreStep.Refused -> return ReadsStep.Refused(sent.reason)
                AccountStoreStep.Empty, is AccountStoreStep.Blob -> return ReadsStep.Refused("неожиданный ответ на запись")
            }
        }
    }

    private suspend fun applyRemote(k: ByteArray, blob: AccountStoreStep.Blob): ReadsStep {
        val theirs = codec.open(k, blob.bytes)
            ?: olderKeys().firstNotNullOfOrNull { codec.open(it, blob.bytes) }
            ?: return ReadsStep.Refused("отметки не открылись: чужой ключ или порча")
        // Ревизия внутри обязана совпадать с внешней — иначе блоб подменили.
        if (theirs.revision != blob.revision) return ReadsStep.Refused("ревизия внутри отметок не совпадает с внешней")
        val advanced = marks.merge(theirs.marks)
        revision.remember(blob.revision)
        return ReadsStep.Pulled(blob.revision, from = blob.device, advanced = advanced)
    }
}

/** Отметки «просмотрено до»: переписка → время последнего входящего, которое видели. */
data class ReadsCopy(val revision: Long, val device: String, val marks: Map<String, Long>)

sealed interface ReadsStep {
    data object Nothing : ReadsStep

    /** Принято; [advanced] — переписки, чья отметка сдвинулась вперёд: их снять с чисел. */
    data class Pulled(val revision: Long, val from: String, val advanced: Map<String, Long>) : ReadsStep
    data class Pushed(val revision: Long, val advanced: Map<String, Long>) : ReadsStep
    data object Unchanged : ReadsStep
    data object NoKey : ReadsStep
    data object Offline : ReadsStep
    data class Refused(val reason: String) : ReadsStep
}

/** Закрыть и открыть отметки ключом. Реализуется `core-encryption`. */
interface ReadsCopyCodec {
    fun seal(key: ByteArray, copy: ReadsCopy): ByteArray?
    fun open(key: ByteArray, sealed: ByteArray): ReadsCopy?
}

/** Отметки в базе устройства. Реализуется `core-database`. */
interface ReadMarksPort {
    fun marks(): Map<String, Long>

    /** Есть ли своё, ещё не отданное. */
    fun dirty(): Boolean

    /** Отдано — снять пометку. */
    fun sent()

    /** Слить пришедшее — большее по каждой переписке. @return сдвинутые вперёд. */
    fun merge(remote: Map<String, Long>): Map<String, Long>
}
