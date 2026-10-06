package io.tima.domain.chat

/**
 * Синхронизация книги и разделов между устройствами человека (ПЛАН-(РЗ)-РАЗДЕЛОВ Р2а).
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
    /**
     * Отпечаток последнего отданного — **переживает перезапуск** (ПЛАН-(ЖУ)-ЖУРНАЛА-УВЕДОМЛЕНИЙ.md,
     * ЖУ9). В памяти он терялся, и каждый запуск отдавал ту же книгу новой ревизией: Redmi
     * 2026-09-30 — 127 → 128 → 129 за четыре минуты без единой правки.
     */
    private val lastPrint: () -> Int? = { null },
    private val rememberPrint: (Int) -> Unit = {},
    /**
     * Прежние версии ключа служебной группы, от новой к старой. Копия запечатана той версией,
     * что была последней у писавшего; после смены ключа (эпоха, новый участник) читающий
     * открыл бы её только прежней. Без них копия застревала навсегда: realme 2026-10-05 не
     * открывал свою же книгу от 09-30 (версия 1) ключом версии 2 от 10-02 — и записать новую
     * тоже не мог, запись начинается со слияния.
     */
    private val olderKeys: suspend () -> List<ByteArray> = { emptyList() },
    /**
     * Прежний блоб, который затирает [restart] (Р56): его открыть пока нечем, но ключ ещё может
     * прийти — тогда [adopt] сольёт его с книгой, а не потеряет. Хранит вызывающий.
     */
    private val keepOrphan: suspend (ByteArray) -> Unit = {},
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

    /** Отпечаток последнего отданного: одно и то же дважды не гоняем. */
    private var pushed: Int? = null
    private var printRead = false

    private fun remembered(print: Int) {
        pushed = print
        rememberPrint(print)
    }

    /**
     * Начать копию заново (Р52): записать свою книгу поверх серверной, не сливая — серверную
     * открыть нечем, ключа служебной группы, которым её закрыли, нет ни у одного своего устройства.
     */
    suspend fun restart(): CopyStep {
        val k = key() ?: return CopyStep.NoKey
        val base = when (val fetched = store.fetch()) {
            AccountStoreStep.Empty -> 0L
            is AccountStoreStep.Blob -> {
                // Прежняя копия уходит с сервера, но не пропадает: если её ключ ещё придёт —
                // сольём (Р56).
                keepOrphan(fetched.bytes)
                fetched.revision
            }
            is AccountStoreStep.Offline -> return CopyStep.Offline
            is AccountStoreStep.Refused -> return CopyStep.Refused(fetched.reason)
            is AccountStoreStep.Conflict, AccountStoreStep.Stored -> return CopyStep.Refused("неожиданный ответ на чтение")
        }
        val next = base + 1
        val plain = copy.snapshot()
        val sealed = codec.seal(k, plain.copy(revision = next, device = device())) ?: return CopyStep.Refused("не удалось закрыть копию")
        return when (val sent = store.put(next, sealed)) {
            AccountStoreStep.Stored -> {
                revision.remember(next)
                remembered(plain.copy(revision = 0, device = "").hashCode())
                CopyStep.Pushed(next)
            }
            is AccountStoreStep.Conflict -> CopyStep.Refused("копию переписали во время перезапуска")
            is AccountStoreStep.Offline -> CopyStep.Offline
            is AccountStoreStep.Refused -> CopyStep.Refused(sent.reason)
            AccountStoreStep.Empty, is AccountStoreStep.Blob -> CopyStep.Refused("неожиданный ответ на запись")
        }
    }

    /**
     * Слить прежнюю копию, затёртую [restart], с книгой и отдать слитое (Р56): ключ к ней пришёл
     * позже — от своего устройства, которое было не на связи. [CopyStep.NoKey] — открыть всё
     * ещё нечем, прежняя копия остаётся ждать.
     */
    suspend fun adopt(orphan: ByteArray): CopyStep {
        val k = key() ?: return CopyStep.NoKey
        val theirs = codec.open(k, orphan) ?: olderKeys().firstNotNullOfOrNull { codec.open(it, orphan) }
            ?: return CopyStep.NoKey
        // Ревизия прежней копии — прошлая: сверять её с нынешней ячейкой незачем. Записи
        // сливаются как всегда — моложе побеждает, надгробия держат убранное.
        copy.apply(theirs)
        return push()
    }

    suspend fun push(): CopyStep {
        val k = key() ?: return CopyStep.NoKey
        if (!printRead) {
            pushed = pushed ?: lastPrint()
            printRead = true
        }
        var attempts = 0
        while (true) {
            val next = revision.last() + 1
            val plain = copy.snapshot()
            // Книга меняется и без правок человека — сверка с сервером дописывает
            // user_id, — и каждое такое изменение звало отдачу. Содержимое копии при этом
            // то же самое: сравниваем и молчим.
            val print = plain.copy(revision = 0, device = "").hashCode()
            if (print == pushed) return CopyStep.Unchanged
            val snapshot = plain.copy(revision = next, device = device())
            val sealed = codec.seal(k, snapshot) ?: return CopyStep.Refused("не удалось закрыть копию")
            when (val sent = store.put(next, sealed)) {
                AccountStoreStep.Stored -> {
                    revision.remember(next)
                    remembered(print)
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
        val theirs = codec.open(k, blob.bytes)
            ?: olderKeys().firstNotNullOfOrNull { codec.open(it, blob.bytes) }
            ?: return CopyStep.Refused("копия не открылась: чужой ключ или порча")
        // Ревизия внутри блоба обязана совпадать с ревизией снаружи — иначе сервер (или
        // кто-то между) подменил блоб. Тогда не применяем: чужая книга хуже отсутствия копии.
        if (theirs.revision != blob.revision) return CopyStep.Refused("ревизия внутри копии не совпадает с внешней")
        copy.apply(theirs)
        revision.remember(blob.revision)
        // Если после приёма наша книга совпала с принятой — отдавать нечего: иначе каждый
        // запуск отвечал бы серверу его же копией под новой ревизией.
        if (copy.snapshot().copy(revision = 0, device = "") == theirs.copy(revision = 0, device = "")) {
            remembered(theirs.copy(revision = 0, device = "").hashCode())
        }
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
    /** Содержимое то же, что уже отдано, — отдавать нечего. */
    data object Unchanged : CopyStep
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
