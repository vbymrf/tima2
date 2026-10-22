package io.tima.domain.chat

/**
 * Правка своего сообщения (ADR-0045; doc_mig/РЕДАКТОР-СООБЩЕНИЙ.md §8).
 *
 * Правка — новая запись с номером исходного, а не замена: внутри только изменённые узлы. Здесь —
 * правила продукта, которые проверяются до сети: своё, не больше трёх, не пустое, строение то же.
 * Остальное («до смены ключа», «исходное не удалено») знает сервер и отвечает отказом — тот
 * приходит в очередь, как у сообщения.
 */
class EditMessage(
    private val queue: EditQueue,
    private val bodies: MessageBodies,
    private val codec: MessageBodyCodec,
    private val keys: DedupKeys,
    private val maxBodyBytes: Int = 4 shl 20,
) {

    /** Что положить в строку ввода. `null` — правится не здесь (или исходного нет). */
    fun source(chatId: String, serverId: Long): String? {
        val original = bodies.original(chatId, serverId) ?: return null
        if (!original.own || original.edits.size >= MAX_EDITS) return null
        return codec.editSource(original.body, original.edits)
    }

    /**
     * Что открыть в редакторе: сообщение, которое строкой ввода не выразить (ряд, виджет). `null` —
     * не своё, правок уже три или документом не выражается.
     */
    fun document(chatId: String, serverId: Long): ComposedDoc? {
        val original = bodies.original(chatId, serverId) ?: return null
        if (!original.own || original.edits.size >= MAX_EDITS) return null
        return codec.editDoc(original.body, original.edits)
    }

    /**
     * Чужое или своё сообщение для пересылки (заказчик 2026-10-19): текст со знаками — `null`, если
     * знаками не выразить; тогда — документ редактора ([copyDocument]).
     */
    fun copySource(chatId: String, serverId: Long): String? {
        val original = bodies.original(chatId, serverId) ?: return null
        return codec.editSource(original.body, original.edits)
    }

    fun copyDocument(chatId: String, serverId: Long): ComposedDoc? {
        val original = bodies.original(chatId, serverId) ?: return null
        return codec.editDoc(original.body, original.edits)
    }

    fun edit(chatId: String, serverId: Long, typed: String, marks: Boolean = true): EditResult {
        if (typed.isBlank()) return checked(chatId, serverId) ?: EditResult.Empty
        return queued(chatId, serverId) { original -> codec.encodeEdit(original.body, original.edits, typed, marks) }
    }

    /** Правка из редактора: те же правила, что у строки ввода; меняются только тексты. */
    fun editDoc(chatId: String, serverId: Long, doc: ComposedDoc): EditResult {
        if (doc.isEmpty) return checked(chatId, serverId) ?: EditResult.Empty
        return queued(chatId, serverId) { original -> codec.encodeEditDoc(original.body, original.edits, doc) }
    }

    /**
     * Фото на месте [index] заменено (ADR-0052; заказчик 2026-10-22): одна правка на сеанс
     * просмотра — повороты и сохранения редактора копятся и уходят здесь одной записью. Предел тот
     * же — три; считается досылка, а не нажатия.
     */
    fun replaceMedia(chatId: String, serverId: Long, index: Int, media: OutMedia): EditResult =
        queued(chatId, serverId) { codec.encodeMediaEdit(index, media) }

    /** Можно ли ещё править: своё, отправлено, правок меньше трёх. Смену ключа знает сервер. */
    fun editable(chatId: String, serverId: Long): Boolean = serverId > 0 && checked(chatId, serverId) == null

    /** Отказ до тела правки: не отправлено, нет, не своё, правок уже три. `null` — можно. */
    private fun checked(chatId: String, serverId: Long): EditResult? {
        require(chatId.isNotBlank()) { "chatId пустой" }
        if (serverId <= 0) return EditResult.NotSent
        val original = bodies.original(chatId, serverId) ?: return EditResult.Missing
        if (!original.own) return EditResult.NotMine
        if (original.edits.size >= MAX_EDITS) return EditResult.Limit
        return null
    }

    private fun queued(chatId: String, serverId: Long, encode: (EditOriginal) -> EditBody): EditResult {
        checked(chatId, serverId)?.let { return it }
        val original = bodies.original(chatId, serverId) ?: return EditResult.Missing
        val body = when (val e = encode(original)) {
            is EditBody.Changes -> e.body
            EditBody.Same -> return EditResult.Same
            EditBody.Structure -> return EditResult.Structure
            EditBody.Unsupported -> return EditResult.Structure
        }
        if (body.size > maxBodyBytes) return EditResult.TooLarge(body.size, maxBodyBytes)
        val dedupKey = keys.newKey()
        require(dedupKey.isNotBlank()) { "ключ идемпотентности пустой" }
        // Круг правки — круг исходного: текст правки читает тот же круг (сервер настоит на том же).
        return if (queue.enqueueEdit(dedupKey, chatId, body, original.level, serverId)) {
            EditResult.Queued(dedupKey)
        } else {
            EditResult.AlreadyQueued(dedupKey)
        }
    }

    companion object {
        /** Не больше трёх правок на сообщение (Решения §8); четвёртую сервер не примет. */
        const val MAX_EDITS = 3
    }
}

/** Чем кончилась правка. */
sealed interface EditResult {
    data class Queued(val dedupKey: String) : EditResult
    data class AlreadyQueued(val dedupKey: String) : EditResult
    /** Сообщение ещё не на сервере — у него нет номера, и правке не на что ссылаться. */
    data object NotSent : EditResult
    data object Missing : EditResult
    data object NotMine : EditResult
    data object Limit : EditResult
    data object Empty : EditResult
    data object Same : EditResult
    /** Правка меняет строение — так нельзя (Решения §8 «разметка на месте»). */
    data object Structure : EditResult
    data class TooLarge(val bytes: Int, val limit: Int) : EditResult
}

/** Порт к очереди — правка уходит той же очередью, что и сообщение. Реализуется `core-outbox`. */
fun interface EditQueue {
    /** @return `false`, если такой `dedupKey` уже есть. */
    fun enqueueEdit(dedupKey: String, chatId: String, body: ByteArray, level: Int, editOf: Long): Boolean
}

/** Порт к телам переписки: исходное и его правки. Реализуется `core-database`. */
fun interface MessageBodies {
    fun original(chatId: String, serverId: Long): EditOriginal?
}

/**
 * Исходное для правки: тело, правки по порядку, круг и чьё оно.
 *
 * [own] — своё ли: править можно только своё (сервер проверит тоже, но кнопку незачем давать).
 */
class EditOriginal(val body: ByteArray, val edits: List<ByteArray>, val level: Int, val own: Boolean)
