package io.tima.core.encryption

import io.tima.crypto.BlockType
import io.tima.crypto.Markup
import io.tima.crypto.MarkupBlock
import io.tima.crypto.MessageContent
import io.tima.crypto.QuickMarkdown
import io.tima.crypto.proto.MediaRef
import io.tima.domain.chat.ComposedCell
import io.tima.domain.chat.ComposedDoc
import io.tima.domain.chat.ComposedItem
import io.tima.domain.chat.ComposedWidget
import io.tima.domain.chat.OutMedia
import io.tima.domain.chat.ReplyTarget

/**
 * Обратный ход: сообщение → документ редактора — для правки того, что знаками строки ввода не
 * выразить (ряды, виджеты; заказчик 2026-10-18: «Редактор не открывает»).
 *
 * Обратное к [ComposedBodiesOverKodium.content]: текстовый блок — текстовый кусок со знаками, ряд из
 * одной ячейки 100 % с одним виджетом — виджет во всю ширину,
 * остальные ряды — рядами. Не выразилось — `null`; вызывающий ещё сверяет, что документ собирается
 * обратно в то же строение.
 */
internal object ComposedFromContent {

    private val WIDGETS = setOf(
        BlockType.IMAGE, BlockType.LINK, BlockType.CLOUD, BlockType.MAP, BlockType.CONTACT,
        BlockType.REPLY, BlockType.QUOTED, BlockType.EMOJI, BlockType.UNDERLAY,
    )

    fun doc(content: MessageContent): ComposedDoc? {
        val markup = content.markup?.takeIf { content.hasMarkup }
            ?: return ComposedDoc(listOf(ComposedItem.Text(content.plainText())))
        // Файлы вне виджетов (фото альбомом) документ не покажет — такое не правится редактором.
        val used = mutableSetOf<Int>()
        fun collect(b: MarkupBlock) {
            b.widget?.media?.takeIf { it >= 0 }?.let(used::add)
            b.children.forEach(::collect)
        }
        markup.blocks.forEach(::collect)
        if (content.media.indices.any { it !in used }) return null

        val items = mutableListOf<ComposedItem>()
        for (b in markup.blocks) {
            items += when {
                b.type == BlockType.FLEX -> flex(content, markup, b) ?: return null
                b.type in WIDGETS -> ComposedItem.Widget(widget(content, b) ?: return null, b.style)
                // Таблица быстрого оформления — только в старых сообщениях: в редакторе её больше нет
                // (заказчик 2026-10-18), такое сообщение показывается как раньше и не правится.
                b.type == BlockType.TABLE -> return null
                // Каждый текстовый блок — свой кусок: склеенные, два простых абзаца разобрались бы
                // обратно одним абзацем, и строение разошлось бы.
                else -> ComposedItem.Text(textOf(content, markup, listOf(b.copy(style = emptyMap()))) ?: return null, b.style)
            }
        }
        return ComposedDoc(items.ifEmpty { listOf(ComposedItem.Text("")) })
    }

    private fun flex(content: MessageContent, markup: Markup, b: MarkupBlock): ComposedItem? {
        val single = b.children.singleOrNull()
        val lone = single?.children?.singleOrNull()
        if (single != null && single.type == BlockType.CELL && single.nodeIds.isEmpty() &&
            single.style == mapOf("width" to "100") && lone != null && lone.type in WIDGETS
        ) {
            return ComposedItem.Widget(widget(content, lone) ?: return null, b.style)
        }
        // Однострочный ряд — ячейки прямо в нём; ряд из строк — строки `row` (2026-10-18).
        val lines = if (b.children.all { it.type == BlockType.ROW }) b.children.map { it.children } else listOf(b.children)
        val columns = lines.first().size
        if (lines.any { it.size != columns }) return null
        val cells = lines.flatten().map { c ->
            if (c.type != BlockType.CELL) return null
            val width = c.style["width"]?.toIntOrNull() ?: return null
            val text = when (c.nodeIds.size) {
                0 -> ""
                1 -> textOf(content, markup, listOf(MarkupBlock(type = BlockType.PARAGRAPH, nodeIds = c.nodeIds))) ?: return null
                else -> return null
            }
            ComposedCell(width, text, c.children.map { w -> widget(content, w) ?: return null }, c.style - "width" - "merge", c.style["merge"].orEmpty())
        }
        return ComposedItem.Row(cells, b.style, columns)
    }

