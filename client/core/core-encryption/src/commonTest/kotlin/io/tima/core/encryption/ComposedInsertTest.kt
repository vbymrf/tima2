package io.tima.core.encryption

import io.tima.crypto.BlockType
import io.tima.domain.chat.ComposedDoc
import io.tima.domain.chat.ComposedItem
import io.tima.domain.chat.ComposedWidget
import io.tima.domain.chat.HtmlToMarks
import io.tima.domain.chat.OutMedia
import io.tima.domain.chat.RichBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Раздел «Вставка» (Решения §10.4): карточка ссылки с картинкой, из подборки, «С оформл.». */
class ComposedInsertTest {

    private val bodies = ComposedBodiesOverKodium

    @Test
    fun карточка_ссылки_несёт_картинку_в_теле() {
        val image = OutMedia("m-card", ByteArray(32) { 2 }, "image/jpeg", 720, 360, 900)
        val doc = ComposedDoc(listOf(ComposedItem.Widget(ComposedWidget.Link("https://tima.chat/a", "Сбор", "card", "Описание", image))))
        val content = bodies.content(doc)
        assertEquals("m-card", content.media.single().media_id)
        val link = content.markup!!.blocks.single().children.single().children.single()
        assertEquals(BlockType.LINK, link.type)
        assertEquals(0, link.widget!!.media)
        assertTrue(bodies.problems(doc).isEmpty())
        val back = bodies.load(bodies.save(doc))!!
        assertEquals("m-card", assertIs<ComposedWidget.Link>(assertIs<ComposedItem.Widget>(back.items.single()).widget).image!!.mediaId)
    }

    @Test
    fun из_подборки_ссылкой_а_не_копией() {
        val doc = ComposedDoc(listOf(ComposedItem.Widget(ComposedWidget.Quoted("group", "g-1", 42))))
        val rich = TextBodyCodec.decodeLine(assertNotNull(bodies.encode(doc)))!!.rich!!
        val quoted = assertIs<RichBlock.Quoted>(assertIs<RichBlock.Row>(rich.blocks.single()).cells.single().widgets.single())
        assertEquals(42, quoted.messageId)
        assertEquals(doc, bodies.load(bodies.save(doc)))
    }

    @Test
    fun с_оформлением_из_буфера_разбирается_как_набранное() {
        val text = HtmlToMarks.convert("<h2>План</h2><p>в <b>9:00</b>, 2*3</p><ul><li>раз</li></ul>")
        val content = bodies.content(ComposedDoc.of(text))
        assertEquals(listOf("План", "в 9:00, 2*3", "раз"), content.nodes)
        assertEquals(listOf(BlockType.HEADING, BlockType.PARAGRAPH, BlockType.LIST), content.markup!!.blocks.map { it.type })
    }

    @Test
    fun запись_страницы_открытым_телом_без_файлов() {
        val doc = ComposedDoc(listOf(ComposedItem.Text("# Сбор\nв **9**")))
        val open = assertNotNull(bodies.open(doc))
        assertEquals(listOf("Сбор", "в 9"), open.nodes)
        assertEquals("Сбор\nв 9", open.text)
        assertTrue(open.markup.startsWith("{\"v\":2,"), "разметка — v2 (ADR-0048): ${open.markup}")
        val withImage = ComposedDoc(listOf(ComposedItem.Widget(ComposedWidget.Image(OutMedia("m", ByteArray(32), "image/jpeg", 10, 10)))))
        assertEquals(null, bodies.open(withImage), "у записи канала нет файлов")
    }

    @Test
    fun подкладка_в_конце_а_продолжение_в_тело_не_идёт() {
        val doc = ComposedDoc(
            listOf(
                ComposedItem.Text("основное"),
                ComposedItem.Widget(ComposedWidget.Underlay("group", "g-1", 9, lines = 5, author = true)),
                ComposedItem.Continuation(3),
                ComposedItem.Text("закрытое"),
            ),
        )
        val content = bodies.content(doc)
        assertEquals(listOf("основное"), content.nodes, "что ниже метки — отдельное сообщение")
        val last = content.markup!!.blocks.last()
        assertEquals(BlockType.UNDERLAY, last.type)
        assertEquals(5, last.widget!!.lines)
        assertTrue(bodies.problems(doc).isEmpty())
        val rich = TextBodyCodec.decodeLine(assertNotNull(bodies.encode(doc)))!!.rich!!
        assertIs<RichBlock.Underlay>(rich.blocks.last())
        assertEquals(doc, bodies.load(bodies.save(doc)), "черновик с меткой и подкладкой переживает сохранение")
    }
}
