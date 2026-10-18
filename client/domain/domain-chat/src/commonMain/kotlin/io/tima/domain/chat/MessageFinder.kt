package io.tima.domain.chat

/**
 * Сообщение по номеру (Решения §11.3–§11.4, ПЛАН-(РС) РС7): вставленное из подборки, подкладка, ответ и
 * переход к сообщению, которого нет в загруженной части переписки или которое в другой переписке.
 *
 * Сначала — устройство: сообщение могло давно лежать в базе. Нет — сервер, той же выдачей по номеру,
 * что и у остальных (`уровень ≤ право читателя`); не положено — заглушка «Недоступно» с причиной.
 */
sealed interface FoundMessage {
    data class Found(val line: ChatLine) : FoundMessage

    /**
     * Сервер не отдал: `level` — уровень выше права читателя ([level] назван), `missing` — удалено
     * или не было, `closed` — закрытый контур, подробностей нет.
     */
    data class Unavailable(val reason: String, val level: Int? = null) : FoundMessage

    /** Сети нет — спросить позже. */
    data object Offline : FoundMessage
}

fun interface MessageFinder {
    /** [kind] — `personal`, `group`, `channel` (вид ссылки в теле, ADR-0044). */
    suspend fun find(kind: String, chatId: String, serverId: Long): FoundMessage
}

/** Порт к базе устройства: строка по номеру. Реализуется `core-database`. */
fun interface LocalMessages {
    fun line(chatId: String, serverId: Long): ChatLine?
}

/** Ссылки на другие сообщения внутри тела: `(вид, переписка, номер)`. Ответ — всегда своей переписки. */
fun RichContent.references(ownChat: String, ownKind: String): List<Triple<String, String, Long>> {
    val out = mutableListOf<Triple<String, String, Long>>()
    fun walk(block: RichBlock) {
        when (block) {
            is RichBlock.Quoted -> out += Triple(block.kind, block.chatId, block.messageId)
            is RichBlock.Underlay -> out += Triple(block.kind, block.chatId, block.messageId)
            is RichBlock.Reply -> out += Triple(ownKind, block.chatId.ifEmpty { ownChat }, block.messageId)
            is RichBlock.Row -> block.cells.forEach { cell -> cell.widgets.forEach(::walk) }
            else -> Unit
        }
    }
    blocks.forEach(::walk)
    return out
}
