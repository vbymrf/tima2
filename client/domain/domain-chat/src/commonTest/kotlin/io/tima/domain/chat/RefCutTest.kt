package io.tima.domain.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Что из вставленного сообщения показывается (заказчик 2026-10-20). */
class RefCutTest {

    private fun p(text: String) = RichBlock.Paragraph(RichText(text))
    private val photo = RichMedia("m-1", ByteArray(0), "image/jpeg", 10, 10)
    private val row = RichBlock.Row(listOf(RowCell(null, RichText("я"))))
    private fun list(vararg titles: String) = RichBlock.Bullets(titles.map { Bullet(RichText(it), blocks = listOf(p("под"))) }, closed = true)

    @Test
    fun подборка_только_текст_и_первое_фото() {
        val shown = RefCut.quoted(RichContent(listOf(p("раз"), row, list("а"), RichBlock.Album(listOf(0)), p("два")), listOf(photo)))
        assertEquals(listOf("раз", "два"), shown.texts.map { it.text }, "ряды и списки не вставляются")
        assertEquals("m-1", shown.photo?.mediaId)
        assertEquals(false, shown.closed)
    }

    @Test
    fun подборка_с_ряда_или_списка_первым_откройте_сообщение() {
        assertTrue(RefCut.quoted(RichContent(listOf(row, p("потом")))).closed, "первым ряд — фраза, даже если текст дальше есть")
        val only = RefCut.quoted(RichContent(listOf(list("а", "б"))))
        assertTrue(only.closed)
        assertNull(only.photo)
    }

    @Test
    fun подкладка_целиком_как_есть() {
        val blocks = listOf(p("раз"), row, list("а"))
        assertEquals(blocks, RefCut.underlay(RichContent(blocks), 0))
    }

    @Test
    fun подкладка_с_ряда_ряд_целиком_строки_не_считаются() {
        val pic = RichBlock.Album(listOf(0))
        assertEquals(listOf(pic, row), RefCut.underlay(RichContent(listOf(pic, row, p("раз\nдва"))), 1))
    }

    @Test
    fun строки_текста_до_ряда_картинка_в_пределах_строк_берётся() {
        val pic = RichBlock.Album(listOf(0))
        val cut = RefCut.underlay(RichContent(listOf(p("раз"), pic, p("два\nтри\nчетыре"), row)), 3)
        assertEquals(3, cut.size)
        assertEquals(pic, cut[1])
        assertEquals("два\nтри", assertIs<RichBlock.Paragraph>(cut[2]).text.text)
    }

    @Test
    fun пункт_списка_одна_строка() {
        // Заказчик: три строки — первым текст, далее список — от списка берутся два пункта.
        val cut = RefCut.underlay(RichContent(listOf(p("раз"), list("а", "б", "в"))), 3)
        val bullets = assertIs<RichBlock.Bullets>(cut[1])
        assertEquals(listOf("а", "б"), bullets.items.map { it.text.text })
        assertTrue(bullets.items.all { it.blocks.isEmpty() }, "содержимое под пунктом не берётся")
        assertEquals(false, bullets.closed, "обрезанный список — раскрыт")
    }

    @Test
    fun оформление_строки_обрезается_вместе_с_текстом() {
        val text = RichText("раз\nдва", listOf(RichSpan(SpanKind.BOLD, 0, 7)))
        val cut = assertIs<RichBlock.Paragraph>(RefCut.underlay(RichContent(listOf(RichBlock.Paragraph(text))), 1).single())
        assertEquals(RichText("раз", listOf(RichSpan(SpanKind.BOLD, 0, 3))), cut.text)
    }
}
