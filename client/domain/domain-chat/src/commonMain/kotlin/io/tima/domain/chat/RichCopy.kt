package io.tima.domain.chat

/**
 * Вставленное по ссылке (подборка, подкладка) — для копии: имя автора, если оно показано, и содержимое;
 * [rich] нет — хотя бы [text].
 */
class CopiedRef(val author: String?, val rich: RichContent?, val text: String?)

/**
 * Текст сообщения целиком — для «Скопировать текст» и выделения (заказчик 2026-10-22: «делай текст
 * целиком, добавь ещё вставлять ссылку»).
 *
 * - Блоки — по порядку, как сообщение выглядит: абзацы, заголовки, списки, цитаты, код, таблицы, ряды.
 * - **Ссылка**: задан свой текст — сначала он, потом полный адрес на той же строке; текста нет или
 *   ссылка карточкой — просто адрес. Так же — ссылка внутри абзаца и облако.
 * - **Вставленное из подборки и подкладка** — целиком, без обрезки строк, на своём месте: строкой —
 *   имя автора, если оно показано, ниже — текст. Раскрывается один уровень: вставка во вставке не
 *   разворачивается, иначе цепочка разрастается или замыкается. Недоступное — [unavailable].
 * - Ответ, картинки, файлы — не текст и в копию не идут.
 */
object RichCopy {

    fun text(rich: RichContent, ref: (kind: String, chatId: String, messageId: Long) -> CopiedRef?, unavailable: String): String =
        lines(rich.blocks, ref, unavailable, depth = 0).joinToString("\n").trim()

    /** Абзац со ссылками внутри: ссылка со своим текстом — «текст адрес»; голый адрес — как есть. */
    fun inline(t: RichText): String {
        val links = t.spans.filter { it.kind == SpanKind.LINK && it.attribute.isNotBlank() && it.start in 0..it.end && it.end <= t.text.length }
            .sortedBy { it.start }
        if (links.isEmpty()) return t.text
        val out = StringBuilder()
        var at = 0
        for (s in links) {
            if (s.start < at) continue
            out.append(t.text, at, s.start)
            out.append(link(t.text.substring(s.start, s.end), s.attribute, card = false))
            at = s.end
        }
        out.append(t.text, at, t.text.length)
        return out.toString()
    }

    /** Ссылка одной строкой: свой текст и полный адрес; без текста или карточкой — только адрес. */
    fun link(title: String, url: String, card: Boolean): String {
        val name = title.trim()
        return if (card || name.isEmpty() || name == url.trim()) url.trim() else "$name ${url.trim()}"
    }

    private fun lines(blocks: List<RichBlock>, ref: (String, String, Long) -> CopiedRef?, unavailable: String, depth: Int): List<String> {
        val out = mutableListOf<String>()
        fun add(s: String?) {
            if (!s.isNullOrBlank()) out += s
        }
        for (b in blocks) when (b) {
            is RichBlock.Paragraph -> add(inline(b.text))
            is RichBlock.Heading -> add(inline(b.text))
            is RichBlock.Quote -> b.lines.forEach { add(inline(it)) }
            is RichBlock.Bullets -> bullets(b.items, "", ref, unavailable, depth).forEach(::add)
            is RichBlock.CodeBlock -> add(b.text)
            RichBlock.Divider -> add("———")
            is RichBlock.Table -> b.rows.forEach { row -> add(row.mapNotNull { it.text?.let(::inline) }.filter { it.isNotBlank() }.joinToString(" | ")) }
            is RichBlock.Row -> b.cells.filter { it.merge.isEmpty() }.forEach { cell ->
                cell.text?.let { add(inline(it)) }
                lines(cell.blocks + cell.widgets, ref, unavailable, depth).forEach(::add)
            }
            is RichBlock.Link -> add(link(b.title, b.url, card = b.mode == "card"))
            is RichBlock.Cloud -> add(link(b.title, b.url, card = false))
            is RichBlock.Place -> add(b.label.ifBlank { "${b.lat}, ${b.lon}" })
            is RichBlock.Contact -> add(listOf(b.name, b.phone).filter { it.isNotBlank() }.joinToString(" "))
            is RichBlock.Emoji -> add(b.text)
            is RichBlock.Fallback -> add(b.text)
            is RichBlock.Quoted -> quoted(b.kind, b.chatId, b.messageId, ref, unavailable, depth).forEach(::add)
            is RichBlock.Underlay -> quoted(b.kind, b.chatId, b.messageId, ref, unavailable, depth).forEach(::add)
            is RichBlock.Video -> add(listOf(b.description, b.url).filter { it.isNotBlank() }.joinToString(" "))
            is RichBlock.Audio -> add(listOf(b.description, b.url).filter { it.isNotBlank() }.joinToString(" "))
            is RichBlock.Stream -> add(listOf(b.description, b.url).filter { it.isNotBlank() }.joinToString(" "))
            is RichBlock.Marks -> b.marks.forEach { m -> add("${markTime(m.atMs)} ${m.text}".trim()) }
            is RichBlock.Reply, is RichBlock.Image, is RichBlock.Album, is RichBlock.File, is RichBlock.Voice -> Unit
        }
        return out
    }

    private fun bullets(items: List<Bullet>, indent: String, ref: (String, String, Long) -> CopiedRef?, unavailable: String, depth: Int): List<String> =
        items.flatMap { item ->
            listOf("$indent• ${inline(item.text)}") +
                lines(item.blocks, ref, unavailable, depth).map { "$indent  $it" } +
                bullets(item.children, "$indent  ", ref, unavailable, depth)
        }

    private fun quoted(kind: String, chatId: String, messageId: Long, ref: (String, String, Long) -> CopiedRef?, unavailable: String, depth: Int): List<String> {
        // Вставка во вставке не разворачивается.
        if (depth > 0) return emptyList()
        val found = ref(kind, chatId, messageId) ?: return listOf(unavailable)
        val body = found.rich?.let { lines(it.blocks, ref, unavailable, depth + 1) }?.takeIf { it.isNotEmpty() }
            ?: listOfNotNull(found.text?.takeIf { it.isNotBlank() })
        if (body.isEmpty()) return listOf(unavailable)
        return listOfNotNull(found.author?.takeIf { it.isNotBlank() }) + body
    }
}

/** Время метки: `м:сс` или `ч:мм:сс` (ПЛАН-(ВМ) §3.6). */
fun markTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = (s % 60).toString().padStart(2, '0')
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$sec" else "$m:$sec"
}

/** Время метки из строки `м:сс` или `ч:мм:сс`; не время — `null`. */
fun parseMarkTime(text: String): Long? {
    val parts = text.trim().split(':')
    if (parts.size !in 2..3 || parts.any { it.isEmpty() || it.any { c -> !c.isDigit() } }) return null
    val n = parts.map { it.toLong() }
    if (n.drop(1).any { it >= 60 }) return null
    return (if (n.size == 3) n[0] * 3600 + n[1] * 60 + n[2] else n[0] * 60 + n[1]) * 1000
}