    /** Блоки текста — знаками быстрого оформления, тем же разбором, что строка ввода (ADR-0045). */
    private fun textOf(content: MessageContent, markup: Markup, blocks: List<MarkupBlock>): String? {
        val ids = mutableListOf<Int>()
        fun collect(b: MarkupBlock) {
            ids += b.nodeIds
            b.children.forEach(::collect)
        }
        blocks.forEach(::collect)
        // Номера — по порядку узлов с единицы: так их даёт разбор, и обратная сверка сойдётся.
        val ordered = ids.distinct().sortedBy { markup.indexOf(it) }
        if (ordered.any { markup.indexOf(it) < 0 }) return null
        val renumber = ordered.withIndex().associate { (i, id) -> id to i + 1 }
        fun remap(b: MarkupBlock): MarkupBlock = b.copy(nodeIds = b.nodeIds.map { renumber.getValue(it) }, children = b.children.map(::remap))
        val sub = MessageContent(
            nodes = ordered.map { content.nodes[markup.indexOf(it)] },
            markup = Markup(
                n = ordered.map { renumber.getValue(it) },
                entities = markup.entities.filter { it.nodeId in renumber }.map { it.copy(nodeId = renumber.getValue(it.nodeId)) },
                blocks = blocks.map(::remap),
            ),
        )
        // Один абзац без оформления разбор отдаёт простым текстом, без разметки, — сверять не с чем.
        val plain = sub.markup?.let { m -> m.entities.isEmpty() && m.blocks.singleOrNull()?.let { it.type == BlockType.PARAGRAPH && it.children.isEmpty() } == true } == true
        return if (plain) sub.nodes.single() else QuickMarkdown.source(sub)
    }

    private fun widget(content: MessageContent, b: MarkupBlock): ComposedWidget? {
        val w = b.widget ?: return null
        fun media(i: Int): OutMedia? = content.media.getOrNull(i)?.let(::outOf)
        return when (b.type) {
            BlockType.IMAGE -> media(w.media)?.let { ComposedWidget.Image(it) }
            BlockType.LINK -> ComposedWidget.Link(w.url, w.title, w.mode, w.description, if (w.media >= 0) media(w.media) ?: return null else null)
            BlockType.CLOUD -> ComposedWidget.Cloud(w.url, w.kind, w.title)
            BlockType.MAP -> ComposedWidget.Place(w.lat ?: return null, w.lon ?: return null, w.label)
            BlockType.CONTACT -> ComposedWidget.Contact(w.name, w.phone, w.userId)
            BlockType.EMOJI -> ComposedWidget.Emoji(w.text)
            BlockType.REPLY -> w.ref?.let { ComposedWidget.Reply(ReplyTarget(it.kind, it.container, it.message, w.userId)) }
            BlockType.QUOTED -> w.ref?.let { ComposedWidget.Quoted(it.kind, it.container, it.message, w.author, w.cut) }
            BlockType.UNDERLAY -> w.ref?.let { ComposedWidget.Underlay(it.kind, it.container, it.message, w.lines, w.author) }
            else -> null
        }
    }

    private fun outOf(m: MediaRef) = OutMedia(
        mediaId = m.media_id,
        key = m.media_key.toByteArray(),
        mime = m.mime,
        width = m.width,
        height = m.height,
        size = m.size_bytes,
        preview = m.preview.toByteArray(),
        name = m.name,
    )
}
