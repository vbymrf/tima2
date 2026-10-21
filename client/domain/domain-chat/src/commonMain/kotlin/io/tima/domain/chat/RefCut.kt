package io.tima.domain.chat

/**
 * Что из вставленного сообщения показывается. Сама ссылка в теле не меняется: содержимое собирает
 * получатель из своей копии исходного.
 *
 * - Подборка — в своём пузыре (фон без рамки; встаёт и в ряд), поэтому только то, что не ломает
 *   разметку (заказчик 2026-10-21): текст, первая картинка, эмоджи, списки без вложенных сообщений,
 *   ссылки — кнопкой или текстом, карточка — кнопкой. Ряды, таблицы, файлы, облако, место, контакт,
 *   вложенные сообщения — нет; начинается с ряда или таблицы — «Откройте сообщение».
 * - Подкладка — без своего пузыря, во всю ширину основного: целиком, а с заданным числом строк — по
 *   [underlay].
 */
object RefCut {

    /** Подборка: что показать по порядку; [closed] — начинается с ряда или таблицы. */
    data class Quoted(val blocks: List<RichBlock>, val closed: Boolean)

    fun quoted(rich: RichContent): Quoted {
        val first = rich.blocks.firstOrNull { it !is RichBlock.Reply && it !is RichBlock.Divider && !it.isPicture() }
        val closed = first is RichBlock.Row || first is RichBlock.Table
        var picture = false
        val out = mutableListOf<RichBlock>()
        for (block in rich.blocks) {
            when (block) {
                is RichBlock.Paragraph, is RichBlock.Heading, is RichBlock.Quote, is RichBlock.CodeBlock, is RichBlock.Emoji -> out += block
                is RichBlock.Bullets -> out += block.copy(items = withoutRefs(block.items))
                is RichBlock.Link -> out += if (block.mode == "card") block.copy(mode = "button") else block
                // Картинка — только первая.
                is RichBlock.Album -> if (!picture) {
                    picture = true
                    block.media.firstOrNull()?.let { out += RichBlock.Album(listOf(it)) }
                }
                is RichBlock.Image -> if (!picture) {
                    picture = true
                    out += block
                }
                else -> Unit
            }
        }
        return Quoted(out, closed)
    }

    /** Пункты без вложенных сообщений (подборки, подкладки, ответа) — в подборке их нет. */
    private fun withoutRefs(items: List<Bullet>): List<Bullet> = items.map { item ->
        item.copy(
            blocks = item.blocks.filterNot { it is RichBlock.Quoted || it is RichBlock.Underlay || it is RichBlock.Reply },
            children = withoutRefs(item.children),
        )
    }

    /**
     * Подкладка, [lines] строк (0 — целиком). Начинается с ряда или таблицы — ряд целиком, строки не
     * считаются: так ничего не сломается. Иначе строки считает текст до первого ряда или таблицы;
     * пункт списка — одна строка; картинка, стоящая в пределах этих строк, берётся и строк не
     * занимает. Ответ и разделитель пропускаются.
     */
    fun underlay(rich: RichContent, lines: Int): List<RichBlock> {
        if (lines <= 0) return rich.blocks
        val blocks = rich.blocks.filter { it !is RichBlock.Divider && it !is RichBlock.Reply }
        val first = blocks.firstOrNull { !it.isPicture() }
        if (first is RichBlock.Row || first is RichBlock.Table) {
            return blocks.takeWhile { it.isPicture() } + first
        }
        val out = mutableListOf<RichBlock>()
        var left = lines
        for (block in blocks) {
            if (left <= 0) break
            when (block) {
                is RichBlock.Album, is RichBlock.Image -> out += block
                is RichBlock.Paragraph -> block.text.firstLines(left).let { (text, n) -> out += block.copy(text = text); left -= n }
                is RichBlock.Heading -> block.text.firstLines(left).let { (text, n) -> out += block.copy(text = text); left -= n }
                is RichBlock.Quote -> block.lines.take(left).let { out += block.copy(lines = it); left -= it.size }
                is RichBlock.CodeBlock -> block.text.split('\n').take(left).let { out += block.copy(text = it.joinToString("\n")); left -= it.size }
                // Эмоджи и ссылка — по строке (2026-10-21: подборка показывает их тоже).
                is RichBlock.Emoji, is RichBlock.Link -> {
                    out += block
                    left -= 1
                }
                is RichBlock.Bullets -> {
                    val budget = intArrayOf(left)
                    val items = cutItems(block.items, budget)
                    out += block.copy(items = items, closed = false)
                    left = budget[0]
                }
                else -> break
            }
        }
        return out
    }

    private fun RichBlock.isPicture() = this is RichBlock.Album || this is RichBlock.Image

    /** Пункты по порядку, сверху вниз, не больше [left]; содержимое под пунктом не берётся. */
    private fun cutItems(items: List<Bullet>, left: IntArray): List<Bullet> = buildList {
        for (item in items) {
            if (left[0] <= 0) break
            left[0]--
            add(item.copy(children = cutItems(item.children, left), blocks = emptyList()))
        }
    }

    /** Первые [n] строк текста (по переводам строки) и сколько их взято. */
    private fun RichText.firstLines(n: Int): Pair<RichText, Int> {
        val all = text.split('\n')
        if (all.size <= n) return this to all.size
        val end = all.take(n).sumOf { it.length } + (n - 1)
        val spans = spans.filter { it.start < end }.map { if (it.end > end) it.copy(end = end) else it }
        return RichText(text.substring(0, end), spans) to n
    }
}
