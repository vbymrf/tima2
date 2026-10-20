package io.tima.domain.chat

/**
 * Что из вставленного сообщения показывается (заказчик 2026-10-20). Сама ссылка в теле не меняется:
 * содержимое собирает получатель из своей копии исходного.
 *
 * - Подборка — в своём пузыре, поэтому только то, что не ломает разметку: текст (с оформлением
 *   строки) и первое фото. Ряды, списки, таблицы, файлы, виджеты не вставляются; начинается
 *   сообщение с них — «Откройте сообщение».
 * - Подкладка — без своего пузыря, во всю ширину основного: целиком, а с заданным числом строк — по
 *   [underlay].
 */
object RefCut {

    /** Подборка: текст по абзацам, первое фото; [closed] — начинается с ряда, списка или таблицы. */
    data class Quoted(val texts: List<RichText>, val photo: RichMedia?, val closed: Boolean)

    fun quoted(rich: RichContent): Quoted {
        val texts = mutableListOf<RichText>()
        var photo: RichMedia? = null
        // Начинается с ряда, списка или таблицы — «Откройте сообщение» вместо него (заказчик 2026-10-20).
        val first = rich.blocks.firstOrNull { it !is RichBlock.Reply && it !is RichBlock.Divider && !it.isPicture() }
        val closed = first is RichBlock.Row || first is RichBlock.Table || first is RichBlock.Bullets
        for (block in rich.blocks) {
            when (block) {
                is RichBlock.Paragraph -> texts += block.text
                is RichBlock.Heading -> texts += block.text
                is RichBlock.Quote -> texts += block.lines
                is RichBlock.Album -> if (photo == null) photo = block.media.firstOrNull()?.let { rich.media.getOrNull(it) }
                is RichBlock.Image -> if (photo == null) photo = rich.media.getOrNull(block.media)
                else -> Unit
            }
        }
        return Quoted(texts.filter { it.text.isNotBlank() }, photo, closed)
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
