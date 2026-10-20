package io.tima.core.encryption

import io.tima.crypto.BlockType
import io.tima.crypto.Markup
import io.tima.crypto.MarkupBlock
import io.tima.crypto.MarkupEntity
import io.tima.crypto.MarkupRef
import io.tima.crypto.MarkupRules
import io.tima.crypto.MarkupWidget
import io.tima.crypto.MessageContent
import io.tima.crypto.MessageContentCodec
import io.tima.crypto.MessageReplies
import io.tima.crypto.MessageSerializer
import io.tima.crypto.QuickMarkdown
import io.tima.crypto.proto.MediaRef
import io.tima.domain.chat.ComposedBodies
import io.tima.domain.chat.ComposedCell
import io.tima.domain.chat.ComposedDoc
import io.tima.domain.chat.ComposedItem
import io.tima.domain.chat.ComposedListItem
import io.tima.domain.chat.ComposedWidget
import io.tima.domain.chat.OutMedia
import io.tima.domain.chat.ReplyTarget
import io.tima.domain.chat.RichContent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.toByteString
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Документ редактора → узлы и разметка (Решения §10, ADR-0044). Каждый текстовый кусок разбирается
 * быстрым оформлением отдельно, узлы получают сквозные номера; ряды — `flex` из ячеек, виджет во
 * всю ширину — ряд из одной ячейки. Проверка — строгая `MarkupRules`, та же, что на отправке.
 */
object ComposedBodiesOverKodium : ComposedBodies {

    override fun problems(doc: ComposedDoc): List<String> {
        val (main, cont) = doc.split()
        return MarkupRules.check(content(main), strict = true).map { it.detail } +
            (cont?.let { MarkupRules.check(content(it), strict = true).map { p -> p.detail } } ?: emptyList())
    }

    override fun encode(doc: ComposedDoc, reply: ReplyTarget?): ByteArray? {
        var content = content(doc)
        if (reply != null) {
            content = MessageReplies.attach(
                content,
                MessageReplies.Reply(MarkupRef(kind = reply.kind, container = reply.chatId, message = reply.messageId), reply.userId),
            )
        }
        if (MarkupRules.check(content, strict = true).isNotEmpty()) return null
        // Таблица шире пузыря — отправить нельзя (Решения §10.3).
        if (io.tima.domain.chat.TableFit.tooWide(doc).isNotEmpty()) return null
        return MessageSerializer.encodeBody(MessageContentCodec.toBody(content))
    }

    override fun preview(doc: ComposedDoc): RichContent? = RichMapper.from(content(doc))

    override fun open(doc: ComposedDoc): io.tima.domain.chat.OpenBody? {
        val c = content(doc)
        if (c.media.isNotEmpty()) return null
        return io.tima.domain.chat.OpenBody(c.displayText(), c.nodes, c.markup?.let { io.tima.crypto.MarkupV2.encodeBody(it, c.nodes) }.orEmpty())
    }

    /**
     * Открытое сообщение канала и сообщества — узлы и разметка открытым текстом (ADR-0011 §4) — для
     * показа в окне «Описание» (ПЛАН-(ЗО) ЗО4). Разметка не разобралась — текст без оформления.
     */
    fun readOpen(nodes: List<String>, markup: String): io.tima.domain.chat.DecodedLine? = runCatching {
        val content = MessageContent(nodes, markup.takeIf { it.isNotBlank() }?.let { io.tima.crypto.MarkupV2.decodeBody(it, nodes) })
        io.tima.domain.chat.DecodedLine(content.displayText(), RichMapper.from(content))
    }.getOrNull()

    /** Содержимое сообщения из документа — для тела, проверки и показа. */
    fun content(doc: ComposedDoc): MessageContent {
        val a = Assembly()
        // Метка продолжения в тело не идёт: что ниже неё — отдельное сообщение (Решения §11.3).
        val blocks = doc.split().first.items.filterNot { it.isEmpty || it is ComposedItem.Continuation }.flatMap { a.item(it) }
        // Один абзац без оформления — обычное сообщение: разметка ему ни к чему.
        val single = blocks.singleOrNull()
        if (single != null && single.type == BlockType.PARAGRAPH && single.style.isEmpty() && a.entities.isEmpty() && a.media.isEmpty()) {
            return MessageContent.text(a.nodes.single())
        }
        if (blocks.isEmpty()) return MessageContent.text("")
        return MessageContent(
            nodes = a.nodes,
            markup = Markup(n = a.ids, entities = a.entities, blocks = blocks),
            media = a.media,
        )
    }

