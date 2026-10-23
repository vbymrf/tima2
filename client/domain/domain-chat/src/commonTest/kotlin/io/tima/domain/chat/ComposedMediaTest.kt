package io.tima.domain.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Видео, звук и метки в документе редактора (ПЛАН-(ВМ) Р9, Р10, Р24, Р27). */
class ComposedMediaTest {
    private fun w(widget: ComposedWidget) = ComposedItem.Widget(widget)
    private fun marks(bind: String, vararg at: Long) = ComposedWidget.Marks(bind, at.map { MediaMark(it, "м$it") })

    @Test
    fun описания_разводятся_и_метки_находят_своё_видео() {
        val doc = ComposedDoc(items = listOf(
            w(ComposedWidget.Video("https://x.ru/a.mp4", "Обзор")),
            w(ComposedWidget.Audio("https://x.ru/a.mp3", "Подкаст", 61_000)),
            w(ComposedWidget.Stream("https://r.ru/l.m3u8", "Радио")),
        ))
        assertEquals(listOf("Обзор", "Подкаст"), ComposedMedia.bindable(doc))
        assertEquals("Обзор (2)", ComposedMedia.unique(doc, "Обзор"))
        assertEquals("Радио (2)", ComposedMedia.unique(doc, "Радио"))
        assertEquals("Новое", ComposedMedia.unique(doc, "Новое"))
        assertEquals("Новое (2)", ComposedMedia.unique(doc, "Новое", taken = listOf("Новое")))
        assertNotNull(ComposedMedia.target(doc, "Подкаст"))
        assertNull(ComposedMedia.target(doc, "Радио"), "у потока меток нет")
        assertEquals(3, ComposedMedia.count(doc))
    }

    @Test
    fun метки_без_видео_убираются_и_встают_по_времени() {
        val doc = ComposedDoc(items = listOf(
            w(ComposedWidget.Video("https://x.ru/a.mp4", "Обзор")),
            w(marks("Обзор", 30_000, 10_000)),
            w(marks("Удалённое", 1_000)),
        ))
        val pruned = ComposedMedia.prune(doc)
        assertEquals(2, pruned.items.size)
        assertEquals(listOf(10_000L, 30_000L), (ComposedMedia.marksOf(pruned, "Обзор")!!).marks.map { it.atMs })
    }

    @Test
    fun замена_меток_на_месте() {
        val doc = ComposedDoc(items = listOf(w(ComposedWidget.Video("https://x.ru/a.mp4", "Обзор")), w(marks("Обзор", 1_000))))
        val next = ComposedMedia.replace(doc, { it is ComposedWidget.Marks && it.bind == "Обзор" }, marks("Обзор", 2_000, 3_000))!!
        assertEquals(listOf(2_000L, 3_000L), ComposedMedia.marksOf(next, "Обзор")!!.marks.map { it.atMs })
        assertNull(ComposedMedia.replace(doc, { it is ComposedWidget.Stream }, marks("x", 1)))
    }

    @Test
    fun список_ссылок_из_буфера() {
        assertEquals(
            listOf("https://a.ru/1.mp3", "https://b.ru/x,y.mp3", "https://c.ru/3.mp3", "http://d.ru/4.mp3"),
            ComposedMedia.splitLinks("https://a.ru/1.mp3 https://b.ru/x,y.mp3,https://c.ru/3.mp3;http://d.ru/4.mp3\nне ссылка"),
        )
    }

    @Test
    fun время_меток_туда_и_обратно() {
        assertEquals("1:05", markTime(65_000))
        assertEquals("1:02:03", markTime(3_723_000))
        assertEquals(65_000L, parseMarkTime("1:05"))
        assertEquals(3_723_000L, parseMarkTime("1:02:03"))
        assertNull(parseMarkTime("1:75"))
        assertNull(parseMarkTime("abc"))
        assertNull(parseMarkTime("5"))
    }
}
