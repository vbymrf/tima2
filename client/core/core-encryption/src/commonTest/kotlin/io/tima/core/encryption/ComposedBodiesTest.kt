package io.tima.core.encryption

import io.tima.crypto.BlockType
import io.tima.domain.chat.ComposedCell
import io.tima.domain.chat.ComposedDoc
import io.tima.domain.chat.ComposedItem
import io.tima.domain.chat.ComposedWidget
import io.tima.domain.chat.OutMedia
import io.tima.domain.chat.ReplyTarget
import io.tima.domain.chat.RichBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Документ редактора собирается в узлы и разметку и возвращается экрану тем, что увидит получатель. */
class ComposedBodiesTest {

    private val bodies = ComposedBodiesOverKodium

    @Test
    fun один_абзац_без_знаков_уходит_обычным_сообщением() {
        val content = bodies.content(ComposedDoc.of("привет"))
        assertEquals(listOf("привет"), content.nodes)
        assertNull(content.markup)
    }

    @Test
    fun куски_получают_сквозные_номера_узлов() {
        val doc = ComposedDoc(
            listOf(
                ComposedItem.Text("# Сбор\nв **9:00**"),
                ComposedItem.Row(listOf(ComposedCell(40, "*слева*"), ComposedCell(60, "справа", listOf(ComposedWidget.Emoji("🎉"))))),
                ComposedItem.Text("конец", style = mapOf("color" to "blue")),
            ),
        )
        val content = bodies.content(doc)
        assertEquals(listOf("Сбор", "в 9:00", "слева", "справа", "конец"), content.nodes)
        val markup = assertNotNull(content.markup)
        assertEquals(listOf(1, 2, 3, 4, 5), markup.n)
        assertEquals(listOf(BlockType.HEADING, BlockType.PARAGRAPH, BlockType.FLEX, BlockType.PARAGRAPH), markup.blocks.map { it.type })
        assertEquals(setOf(2, 3), markup.entities.map { it.nodeId }.toSet(), "жирный — во втором узле, курсив — в ячейке")
        assertEquals("blue", markup.blocks.last().style["color"])
        assertTrue(bodies.problems(doc).isEmpty())

        val rich = TextBodyCodec.decodeLine(assertNotNull(bodies.encode(doc)))!!.rich!!
        val row = assertIs<RichBlock.Row>(rich.blocks[2])
        assertEquals(listOf(40, 60), row.cells.map { it.width })
        assertEquals(RichBlock.Emoji("🎉"), row.cells[1].widgets.single())
    }

    /** ADR-0048: знак, набранный текстом, в документе экранирован — в тело уходит текстом. */
    @Test
    fun знаки_выключены_текст_как_есть() {
        val typed = TypedOverQuickMarkdown.write(io.tima.domain.chat.Typed.plain("**не жирный**"))
        val content = bodies.content(ComposedDoc.of(typed, marks = false))
        assertEquals(listOf("**не жирный**"), content.nodes)
        assertNull(content.markup)
    }

    /** Черновик до 179 со снятыми «Знаками md» хранил текст как набран — при чтении знаки экранируются. */
    @Test
    fun прежний_черновик_без_знаков_читается_текстом() {
        val old = bodies.load("""{"items":[{"text":"**x** # y"}],"marks":false}""")!!
        assertEquals(listOf("**x** # y"), bodies.content(old).nodes)
        val kept = bodies.load("""{"items":[{"text":"**x**"}],"marks":true}""")!!
        assertEquals(listOf("x"), bodies.content(kept).nodes, "со знаками — как было")
    }

    @Test
    fun нарушение_правил_не_отправляется() {
        val bad = ComposedDoc(listOf(ComposedItem.Row(listOf(ComposedCell(50, "a"), ComposedCell(30, "b")))))
        assertTrue(bodies.problems(bad).any { "100" in it })
        assertNull(bodies.encode(bad))
    }

    @Test
    fun виджет_во_всю_ширину_и_ответ_первым() {
        val doc = ComposedDoc(listOf(ComposedItem.Widget(ComposedWidget.Place(55.75, 37.61, "Сбор")), ComposedItem.Text("тут")))
        val reply = ReplyTarget("personal", "chat-1", 7, "u-1")
        val rich = TextBodyCodec.decodeLine(assertNotNull(bodies.encode(doc, reply)))!!.rich!!
        assertIs<RichBlock.Reply>(rich.blocks.first())
        assertEquals(100, assertIs<RichBlock.Row>(rich.blocks[1]).cells.single().width)
    }

    @Test
    fun черновик_переживает_сохранение() {
        val doc = ComposedDoc(
            listOf(
                ComposedItem.Text("# a", mapOf("background" to "amber-soft")),
                ComposedItem.Widget(ComposedWidget.Image(OutMedia("m-1", ByteArray(32) { 5 }, "image/jpeg", 10, 20, 300, ByteArray(3) { 1 }))),
                ComposedItem.Row(listOf(ComposedCell(100, "x", listOf(ComposedWidget.Contact("Аня", "+7"))))),
                ComposedItem.Widget(ComposedWidget.Reply(ReplyTarget("group", "g", 3, "u"))),
            ),
            marks = false,
            level = 2,
        )
        val back = assertNotNull(bodies.load(bodies.save(doc)))
        assertEquals(doc.marks, back.marks)
        assertEquals(doc.level, back.level)
        assertEquals(doc.items[0], back.items[0])
        assertEquals(doc.items[2], back.items[2])
        assertEquals(doc.items[3], back.items[3])
        val image = assertIs<ComposedWidget.Image>(assertIs<ComposedItem.Widget>(back.items[1]).widget).media
        assertEquals((ByteArray(32) { 5 }).toList(), image.key.toList())
        assertEquals(3, image.preview.size)
        assertNull(bodies.load("не json"))
    }
}