    /**
     * Сборка тела из документа. Текст куска — набранное со знаками, где знак, набранный текстом,
     * экранирован (ADR-0048): разбирается всегда, «Знаки md» на сборку не влияют.
     */
    private class Assembly {
        val nodes = mutableListOf<String>()
        val ids = mutableListOf<Int>()
        val entities = mutableListOf<MarkupEntity>()
        val media = mutableListOf<MediaRef>()

        private fun node(text: String): Int {
            val id = ids.size + 1
            nodes += text
            ids += id
            return id
        }

        private fun parsed(source: String) = QuickMarkdown.parse(source)

        /** Разобранный кусок — в общий документ: узлы с новыми номерами, блоки и оформление следом. */
        private fun graft(content: MessageContent): List<MarkupBlock> {
            val markup = content.markup?.takeIf { content.hasMarkup }
                ?: return content.nodes.filter { it.isNotEmpty() }.map { MarkupBlock(type = BlockType.PARAGRAPH, nodeIds = listOf(node(it))) }
            val renumber = markup.n.mapIndexed { i, old -> old to node(content.nodes[i]) }.toMap()
            entities += markup.entities.map { it.copy(nodeId = renumber[it.nodeId] ?: it.nodeId) }
            return markup.blocks.map { remap(it, renumber) }
        }

        private fun remap(b: MarkupBlock, renumber: Map<Int, Int>): MarkupBlock =
            b.copy(nodeIds = b.nodeIds.map { renumber[it] ?: it }, children = b.children.map { remap(it, renumber) })

        fun item(item: ComposedItem): List<MarkupBlock> = when (item) {
            is ComposedItem.Text -> graft(parsed(item.source.trim('\n'))).map { block ->
                if (item.style.isEmpty()) block else block.copy(style = block.style + item.style)
            }
            is ComposedItem.ItemList -> listOf(list(item))
            is ComposedItem.Continuation -> emptyList()
            // Ответ и подкладка — блоки верхнего уровня, остальные виджеты живут в ячейках (ADR-0044).
            is ComposedItem.Widget -> if (item.widget is ComposedWidget.Reply || item.widget is ComposedWidget.Underlay) {
                listOf(widget(item.widget).copy(style = item.style))
            } else {
                listOf(
                    MarkupBlock(
                        type = BlockType.FLEX,
                        style = item.style,
                        children = listOf(MarkupBlock(type = BlockType.CELL, style = mapOf("width" to "100"), children = listOf(widget(item.widget)))),
                    ),
                )
            }
            // Пустая ячейка уходит (заказчик 2026-10-18): её фон виден. Однострочный ряд без слияний —
            // прежним видом, ячейки прямо в ряду; иначе — строки `row` (2026-10-18).
            is ComposedItem.Row -> {
                val cells = item.cells.map(::cell)
                val single = item.lines <= 1 && item.cells.none { it.merge.isNotEmpty() }
                val children = if (single) cells else cells.chunked(item.columns.coerceAtLeast(1)).map { MarkupBlock(type = BlockType.ROW, children = it) }
                listOf(MarkupBlock(type = BlockType.FLEX, style = item.style, children = children))
            }
        }

        /** Одна строка набранного — узлом с оформлением внутри строки (заголовок пункта). */
        private fun lineNode(source: String): Int {
            val content = parsed(source.trim('\n'))
            val markup = content.markup?.takeIf { content.hasMarkup }
            val paragraph = markup?.blocks?.singleOrNull()?.takeIf { it.type == BlockType.PARAGRAPH && it.nodeIds.size == 1 }
            return when {
                markup == null -> node(content.plainText())
                paragraph != null -> node(content.nodes[markup.indexOf(paragraph.nodeIds.single())]).also { id ->
                    entities += markup.entities.filter { it.nodeId == paragraph.nodeIds.single() }.map { it.copy(nodeId = id) }
                }
                else -> node(content.displayText())
            }
        }

