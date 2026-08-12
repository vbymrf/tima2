package io.tima.app

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import io.tima.app.chat.renderInline
import io.tima.app.chat.renderMessageContent
import io.tima.crypto.EntityType
import io.tima.crypto.MarkupEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Точечный рендер инлайн-стилей (ADR-0011 §8, Р5б) — без блочного рендера и без Compose-раннера. */
class InlineStyleTest {

    @Test
    fun `сообщение без сущностей - обычная строка, стилей нет`() {
        val out = renderInline("привет", emptyList())
        assertEquals("привет", out.text)
        assertTrue(out.spanStyles.isEmpty())
    }

    @Test
    fun `жирный диапазон применяется`() {
        val out = renderInline("жирный текст", listOf(MarkupEntity(type = EntityType.BOLD, nodeId = 1, start = 0, length = 6)))
        assertEquals("жирный текст", out.text)
        val span = out.spanStyles.single()
        assertEquals(SpanStyle(fontWeight = FontWeight.Bold), span.item)
        assertEquals(0, span.start)
        assertEquals(6, span.end)
    }

    @Test
    fun `несколько сущностей не пересекаются`() {
        val out = renderInline(
            "жирный и курсив",
            listOf(
                MarkupEntity(type = EntityType.BOLD, nodeId = 1, start = 0, length = 6),
                MarkupEntity(type = EntityType.ITALIC, nodeId = 1, start = 9, length = 6),
            ),
        )
        assertEquals(2, out.spanStyles.size)
        assertTrue(out.spanStyles.any { it.item == SpanStyle(fontWeight = FontWeight.Bold) && it.start == 0 && it.end == 6 })
        assertTrue(out.spanStyles.any { it.item == SpanStyle(fontStyle = FontStyle.Italic) && it.start == 9 && it.end == 15 })
    }

    @Test
    fun `сущность за пределами текста пропускается - не роняет сообщение`() {
        val out = renderInline("коротко", listOf(MarkupEntity(type = EntityType.BOLD, nodeId = 1, start = 0, length = 999)))
        assertEquals("коротко", out.text)
        assertTrue(out.spanStyles.isEmpty())
    }

    @Test
    fun `неизвестный тип сущности - текст остаётся без оформления`() {
        val out = renderInline("текст", listOf(MarkupEntity(type = EntityType.UNKNOWN, nodeId = 1, start = 0, length = 5)))
        assertEquals("текст", out.text)
        assertEquals(SpanStyle(), out.spanStyles.single().item)
    }

    @Test
    fun `без разметки - обычный текст без оформления`() {
        val out = renderMessageContent("просто текст", "")
        assertEquals("просто текст", out.text)
        assertTrue(out.spanStyles.isEmpty())
    }

    @Test
    fun `один узел с разметкой - инлайн применяется`() {
        val markup = """{"version":1,"n":[5],"entities":[{"type":"bold","node":5,"start":0,"length":4}]}"""
        val out = renderMessageContent("жирный текст", markup)
        assertEquals("жирный текст", out.text)
        val span = out.spanStyles.single()
        assertEquals(SpanStyle(fontWeight = FontWeight.Bold), span.item)
        assertEquals(0, span.start)
        assertEquals(4, span.end)
    }

    @Test
    fun `несколько узлов - без инлайна, текст цел`() {
        // n содержит 2 узла — смещения сущностей заданы для конкретного узла, а не
        // для склеенного текста; применять их вслепую значило бы разъехаться.
        val markup = """{"version":1,"n":[1,2],"entities":[{"type":"bold","node":1,"start":0,"length":3}]}"""
        val out = renderMessageContent("Заголовоктекст", markup)
        assertEquals("Заголовоктекст", out.text)
        assertTrue(out.spanStyles.isEmpty())
    }

    @Test
    fun `испорченная разметка - деградация до текста без оформления`() {
        val out = renderMessageContent("текст на месте", "{это не json")
        assertEquals("текст на месте", out.text)
        assertTrue(out.spanStyles.isEmpty())
    }
}
