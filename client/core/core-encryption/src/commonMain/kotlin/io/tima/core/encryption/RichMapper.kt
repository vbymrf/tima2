package io.tima.core.encryption

import io.tima.crypto.BlockType
import io.tima.crypto.EntityType
import io.tima.crypto.Markup
import io.tima.crypto.MarkupBlock
import io.tima.crypto.MarkupRules
import io.tima.crypto.MessageContent
import io.tima.domain.chat.Bullet
import io.tima.domain.chat.RichBlock
import io.tima.domain.chat.RichContent
import io.tima.domain.chat.RichSpan
import io.tima.domain.chat.RichText
import io.tima.domain.chat.SpanKind
import io.tima.domain.chat.TableCell

/**
 * Разметка тела → модель экрана (ПЛАН-(РС) РС4). Только здесь `io.tima.crypto` встречается с
 * `io.tima.domain.chat`: выше `core-encryption` крипто-модуль не поднимается.
 *
 * **Мягкая проверка** (`MarkupRules`, strict = false): нарушены пределы или сломана структура —
 * оформления нет, сообщение показывается текстом (ADR-0011: испорченная разметка не теряет
 * сообщение). Незнакомое от более новой версии — не ошибка: такой блок показывается текстом
 * своих узлов ([RichBlock.Fallback]).
 */
internal object RichMapper {

    fun from(content: MessageContent): RichContent? {
        val markup = content.markup ?: return null
        if (MarkupRules.check(content, strict = false).isNotEmpty()) return null
        val byId = markup.n.mapIndexed { index, id -> id to index }.toMap()
        fun nodeText(id: Int): String = byId[id]?.let { content.nodes.getOrNull(it) }.orEmpty()
        fun rich(id: Int): RichText = richText(nodeText(id), id, markup)

        val blocks = markup.blocks.map { block ->
            when (block.type) {
                BlockType.PARAGRAPH -> RichBlock.Paragraph(rich(block.nodeIds.firstOrNull() ?: -1))
                BlockType.HEADING -> RichBlock.Heading(block.level.coerceIn(1, 3), rich(block.nodeIds.firstOrNull() ?: -1))
                BlockType.QUOTE -> RichBlock.Quote(block.nodeIds.map(::rich))
                BlockType.LIST -> RichBlock.Bullets(bullets(block, ::rich))
                BlockType.CODE_BLOCK -> RichBlock.CodeBlock(nodeText(block.nodeIds.firstOrNull() ?: -1))
                BlockType.DIVIDER -> RichBlock.Divider
                BlockType.TABLE -> RichBlock.Table(
                    block.children.map { row ->
                        row.children.map { cell ->
                            TableCell(
                                text = cell.nodeIds.firstOrNull()?.let(::rich),
                                align = cell.style["align"],
                                merge = cell.style["merge"],
                            )
                        }
                    },
                )
                BlockType.REPLY -> block.widget?.ref?.let { RichBlock.Reply(block.widget!!.userId, it.container, it.message) }
                    ?: RichBlock.Fallback("")
                // Ряд, виджеты, подкладка, незнакомое — пока текстом своих узлов (виджеты — РС4/РС6).
                else -> RichBlock.Fallback(allNodes(block).joinToString("\n") { nodeText(it) }.trim())
            }
        }.filterNot { it is RichBlock.Fallback && it.text.isEmpty() }
        return RichContent(blocks)
    }

    private fun bullets(list: MarkupBlock, rich: (Int) -> RichText): List<Bullet> =
        list.children.filter { it.type == BlockType.LIST_ITEM }.map { item ->
            Bullet(
                text = rich(item.nodeIds.firstOrNull() ?: -1),
                children = item.children.filter { it.type == BlockType.LIST }.flatMap { bullets(it, rich) },
            )
        }

    private fun allNodes(block: MarkupBlock): List<Int> = block.nodeIds + block.children.flatMap(::allNodes)

    private fun richText(text: String, nodeId: Int, markup: Markup): RichText {
        val spans = markup.entities.filter { it.nodeId == nodeId }.mapNotNull { e ->
            val kind = when (e.type) {
                EntityType.BOLD -> SpanKind.BOLD
                EntityType.ITALIC -> SpanKind.ITALIC
                EntityType.UNDERLINE -> SpanKind.UNDERLINE
                EntityType.STRIKETHROUGH -> SpanKind.STRIKE
                EntityType.HIGHLIGHT -> SpanKind.HIGHLIGHT
                EntityType.CODE -> SpanKind.CODE
                EntityType.LINK -> SpanKind.LINK
                EntityType.MENTION -> SpanKind.MENTION
                EntityType.HASHTAG -> SpanKind.HASHTAG
                EntityType.UNKNOWN -> null
            } ?: return@mapNotNull null
            val start = e.start.coerceIn(0, text.length)
            val end = (e.start + e.length).coerceIn(start, text.length)
            if (end <= start) null else RichSpan(kind, start, end, e.attribute.ifEmpty { e.url })
        }
        return RichText(text, spans)
    }
}