        /**
         * Список (ADR-0049): пункт — узел-заголовок, под ним его блоки, виджеты и вложенный список,
         * узлы — в порядке обхода. Пункт глубже соседа сверху больше чем на ступень встаёт на ступень
         * ниже соседа. Свойства списка — стилем: `fold`, `indent`.
         */
        private fun list(l: ComposedItem.ItemList): MarkupBlock {
            val items = l.items
            var i = 0
            fun level(depth: Int): MarkupBlock {
                val out = mutableListOf<MarkupBlock>()
                while (i < items.size) {
                    val it = items[i]
                    if (out.isNotEmpty() && it.depth < depth) break
                    i++
                    val title = lineNode(it.title)
                    val content = if (it.text.isBlank()) emptyList() else graft(parsed(it.text.trim('\n')))
                    val widgets = it.widgets.map(::widget)
                    val nested = if (i < items.size && items[i].depth > depth && depth < MarkupRules.MAX_LIST_DEPTH) listOf(level(depth + 1)) else emptyList()
                    out += MarkupBlock(type = BlockType.LIST_ITEM, nodeIds = listOf(title), children = content + widgets + nested, style = it.style)
                }
                return MarkupBlock(type = BlockType.LIST, children = out)
            }
            val style = buildMap {
                putAll(l.style)
                if (!l.open) put("fold", "closed")
                if (l.flat) put("indent", "flat")
            }
            return level(1).copy(style = style)
        }

        /**
         * Ячейка — область со своими блоками (ADR-0048 §4): один простой абзац — узлом самой ячейки,
         * как прежде; заголовки, списки, цитаты, код — её детьми, перед виджетами. Слитая — без содержимого.
         */
        private fun cell(c: ComposedCell): MarkupBlock {
            if (c.merge.isNotEmpty()) return MarkupBlock(type = BlockType.CELL, style = mapOf("width" to c.width.toString(), "merge" to c.merge))
            val text = c.text.trim('\n')
            var nodeIds = emptyList<Int>()
            var blocks = emptyList<MarkupBlock>()
            if (text.isNotBlank()) {
                val content = parsed(text)
                val markup = content.markup?.takeIf { content.hasMarkup }
                val paragraph = markup?.blocks?.singleOrNull()?.takeIf { it.type == BlockType.PARAGRAPH && it.nodeIds.size == 1 && it.style.isEmpty() }
                when {
                    markup == null -> nodeIds = listOf(node(content.plainText()))
                    paragraph != null -> {
                        val id = node(content.nodes[markup.indexOf(paragraph.nodeIds.single())])
                        entities += markup.entities.filter { it.nodeId == paragraph.nodeIds.single() }.map { it.copy(nodeId = id) }
                        nodeIds = listOf(id)
                    }
                    else -> blocks = graft(content)
                }
            }
            return MarkupBlock(
                type = BlockType.CELL,
                nodeIds = nodeIds,
                style = c.style - "merge" + ("width" to c.width.toString()),
                children = blocks + c.widgets.map(::widget),
            )
        }

        fun widget(w: ComposedWidget): MarkupBlock = when (w) {
            is ComposedWidget.Image -> {
                media += ref(w.media)
                MarkupBlock(type = BlockType.IMAGE, widget = MarkupWidget(media = media.size - 1))
            }
            is ComposedWidget.Link -> {
                val image = w.image?.let { media += ref(it); media.size - 1 } ?: -1
                MarkupBlock(
                    type = BlockType.LINK,
                    widget = MarkupWidget(url = w.url, mode = w.mode, title = w.title, description = w.description, media = image),
                    // Цвет ссылки — стилем её блока, как цвет любого узла (заказчик 2026-10-19).
                    style = if (w.color.isNotEmpty()) mapOf("color" to w.color) else emptyMap(),
                )
            }
            is ComposedWidget.Cloud -> MarkupBlock(type = BlockType.CLOUD, widget = MarkupWidget(url = w.url, kind = w.kind, title = w.title))
            is ComposedWidget.Place -> MarkupBlock(type = BlockType.MAP, widget = MarkupWidget(lat = w.lat, lon = w.lon, label = w.label))
            is ComposedWidget.Contact -> MarkupBlock(type = BlockType.CONTACT, widget = MarkupWidget(name = w.name, phone = w.phone, userId = w.userId))
            is ComposedWidget.Emoji -> MarkupBlock(type = BlockType.EMOJI, widget = MarkupWidget(text = w.text))
            is ComposedWidget.Reply -> MarkupBlock(
                type = BlockType.REPLY,
                widget = MarkupWidget(userId = w.target.userId, ref = MarkupRef(kind = w.target.kind, container = w.target.chatId, message = w.target.messageId)),
            )
            is ComposedWidget.Quoted -> MarkupBlock(
                type = BlockType.QUOTED,
                widget = MarkupWidget(
                    ref = MarkupRef(kind = w.kind, container = w.container, message = w.message),
                    author = w.author, lines = w.lines, more = w.more, moreText = w.moreText, tap = w.tap,
                ),
            )
            is ComposedWidget.Underlay -> MarkupBlock(
                type = BlockType.UNDERLAY,
                widget = MarkupWidget(
                    ref = MarkupRef(kind = w.kind, container = w.container, message = w.message),
                    author = w.author, lines = w.lines, more = w.more, moreText = w.moreText, tap = w.tap,
                ),
            )
        }

