package io.tima.domain.chat

/**
 * Синхронизация книги и разделов между устройствами человека (ПЛАН-РАЗДЕЛОВ Р2а).
 *
 * ── КАК УСТРОЕНО ────────────────────────────────────────────────────────────
 *
 * Сервер держит одну ячейку на аккаунт: блоб, ревизию, устройство. Блоб зашифрован ключом
 * **служебной группы аккаунта** — той же механикой, что личная группа: ключ заворачивается
 * на каждое устройство человека, сервер его не видит. Ключ достаёт [key]; кто его выпускает
 * и лечит — забота оркестратора ключей, здесь он просто есть или его нет.
 *
 * ── ДВА ХОДА ────────────────────────────────────────────────────────────────
 *
 * **Забрать** ([pull]): взять блоб, открыть, применить записи моложе наших. Ревизия
 * запоминается — от неё считается следующая запись.
 *
 * **Отдать** ([push]): собрать снимок, закрыть, отправить ревизией «запомненная + 1». Сервер
 * ответил 409 — кто-то сохранил раньше: применить его записи, запомнить его ревизию и
 * отправить снова, теперь уже слитое. Повтор один: второй отказ подряд — не гонка, а
 * поломка, и её надо показать, а не замазать.
 *
 * ── ЧЕГО ЗДЕСЬ НЕТ ──────────────────────────────────────────────────────────
 *
 * Расписания. Когда забирать и когда отдавать — решает вызывающий: при запуске, после
 * правки, по кадру сервера. Здесь только сами ходы, чтобы их можно было проверить без
 * сети, часов и телефона.
 */
class SyncBookCopy(
    private val copy: BookCopyPort,
    private val store: AccountStorePort,
    private val codec: BookCopyCodec,
    /** Ключ служебной группы — или `null`, когда его пока нет: тогда ходов не делаем. */
    private val key: suspend () -> ByteArray?,
    /** Запомненная ревизия сервера: от неё считается следующая. */
    private val revision: RevisionMemory,
    private val device: () -> String,
) {

    suspend fun pull(): CopyStep {
        val k = key() ?: return CopyStep.NoKey
        return when (val fetched = store.fetch()) {
            AccountStoreStep.Empty -> {
                revision.remember(0)
                CopyStep.Nothing
            }
            is AccountStoreStep.Blob -> applyRemote(k, fetched)
            is AccountStoreStep.Offline -> CopyStep.Offline
            is AccountStoreStep.Refused -> CopyStep.Refused(fetched.reason)
            is AccountStoreStep.Conflict, AccountStoreStep.Stored -> CopyStep.Refused("неожиданный ответ на чтение")
        }
    }

    suspend fun push(): CopyStep {
        val k = key() ?: return CopyStep.NoKey
        var attempts = 0
        while (true) {
            val next = revision.last() + 1
            val snapshot = copy.snapshot().copy(revision = next, device = device())
            val sealed = codec.seal(k, snapshot) ?: return CopyStep.Refused("не удалось закрыть копию")
            when (val sent = store.put(next, sealed)) {
                AccountStoreStep.Stored -> {
                    revision.remember(next)
                    return CopyStep.Pushed(next)
                }
                is AccountStoreStep.Conflict -> {
                    // Кто-то сохранил раньше. Его записи — к нам (моложе наших победят),
                    // его ревизия — в память, и ещё одна попытка со слитым.
                    val applied = applyRemote(k, sent.current)
                    if (applied is CopyStep.Refused) return applied
                    if (++attempts >= 2) return CopyStep.Refused("ревизия расходится дважды подряд")
                }
                is AccountStoreStep.Offline -> return CopyStep.Offline
                is AccountStoreStep.Refused -> return CopyStep.Refused(sent.reason)
                AccountStoreStep.Empty, is AccountStoreStep.Blob -> return CopyStep.Refused("неожиданный ответ на запись")
            }
        }
    }

    private suspend fun applyRemote(k: ByteArray, blob: AccountStoreStep.Blob): CopyStep {
        val theirs = codec.open(k, blob.bytes) ?: return CopyStep.Refused("копия не открылась: чужой ключ или порча")
        // Ревизия внутри блоба обязана совпадать с ревизией снаружи — иначе сервер (или
        // кто-то между) подменил блоб. Тогда не применяем: чужая книга хуже отсутствия копии.
        if (theirs.revision != blob.revision) return CopyStep.Refused("ревизия внутри копии не совпадает с внешней")
        copy.apply(theirs)
        revision.remember(blob.revision)
        return CopyStep.Pulled(blob.revision, from = blob.device)
    }
}

sealed interface CopyStep {
    /** Копии у сервера ещё нет. */
    data object Nothing : CopyStep
    data class Pulled(val revision: Long, val from: String) : CopyStep
    data class Pushed(val revision: Long) : CopyStep
    /** Ключа служебной группы пока нет — ходов не делали. */
    data object NoKey : CopyStep
    data object Offline : CopyStep
    data class Refused(val reason: String) : CopyStep
}

// ── порты ───────────────────────────────────────────────────────────────────

/** Ячейка копии у сервера. Реализуется `core-network`. */
interface AccountStorePort {
    suspend fun fetch(): AccountStoreStep
    suspend fun put(revision: Long, blob: ByteArray): AccountStoreStep
}

sealed interface AccountStoreStep {
    data object Empty : AccountStoreStep
    data class Blob(val revision: Long, val device: String, val bytes: ByteArray) : AccountStoreStep
    data object Stored : AccountStoreStep
    /** Ревизия не следующая; в ответе — текущее. */
    data class Conflict(val current: Blob) : AccountStoreStep
    data object Offline : AccountStoreStep
    data class Refused(val reason: String) : AccountStoreStep
}

/** Закрыть и открыть копию ключом. Реализуется `core-encryption`. */
interface BookCopyCodec {
    fun seal(key: ByteArray, copy: BookCopy): ByteArray?
    fun open(key: ByteArray, sealed: ByteArray): BookCopy?
}

/** Где помнится последняя ревизия сервера. Реализуется настройками. */
interface RevisionMemory {
    fun last(): Long
    fun remember(revision: Long)
}
