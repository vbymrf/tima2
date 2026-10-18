package io.tima.crypto

import io.tima.crypto.proto.MediaRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Проверка разметки редактора (ADR-0044 §5). */
class MarkupRulesTest {

    private fun cell(width: Int, node: Int? = null, vararg widgets: MarkupBlock) = MarkupBlock(
        type = BlockType.CELL,
        nodeIds = listOfNotNull(node),
        children = widgets.toList(),
        style = mapOf("width" to width.toString()),
    )

    private fun image(index: Int) = MarkupBlock(type = BlockType.IMAGE, widget = MarkupWidget(media = index))

    private fun media(n: Int) = List(n) { MediaRef(media_id = "m$it") }

    private fun sample(): MessageContent = MarkupSample.content()

    private fun rules(c: MessageContent, strict: Boolean = true) = MarkupRules.check(c, strict).map { it.rule }

    @Test
    fun образцовое_сообщение_редактора_проходит() {
        assertEquals(emptyList(), MarkupRules.check(sample(), strict = true))
    }

    @Test
    fun простое_сообщение_без_разметки_проходит() {
        assertEquals(emptyList(), MarkupRules.check(MessageContent.text("привет"), strict = true))
    }

    @Test
    fun текст_длиннее_4096_знаков_не_проходит() {
        assertTrue("node-chars" in rules(MessageContent.text("а".repeat(4097))))
    }

    @Test
    fun ширины_ряда_в_сумме_не_100_не_проходят() {
        val c = sample().let { s ->
            s.copy(markup = s.markup!!.copy(blocks = listOf(
                MarkupBlock(type = BlockType.FLEX, children = listOf(cell(50, 2), cell(40))),
            )))
        }
        assertTrue("width" in rules(c))
    }

    @Test
    fun пять_ячеек_в_ряду_не_проходят() {
        val c = MessageContent(nodes = listOf("x"), markup = Markup(n = listOf(1), blocks = listOf(
            MarkupBlock(type = BlockType.FLEX, children = List(5) { cell(20) }),
        )))
        assertTrue("cells" in rules(c))
    }

    @Test
    fun ячейка_ряда_один_узел() {
        val c = MessageContent(nodes = listOf("a", "b"), markup = Markup(n = listOf(1, 2), blocks = listOf(
            MarkupBlock(type = BlockType.FLEX, children = listOf(
                MarkupBlock(type = BlockType.CELL, nodeIds = listOf(1, 2), style = mapOf("width" to "100")),
            )),
        )))
        assertTrue("cell-node" in rules(c))
    }

    @Test
    fun подкладка_только_в_конце() {
        val s = sample()
        val blocks = s.markup!!.blocks
        val moved = blocks.last() // подкладка
        val c = s.copy(markup = s.markup.copy(blocks = listOf(moved) + blocks.dropLast(1)))
        assertTrue("underlay-last" in rules(c))
    }

    @Test
    fun цвет_кодом_и_незнакомая_ступень_не_проходят() {
        val c = MessageContent(nodes = listOf("a"), markup = Markup(n = listOf(1), blocks = listOf(
            MarkupBlock(type = BlockType.PARAGRAPH, nodeIds = listOf(1), style = mapOf("color" to "#ff0000", "padding" to "huge")),
        )))
        val r = rules(c)
        assertTrue("palette" in r)
        assertTrue("step" in r)
    }

    @Test
    fun больше_десяти_картинок_не_проходят() {
        val cells = List(4) { i -> cell(25, null, image(i * 3), image(i * 3 + 1), image(i * 3 + 2)) }
        val c = MessageContent(
            nodes = listOf("x"),
            markup = Markup(n = listOf(1), blocks = listOf(MarkupBlock(type = BlockType.FLEX, children = cells))),
            media = media(12),
        )
        assertTrue("images" in rules(c))
    }

    @Test
    fun картинка_без_файла_в_теле_не_проходит() {
        val c = sample().copy(media = emptyList())
        assertTrue("widget" in rules(c))
    }

    @Test
    fun таблица_в_ячейке_ряда_допустима() {
        val table = MarkupBlock(type = BlockType.TABLE, children = listOf(
            MarkupBlock(type = BlockType.ROW, children = listOf(MarkupBlock(type = BlockType.CELL, nodeIds = listOf(2)))),
        ))
        val c = MessageContent(nodes = listOf("a", "99"), markup = Markup(n = listOf(1, 2), blocks = listOf(
            MarkupBlock(type = BlockType.FLEX, children = listOf(cell(40, 1), cell(60, null, table))),
        )))
        assertEquals(emptyList(), MarkupRules.check(c, strict = true))
    }

    @Test
    fun незнакомое_строго_ошибка_мягко_нет() {
        // Разметка от более новой версии клиента: тип и свойство, которых эта версия не знает.
        val c = MessageContent(nodes = listOf("a"), markup = Markup(n = listOf(1), blocks = listOf(
            MarkupBlock(type = BlockType.PARAGRAPH, nodeIds = listOf(1), style = mapOf("glow" to "on")),
            MarkupBlock(type = BlockType.UNKNOWN),
        )))
        assertTrue(rules(c, strict = true).isNotEmpty())
        assertEquals(emptyList(), MarkupRules.check(c, strict = false))
    }

    @Test
    fun разметка_переживает_круговой_путь_через_тело() {
        val s = sample()
        val back = MessageContentCodec.fromBody(MessageContentCodec.toBody(s))
        assertEquals(s.markup, back.markup)
        assertEquals(emptyList(), MarkupRules.check(back, strict = true))
    }

    @Test
    fun первый_текст_для_списка_и_поиска() {
        assertEquals("Поход в субботу", sample().firstText())
        assertEquals(null, MessageContent(nodes = listOf("", "  ")).firstText())
    }
}
