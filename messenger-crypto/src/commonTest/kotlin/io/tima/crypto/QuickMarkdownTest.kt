package io.tima.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Быстрое оформление (doc_mig/РЕДАКТОР-СООБЩЕНИЙ.md §7, ПЛАН-(РС) РС2). */
class QuickMarkdownTest {

    private fun parse(s: String) = QuickMarkdown.parse(s).also {
        // Любой разбор обязан давать разметку, которую примет строгая проверка.
        assertEquals(emptyList(), MarkupRules.check(it, strict = true), "разбор «$s» дал разметку, которую проверка не примет")
    }

    /** Текст одного узла и его отрезки: вид → подстрока. */
    private fun inline(s: String): Pair<String, List<Pair<EntityType, String>>> {
        val c = parse(s)
        val text = c.nodes.single()
        val spans = c.markup?.entities.orEmpty().map { it.type to text.substring(it.start, it.start + it.length) }
        return text to spans
    }

    @Test
    fun простой_текст_без_знаков_уходит_без_разметки() {
        val c = parse("привет, как дела\nвторая строка")
        assertNull(c.markup)
        assertEquals(listOf("привет, как дела\nвторая строка"), c.nodes)
    }

    @Test
    fun жирный_курсив_подчёркнутый_маркер_зачёркнутый_код() {
        val (text, spans) = inline("**ж** *к* ++ч++ ==м== ~~з~~ `код`")
        assertEquals("ж к ч м з код", text)
        assertEquals(
            listOf(
                EntityType.BOLD to "ж", EntityType.ITALIC to "к", EntityType.UNDERLINE to "ч",
                EntityType.HIGHLIGHT to "м", EntityType.STRIKETHROUGH to "з", EntityType.CODE to "код",
            ),
            spans,
        )
    }

    @Test
    fun вложенное_оформление() {
        val (text, spans) = inline("**жирный *и курсив* внутри**")
        assertEquals("жирный и курсив внутри", text)
        assertTrue(EntityType.BOLD to "жирный и курсив внутри" in spans)
        assertTrue(EntityType.ITALIC to "и курсив" in spans)
    }

    @Test
    fun подчёркивание_и_init_ничего_не_значат() {
        val (text, spans) = inline("file_name_v2 и __init__ и _текст_")
        assertEquals("file_name_v2 и __init__ и _текст_", text)
        assertEquals(emptyList(), spans)
    }

    @Test
    fun знак_без_пары_и_с_пробелами_остаётся_текстом() {
        assertEquals("на C++ и C++" to emptyList(), inline("на C++ и C++"))
        assertEquals("a == b" to emptyList(), inline("a == b"))
        assertEquals("--force и i--" to emptyList(), inline("--force и i--"))
        assertEquals("x++ и ++i" to emptyList(), inline("x++ и ++i"))
    }

    @Test
    fun звёздочка_внутри_слова_по_CommonMark() {
        val (text, spans) = inline("2*3*4")
        assertEquals("234", text)
        assertEquals(listOf(EntityType.ITALIC to "3"), spans)
    }

    @Test
    fun экранирование_делает_знак_текстом() {
        assertEquals("**не жирный** и #не-тег" to emptyList(), inline("\\*\\*не жирный\\*\\* и \\#не-тег"))
    }

    @Test
    fun внутри_кода_и_адреса_разметки_нет() {
        val (text, spans) = inline("`**код**` и https://site.ru/a_b_*c* конец")
        assertEquals("**код** и https://site.ru/a_b_*c* конец", text)
        assertEquals(listOf(EntityType.CODE to "**код**"), spans)
    }

    @Test
    fun хештег_и_упоминание() {
        val (text, spans) = inline("#Поход для @Анна.")
        assertEquals("#Поход для @Анна.", text)
        assertEquals(listOf(EntityType.HASHTAG to "#Поход", EntityType.MENTION to "@Анна"), spans)
        val c = parse("#Поход")
        assertEquals("поход", c.markup!!.entities.first().attribute)
    }

    @Test
    fun заголовки_трёх_уровней_и_тег_без_пробела_не_заголовок() {
        val c = parse("# Один\n## Два\n### Три\n#тег")
        val blocks = c.markup!!.blocks
        assertEquals(listOf(1, 2, 3), blocks.take(3).map { it.level })
        assertTrue(blocks.take(3).all { it.type == BlockType.HEADING })
        assertEquals(BlockType.PARAGRAPH, blocks[3].type)
        assertEquals(listOf("Один", "Два", "Три", "#тег"), c.nodes)
    }

    @Test
    fun пробелы_перед_знаком_в_начале_строки_допустимы() {
        val c = parse("   ## Заголовок\n   --> цитата\n   ---")
        assertEquals(listOf(BlockType.HEADING, BlockType.QUOTE, BlockType.DIVIDER), c.markup!!.blocks.map { it.type })
    }

