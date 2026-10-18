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

    fun edit(chatId: String, serverId: Long, typed: String, marks: Boolean = true): EditResult {
        require(chatId.isNotBlank()) { "chatId пустой" }
        if (serverId <= 0) return EditResult.NotSent
        val original = bodies.original(chatId, serverId) ?: return EditResult.Missing
        if (!original.own) return EditResult.NotMine
        if (original.edits.size >= MAX_EDITS) return EditResult.Limit
        if (typed.isBlank()) return EditResult.Empty
        val body = when (val e = codec.encodeEdit(original.body, original.edits, typed, marks)) {
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
