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
        return io.tima.domain.chat.OpenBody(c.displayText(), c.nodes, c.markup?.let(Markup::encode).orEmpty())
    }

    /** Содержимое сообщения из документа — для тела, проверки и показа. */
    fun content(doc: ComposedDoc): MessageContent {
        val a = Assembly(doc.marks)
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

    private class Assembly(val marks: Boolean) {
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

        private fun parsed(source: String) = if (marks) QuickMarkdown.parse(source) else QuickMarkdown.plain(source)

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
            // Пустые ячейки не уходят (Решения §10.3), ширины оставшихся — снова 100.
            is ComposedItem.Row -> listOf(
                MarkupBlock(type = BlockType.FLEX, style = item.style, children = io.tima.domain.chat.ComposedLayout.compact(item).cells.map(::cell)),
            )
        }

        /** Ячейка — один узел: из текста берётся только оформление внутри строки. */
        private fun cell(c: ComposedCell): MarkupBlock {
            val text = c.text.trim('\n')
            val nodeIds = if (text.isBlank()) {
                emptyList()
            } else {
                val content = parsed(text)
                val markup = content.markup?.takeIf { content.hasMarkup }
                val paragraph = markup?.blocks?.singleOrNull()?.takeIf { it.type == BlockType.PARAGRAPH && it.nodeIds.size == 1 }
                when {
                    markup == null -> listOf(node(content.plainText()))
                    paragraph != null -> {
                        val id = node(content.nodes[markup.indexOf(paragraph.nodeIds.single())])
                        entities += markup.entities.filter { it.nodeId == paragraph.nodeIds.single() }.map { it.copy(nodeId = id) }
                        listOf(id)
                    }
                    // Заголовок и список в ячейке не живут — текст как есть.
                    else -> listOf(node(content.displayText()))
                }
            }
            return MarkupBlock(
                type = BlockType.CELL,
                nodeIds = nodeIds,
                style = c.style + ("width" to c.width.toString()),
                children = c.widgets.map(::widget),
            )
        }

        fun widget(w: ComposedWidget): MarkupBlock = when (w) {
            is ComposedWidget.Image -> {
                media += ref(w.media)
                MarkupBlock(type = BlockType.IMAGE, widget = MarkupWidget(media = media.size - 1))
            }
            is ComposedWidget.Link -> {
                val image = w.image?.let { media += ref(it); media.size - 1 } ?: -1
                MarkupBlock(type = BlockType.LINK, widget = MarkupWidget(url = w.url, mode = w.mode, title = w.title, description = w.description, media = image))
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
                widget = MarkupWidget(ref = MarkupRef(kind = w.kind, container = w.container, message = w.message), author = w.author, cut = w.cut),
            )
            is ComposedWidget.Underlay -> MarkupBlock(
                type = BlockType.UNDERLAY,
                widget = MarkupWidget(ref = MarkupRef(kind = w.kind, container = w.container, message = w.message), author = w.author, lines = w.lines),
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
    private data class SavedDoc(val items: List<SavedItem>, val marks: Boolean = true, val level: Int? = null) {
        fun toDoc() = ComposedDoc(items.mapNotNull { it.toItem() }.ifEmpty { listOf(ComposedItem.Text("")) }, marks, level)

        companion object {
            fun of(doc: ComposedDoc) = SavedDoc(doc.items.map(SavedItem::of), doc.marks, doc.level)
        }
    }

    @Serializable
    private data class SavedItem(
        val text: String? = null,
        val widget: SavedWidget? = null,
        val cells: List<SavedCell>? = null,
        val style: Map<String, String> = emptyMap(),
        val continuation: Int? = null,
    ) {
        fun toItem(): ComposedItem? = when {
            continuation != null -> ComposedItem.Continuation(continuation)
            text != null -> ComposedItem.Text(text, style)
            widget != null -> widget.toWidget()?.let { ComposedItem.Widget(it, style) }
            cells != null -> ComposedItem.Row(cells.map { it.toCell() }, style)
            else -> null
        }

        companion object {
            fun of(item: ComposedItem) = when (item) {
                is ComposedItem.Text -> SavedItem(text = item.source, style = item.style)
                is ComposedItem.Widget -> SavedItem(widget = SavedWidget.of(item.widget), style = item.style)
                is ComposedItem.Row -> SavedItem(cells = item.cells.map(SavedCell::of), style = item.style)
                is ComposedItem.Continuation -> SavedItem(continuation = item.level)
            }
        }
    }

    @Serializable
    private data class SavedCell(val width: Int, val text: String = "", val widgets: List<SavedWidget> = emptyList(), val style: Map<String, String> = emptyMap()) {
        fun toCell() = ComposedCell(width, text, widgets.mapNotNull { it.toWidget() }, style)

        companion object {
            fun of(c: ComposedCell) = SavedCell(c.width, c.text, c.widgets.map(SavedWidget::of), c.style)
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
    ) {
        private fun media(): OutMedia? = if (mediaId.isEmpty()) null else
            OutMedia(mediaId, Base64.decode(key), mime, width, height, size, Base64.decode(preview), name)

        fun toWidget(): ComposedWidget? = when (type) {
            "image" -> media()?.let { ComposedWidget.Image(it) }
            "link" -> ComposedWidget.Link(url, title, mode, description, media())
            "cloud" -> ComposedWidget.Cloud(url, kind, title)
            "place" -> ComposedWidget.Place(lat, lon, label)
            "contact" -> ComposedWidget.Contact(name, phone, userId)
            "emoji" -> ComposedWidget.Emoji(text)
            "reply" -> ComposedWidget.Reply(ReplyTarget(kind, chatId, messageId, userId))
            "quoted" -> ComposedWidget.Quoted(kind, chatId, messageId, author, cut)
            "underlay" -> ComposedWidget.Underlay(kind, chatId, messageId, lines.coerceAtLeast(1), author)
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
                    "link", url = w.url, title = w.title, mode = w.mode, description = w.description,
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
                    "quoted", kind = w.kind, chatId = w.container, messageId = w.message, author = w.author, cut = w.cut,
                )
                is ComposedWidget.Underlay -> SavedWidget(
                    "underlay", kind = w.kind, chatId = w.container, messageId = w.message, author = w.author, lines = w.lines,
                )
            }
        }
    }
}
