package io.tima.crypto

/**
 * Ответ на сообщение (doc_mig/РЕДАКТОР-СООБЩЕНИЙ.md §7 «`@Ник` — ответ», §11.1 «Ответить»;
 * ADR-0044 — блок `reply`).
 *
 * Ответ — **блок разметки первым**, а не поле конверта: ссылка на сообщение едет внутри
 * зашифрованного тела, и сервер не видит, кто кому отвечает. Блок узлов не занимает: текст
 * ответа — узлы, как у любого сообщения, их номера не сдвигаются.
 */
object MessageReplies {

    /** Ссылка ответа и чьё сообщение. */
    data class Reply(val ref: MarkupRef, val userId: String)

    /** Содержимое с блоком ответа первым. Сообщение без разметки получает её: абзац на узел. */
    fun attach(content: MessageContent, reply: Reply): MessageContent {
        val block = MarkupBlock(type = BlockType.REPLY, widget = MarkupWidget(userId = reply.userId, ref = reply.ref))
        val markup = content.markup?.takeIf { content.hasMarkup }
        if (markup == null) {
            val nodes = content.nodes.ifEmpty { listOf("") }
            return content.copy(
                nodes = nodes,
                markup = Markup(
                    n = Markup.idsFor(nodes.size),
                    blocks = listOf(block) + nodes.indices.map { MarkupBlock(type = BlockType.PARAGRAPH, nodeIds = listOf(it + 1)) },
                ),
            )
        }
        return content.copy(markup = markup.copy(blocks = listOf(block) + markup.blocks.filter { it.type != BlockType.REPLY }))
    }

    /** Ответ, если сообщение им начинается. */
    fun of(content: MessageContent): Reply? {
        val block = content.markup?.blocks?.firstOrNull()?.takeIf { it.type == BlockType.REPLY } ?: return null
        val w = block.widget ?: return null
        return Reply(w.ref ?: return null, w.userId)
    }

    /**
     * Без блока ответа. Осталось ровно то, что даёт простой текст (один абзац без оформления), —
     * простой текст: так правка ответа считается теми же правилами, что правка обычного сообщения.
     */
    fun detach(content: MessageContent): MessageContent {
        val markup = content.markup ?: return content
        if (markup.blocks.firstOrNull()?.type != BlockType.REPLY) return content
        val rest = markup.copy(blocks = markup.blocks.drop(1))
        val plain = rest.entities.isEmpty() && rest.styles == null && content.nodes.size == 1 &&
            rest.blocks == listOf(MarkupBlock(type = BlockType.PARAGRAPH, nodeIds = rest.n))
        return if (plain) MessageContent.text(content.nodes.single(), content.media) else content.copy(markup = rest)
    }
}