    @Test
    fun цитата_подряд_идущими_строками() {
        val c = parse("--> первая\n-->вторая\nпосле")
        val quote = c.markup!!.blocks.first()
        assertEquals(BlockType.QUOTE, quote.type)
        assertEquals(listOf("первая", "вторая"), quote.nodeIds.map { c.nodes[it - 1] })
    }

    @Test
    fun список_вложенность_пробелами_глубже_третьего_прижимается() {
        val c = parse("- 1\n   - 1.1\n      - 1.1.1\n         - глубже\n- 2")
        val list = c.markup!!.blocks.single()
        assertEquals(BlockType.LIST, list.type)
        assertEquals(2, list.children.size, "верхних пунктов два")
        val level2 = list.children[0].children.single()
        val level3 = level2.children[0].children.single()
        // «глубже» прижат к третьему уровню — рядом с «1.1.1», а не ниже.
        assertEquals(listOf("1.1.1", "глубже"), level3.children.map { c.nodes[it.nodeIds.single() - 1] })
        assertTrue(level3.children.all { it.children.isEmpty() })
    }

    @Test
    fun номер_в_пункте_это_имя_а_не_нумерация() {
        val c = parse("- 1.1) первое\n2024. Год")
        assertEquals("1.1) первое", c.nodes[0])
        assertEquals(BlockType.PARAGRAPH, c.markup!!.blocks[1].type, "нумерованных списков нет")
    }

    @Test
    fun таблица_с_выравниванием_и_объединением() {
        val c = parse("| Что | Сколько |\n| --- | ---: |\n| вода | 6 |\n| ^^ | << |")
        val table = c.markup!!.blocks.single()
        assertEquals(BlockType.TABLE, table.type)
        assertEquals(3, table.children.size)
        val last = table.children[2].children
        assertEquals("up", last[0].style["merge"])
        assertEquals("left", last[1].style["merge"])
        assertTrue(last.all { it.nodeIds.isEmpty() }, "объединённая ячейка без своего текста")
        assertEquals("end", table.children[0].children[1].style["align"])
    }

    @Test
    fun блок_кода_без_разбора_внутри() {
        val c = parse("```\n**не жирный**\n# не заголовок\n```")
        val code = c.markup!!.blocks.single()
        assertEquals(BlockType.CODE_BLOCK, code.type)
        assertEquals("**не жирный**\n# не заголовок", c.nodes.single())
        assertEquals(emptyList(), c.markup!!.entities)
    }

    @Test
    fun знаки_выключены_текст_как_есть() {
        val c = QuickMarkdown.plain("Итого **1** место")
        assertNull(c.markup)
        assertEquals(listOf("Итого **1** место"), c.nodes)
    }

    @Test
    fun строка_ввода_знаки_серым_текст_оформлен() {
        // Решения §7: в строке ввода знаки видны, текст уже оформлен; набранное не меняется.
        val marks = QuickMarkdown.typing("**ж** ++ч++")
        val kinds = marks.map { Triple(it.kind, it.start, it.end) }.toSet()
        assertEquals(
            setOf(
                Triple(QuickMarkdown.TypingKind.MARKER, 0, 2), Triple(QuickMarkdown.TypingKind.BOLD, 2, 3),
                Triple(QuickMarkdown.TypingKind.MARKER, 3, 5), Triple(QuickMarkdown.TypingKind.MARKER, 6, 8),
                Triple(QuickMarkdown.TypingKind.UNDERLINE, 8, 9), Triple(QuickMarkdown.TypingKind.MARKER, 9, 11),
            ),
            kinds,
        )
    }

    @Test
    fun строка_ввода_заголовок_и_вторая_строка() {
        val marks = QuickMarkdown.typing("# Поход\nсбор `9:00`")
        assertTrue(QuickMarkdown.TypingMark(QuickMarkdown.TypingKind.MARKER, 0, 2) in marks)
        assertTrue(QuickMarkdown.TypingMark(QuickMarkdown.TypingKind.HEADING, 2, 7) in marks)
        // Вторая строка начинается с 8: «сбор » — 5 знаков, апостроф на 13.
        assertTrue(QuickMarkdown.TypingMark(QuickMarkdown.TypingKind.MARKER, 13, 14) in marks)
        assertTrue(QuickMarkdown.TypingMark(QuickMarkdown.TypingKind.CODE, 14, 18) in marks)
    }

    @Test
    fun строка_ввода_знак_без_пары_не_серый() {
        assertEquals(emptyList(), QuickMarkdown.typing("на C++ и a == b"))
    }

    @Test
    fun тире_в_начале_строки_становится_списком() {
        // §7: «- » как тире в диалоге превращается в список; вернуть — стиранием сразу после.
        val c = parse("- Привет")
        assertEquals(BlockType.LIST, c.markup!!.blocks.single().type)
    }
}