        private fun ref(m: OutMedia) = MediaRef(
            media_id = m.mediaId,
            media_key = m.key.toByteString(),
            mime = m.mime,
            size_bytes = m.size,
            width = m.width,
            height = m.height,
            preview = m.preview.toByteString(),
            name = m.name,
        )
    }

    // ── Черновик строкой ─────────────────────────────────────────────────────

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    override fun save(doc: ComposedDoc): String = json.encodeToString(SavedDoc.serializer(), SavedDoc.of(doc))

    override fun load(saved: String): ComposedDoc? =
        runCatching { json.decodeFromString(SavedDoc.serializer(), saved).toDoc() }.getOrNull()

    /** Черновик на диске — своя запись, а не доменные классы: их форма вправе меняться. */
    @Serializable
    private data class SavedDoc(
        val items: List<SavedItem>,
        val marks: Boolean = true,
        val level: Int? = null,
        /** 2 — набранное с экранированными знаками-текстом (ADR-0048); прежде выключенные знаки хранились как есть. */
        val typed: Int = 0,
    ) {
        fun toDoc(): ComposedDoc {
            val doc = ComposedDoc(items.mapNotNull { it.toItem() }.ifEmpty { listOf(ComposedItem.Text("")) }, marks, level)
            if (marks || typed >= TYPED) return doc
            // Черновик до 179 со снятыми «Знаками md»: текст был как набран — знаки в нём экранируются.
            fun esc(text: String) = TypedOverQuickMarkdown.write(io.tima.domain.chat.Typed.plain(text))
            return doc.copy(
                items = doc.items.map { item ->
                    when (item) {
                        is ComposedItem.Text -> item.copy(source = esc(item.source))
                        is ComposedItem.Row -> item.copy(cells = item.cells.map { it.copy(text = esc(it.text)) })
                        else -> item
                    }
                },
            )
        }

        companion object {
            const val TYPED = 2
            fun of(doc: ComposedDoc) = SavedDoc(doc.items.map(SavedItem::of), doc.marks, doc.level, TYPED)
        }
    }

    @Serializable
    private data class SavedItem(
        val text: String? = null,
        val widget: SavedWidget? = null,
        val cells: List<SavedCell>? = null,
        val style: Map<String, String> = emptyMap(),
        val continuation: Int? = null,
        /** Столбцов ряда; нет — однострочный ряд (черновики до 2026-10-18). */
        val columns: Int? = null,
        /** Пункты списка (ADR-0049); свойства списка — [open], [flat]. */
        val items: List<SavedListItem>? = null,
        val open: Boolean = true,
        val flat: Boolean = false,
    ) {
        fun toItem(): ComposedItem? = when {
            continuation != null -> ComposedItem.Continuation(continuation)
            text != null -> ComposedItem.Text(text, style)
            items != null -> ComposedItem.ItemList(items.map { it.toItem() }, style, open, flat)
            widget != null -> widget.toWidget()?.let { ComposedItem.Widget(it, style) }
            cells != null -> ComposedItem.Row(cells.map { it.toCell() }, style, columns ?: cells.size)
            else -> null
        }

        companion object {
            fun of(item: ComposedItem) = when (item) {
                is ComposedItem.Text -> SavedItem(text = item.source, style = item.style)
                is ComposedItem.Widget -> SavedItem(widget = SavedWidget.of(item.widget), style = item.style)
                is ComposedItem.Row -> SavedItem(cells = item.cells.map(SavedCell::of), style = item.style, columns = item.columns)
                is ComposedItem.Continuation -> SavedItem(continuation = item.level)
                is ComposedItem.ItemList -> SavedItem(items = item.items.map(SavedListItem::of), style = item.style, open = item.open, flat = item.flat)
            }
        }
    }

    @Serializable
    private data class SavedListItem(
        val depth: Int = 1,
        val title: String = "",
        val text: String = "",
        val widgets: List<SavedWidget> = emptyList(),
        val style: Map<String, String> = emptyMap(),
    ) {
        fun toItem() = ComposedListItem(depth, title, text, widgets.mapNotNull { it.toWidget() }, style)

        companion object {
            fun of(i: ComposedListItem) = SavedListItem(i.depth, i.title, i.text, i.widgets.map(SavedWidget::of), i.style)
        }
    }

