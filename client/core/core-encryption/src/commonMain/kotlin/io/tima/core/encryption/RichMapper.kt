package io.tima.core.encryption

import io.tima.crypto.BlockType
import io.tima.crypto.EntityType
import io.tima.crypto.Markup
import io.tima.crypto.MarkupBlock
import io.tima.crypto.MarkupRules
import io.tima.crypto.MessageContent
import io.tima.domain.chat.BlockStyle
import io.tima.domain.chat.Bullet
import io.tima.domain.chat.RichMedia
import io.tima.domain.chat.RowCell
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

    /** Блоки текста, которые живут и в ячейке ряда (ADR-0048 §4). */
    private val CELL_TEXT = setOf(BlockType.PARAGRAPH, BlockType.HEADING, BlockType.QUOTE, BlockType.LIST, BlockType.CODE_BLOCK, BlockType.DIVIDER)

    fun from(content: MessageContent): RichContent? {
        // Без разметки, но с файлами — сообщение вне пузыря (Решения §2, §3, §6): фото — альбомом,
        // остальное — файлами.
        if (content.markup == null && content.media.isNotEmpty() && content.plainText().isBlank()) return attachments(content)
        val markup = content.markup ?: return null
        MarkupRules.check(content, strict = false).takeIf { it.isNotEmpty() }?.let { problems ->
            // Показано без оформления — журналу: какие правила и сколько весит разметка (MSG-MARKUP).
            CodecWatch.markupDropped?.invoke(problems.map { it.rule }.distinct(), io.tima.crypto.MarkupV2.encodeBody(markup, content.nodes).encodeToByteArray().size)
            return null
        }
        val byId = markup.n.mapIndexed { index, id -> id to index }.toMap()
        fun nodeText(id: Int): String = byId[id]?.let { content.nodes.getOrNull(it) }.orEmpty()
        fun rich(id: Int): RichText = richText(nodeText(id), id, markup)

        fun map(block: MarkupBlock): RichBlock =
            when (block.type) {
                BlockType.PARAGRAPH -> RichBlock.Paragraph(rich(block.nodeIds.firstOrNull() ?: -1), style(block))
                BlockType.HEADING -> RichBlock.Heading(block.level.coerceIn(1, 3), rich(block.nodeIds.firstOrNull() ?: -1), style(block))
                BlockType.QUOTE -> RichBlock.Quote(block.nodeIds.map(::rich), style(block))
                // Пункт — контейнер (ADR-0049 §2): его блоки и виджеты — под заголовком.
                BlockType.LIST -> RichBlock.Bullets(
                    bullets(block, ::rich) { b -> if (b.type in CELL_TEXT) map(b) else widget(b) },
                    closed = block.style["fold"] == "closed",
                    flat = block.style["indent"] == "flat",
                    style = style(block),
                )
                BlockType.CODE_BLOCK -> RichBlock.CodeBlock(nodeText(block.nodeIds.firstOrNull() ?: -1), style(block))
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
                    style(block),
                )
                BlockType.FLEX -> {
                    // Однострочный ряд — ячейки прямо в нём; ряд из строк (2026-10-18) — строки `row`.
                    val lines = if (block.children.isNotEmpty() && block.children.all { it.type == BlockType.ROW }) {
                        block.children.map { line -> line.children.filter { it.type == BlockType.CELL } }
                    } else {
                        listOf(block.children.filter { it.type == BlockType.CELL })
                    }
                    RichBlock.Row(
                        lines.flatten().map { cell ->
                            RowCell(
                                width = cell.style["width"]?.toIntOrNull(),
                                text = cell.nodeIds.firstOrNull()?.let(::rich),
                                // Ячейка — область со своими блоками (ADR-0048 §4): текст — блоками, затем виджеты.
                                blocks = cell.children.filter { it.type in CELL_TEXT }.map(::map),
                                widgets = cell.children.filter { it.type !in CELL_TEXT }.mapNotNull(::widget),
                                style = style(cell),
                                merge = cell.style["merge"].orEmpty(),
                            )
                        },
                        style(block),
                        columns = lines.first().size,
                    )
                }
                // Виджеты и ответ; незнакомое от более новой версии — текстом своих узлов.
                else -> widget(block) ?: RichBlock.Fallback(allNodes(block).joinToString("\n") { nodeText(it) }.trim())
            }

        val blocks = markup.blocks.map(::map).filterNot { it is RichBlock.Fallback && it.text.isEmpty() }
        return RichContent(blocks, media(content))
    }

    private fun media(content: MessageContent): List<RichMedia> = content.media.map {
        RichMedia(
            it.media_id, it.media_key.toByteArray(), it.mime, it.width, it.height,
            name = it.name, size = it.size_bytes,
            voice = it.voice, durationMs = it.duration_ms.toLong(), wave = it.wave.toByteArray(),
        )
    }

    private fun attachments(content: MessageContent): RichContent {
        val all = media(content)
        val photos = all.indices.filter { all[it].mime.startsWith("image/") && all[it].name.isEmpty() && !all[it].voice }
        val blocks = buildList {
            if (photos.isNotEmpty()) add(RichBlock.Album(photos))
            for (i in all.indices) if (i !in photos) add(if (all[i].voice) RichBlock.Voice(i) else RichBlock.File(i))
        }
        return RichContent(blocks, all)
    }

    /** Виджет — в ячейке ряда или сам по себе (ADR-0044 §3). Не виджет или без данных — `null`. */
    private fun widget(block: MarkupBlock): RichBlock? {
        val w = block.widget ?: return null
        return when (block.type) {
            BlockType.IMAGE -> w.media.takeIf { it >= 0 }?.let { RichBlock.Image(it, w.share.takeIf { s -> s in 20..99 } ?: 100) }
            BlockType.LINK -> w.takeIf { it.url.isNotBlank() }?.let {
                RichBlock.Link(it.url, it.mode.ifEmpty { "plain" }, it.title, it.description, it.media, block.style["color"].orEmpty())
            }
            BlockType.CLOUD -> w.takeIf { it.url.isNotBlank() }?.let { RichBlock.Cloud(it.url, it.kind.ifEmpty { "file" }, it.title) }
            BlockType.MAP -> {
                val lat = w.lat
                val lon = w.lon
                if (lat != null && lon != null) RichBlock.Place(lat, lon, w.label) else null
            }
            BlockType.CONTACT -> RichBlock.Contact(w.userId, w.name, w.phone)
            BlockType.EMOJI -> w.text.takeIf { it.isNotBlank() }?.let { RichBlock.Emoji(it) }
            BlockType.REPLY -> w.ref?.let { RichBlock.Reply(w.userId, it.container, it.message) }
            BlockType.QUOTED -> w.ref?.let { RichBlock.Quoted(it.kind, it.container, it.message, w.author, ComposedFromContent.quotedLines(w), w.more, w.moreText, w.tap) }
            BlockType.UNDERLAY -> w.ref?.let { RichBlock.Underlay(it.kind, it.container, it.message, w.author, w.lines, w.more, w.moreText, w.tap, style(block)) }
            BlockType.VIDEO -> w.takeIf { it.url.isNotBlank() }?.let { RichBlock.Video(it.url, it.description, it.title, it.showTitle, it.look.ifEmpty { "picture" }, it.media) }
            BlockType.AUDIO -> w.takeIf { it.url.isNotBlank() }?.let { RichBlock.Audio(it.url, it.description, it.durationMs) }
            BlockType.STREAM -> w.takeIf { it.url.isNotBlank() }?.let { RichBlock.Stream(it.url, it.description) }
            BlockType.MARKS -> w.takeIf { it.marks.isNotEmpty() }?.let { RichBlock.Marks(it.bind, it.marks.map { m -> io.tima.domain.chat.MediaMark(m.at, m.text) }) }
            else -> null
        }
    }

    /** Стиль блока — только известные ключи; цвета — имена, их значения знает экран. */
    private fun style(block: MarkupBlock): BlockStyle {
        val st = block.style
        if (st.isEmpty()) return BlockStyle.NONE
        return BlockStyle(
            background = st["background"],
            color = st["color"],
            size = st["size"],
            align = st["align"],
            valign = st["valign"],
            padding = st["padding"],
            divider = st["divider"],
            dividerColor = st["divider_color"],
        )
    }

    private fun bullets(list: MarkupBlock, rich: (Int) -> RichText, inner: (MarkupBlock) -> RichBlock?): List<Bullet> =
        list.children.filter { it.type == BlockType.LIST_ITEM }.map { item ->
            Bullet(
                text = rich(item.nodeIds.firstOrNull() ?: -1),
                children = item.children.filter { it.type == BlockType.LIST }.flatMap { bullets(it, rich, inner) },
                blocks = item.children.filter { it.type != BlockType.LIST }.mapNotNull(inner),
                style = style(item),
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
        // Адрес текстом без отрезка-ссылки — тоже ссылка (ADR-0050 §3): показ нашим видом, «Перейти · домен».
        // В коде и поверх ссылки — нет.
        val taken = spans.filter { it.kind == SpanKind.LINK || it.kind == SpanKind.CODE }
        val found = io.tima.domain.chat.TextLinks.find(text).filter { r -> taken.none { it.start <= r.last && r.first < it.end } }
            .map { r -> RichSpan(SpanKind.LINK, r.first, r.last + 1, io.tima.domain.chat.TextLinks.openable(text.substring(r))) }
        return RichText(text, spans + found)
    }
}