    @Serializable
    private data class SavedCell(
        val width: Int,
        val text: String = "",
        val widgets: List<SavedWidget> = emptyList(),
        val style: Map<String, String> = emptyMap(),
        val merge: String = "",
    ) {
        fun toCell() = ComposedCell(width, text, widgets.mapNotNull { it.toWidget() }, style, merge)

        companion object {
            fun of(c: ComposedCell) = SavedCell(c.width, c.text, c.widgets.map(SavedWidget::of), c.style, c.merge)
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Serializable
    private data class SavedWidget(
        val type: String,
        val url: String = "",
        val title: String = "",
        val mode: String = "",
        val kind: String = "",
        val lat: Double = 0.0,
        val lon: Double = 0.0,
        val label: String = "",
        val name: String = "",
        val phone: String = "",
        val userId: String = "",
        val text: String = "",
        val chatId: String = "",
        val messageId: Long = 0,
        val mediaId: String = "",
        val key: String = "",
        val mime: String = "",
        val width: Int = 0,
        val height: Int = 0,
        val size: Long = 0,
        val preview: String = "",
        val author: Boolean = false,
        val cut: String = "",
        val description: String = "",
        val lines: Int = 0,
        val color: String = "",
        /** Строк задано (ADR-0050): у прежних черновиков подборки его нет — тогда по [cut]. */
        val linesSet: Boolean = false,
        val more: String = "",
        val moreText: String = "",
        val tap: Boolean = false,
    ) {
        private fun media(): OutMedia? = if (mediaId.isEmpty()) null else
            OutMedia(mediaId, Base64.decode(key), mime, width, height, size, Base64.decode(preview), name)

        fun toWidget(): ComposedWidget? = when (type) {
            "image" -> media()?.let { ComposedWidget.Image(it) }
            "link" -> ComposedWidget.Link(url, title, mode, description, media(), color)
            "cloud" -> ComposedWidget.Cloud(url, kind, title)
            "place" -> ComposedWidget.Place(lat, lon, label)
            "contact" -> ComposedWidget.Contact(name, phone, userId)
            "emoji" -> ComposedWidget.Emoji(text)
            "reply" -> ComposedWidget.Reply(ReplyTarget(kind, chatId, messageId, userId))
            "quoted" -> ComposedWidget.Quoted(kind, chatId, messageId, author, if (linesSet) lines else if (cut == "full") 0 else 3, more, moreText, tap)
            "underlay" -> ComposedWidget.Underlay(kind, chatId, messageId, lines, author, more, moreText, tap)
            else -> null
        }

        companion object {
            fun of(w: ComposedWidget) = when (w) {
                is ComposedWidget.Image -> SavedWidget(
                    "image", mediaId = w.media.mediaId, key = Base64.encode(w.media.key), mime = w.media.mime,
                    width = w.media.width, height = w.media.height, size = w.media.size,
                    preview = Base64.encode(w.media.preview), name = w.media.name,
                )
                is ComposedWidget.Link -> SavedWidget(
                    "link", url = w.url, title = w.title, mode = w.mode, description = w.description, color = w.color,
                    mediaId = w.image?.mediaId.orEmpty(), key = w.image?.let { Base64.encode(it.key) }.orEmpty(),
                    mime = w.image?.mime.orEmpty(), width = w.image?.width ?: 0, height = w.image?.height ?: 0, size = w.image?.size ?: 0,
                )
                is ComposedWidget.Cloud -> SavedWidget("cloud", url = w.url, kind = w.kind, title = w.title)
                is ComposedWidget.Place -> SavedWidget("place", lat = w.lat, lon = w.lon, label = w.label)
                is ComposedWidget.Contact -> SavedWidget("contact", name = w.name, phone = w.phone, userId = w.userId)
                is ComposedWidget.Emoji -> SavedWidget("emoji", text = w.text)
                is ComposedWidget.Reply -> SavedWidget(
                    "reply", kind = w.target.kind, chatId = w.target.chatId, messageId = w.target.messageId, userId = w.target.userId,
                )
                is ComposedWidget.Quoted -> SavedWidget(
                    "quoted", kind = w.kind, chatId = w.container, messageId = w.message, author = w.author,
                    lines = w.lines, linesSet = true, more = w.more, moreText = w.moreText, tap = w.tap,
                )
                is ComposedWidget.Underlay -> SavedWidget(
                    "underlay", kind = w.kind, chatId = w.container, messageId = w.message, author = w.author, lines = w.lines,
                    more = w.more, moreText = w.moreText, tap = w.tap,
                )
            }
        }
    }
}
