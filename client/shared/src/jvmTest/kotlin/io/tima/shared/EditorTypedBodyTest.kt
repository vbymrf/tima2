package io.tima.shared

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.tima.core.encryption.ComposedBodiesOverKodium
import io.tima.core.encryption.TypedOverQuickMarkdown
import io.tima.crypto.BlockType
import io.tima.crypto.EntityType
import io.tima.domain.chat.ComposedDoc
import io.tima.domain.chat.InlineKind
import io.tima.feature.chat.EditorModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Редактор на настоящем разборе (ADR-0048): поле без знаков, в документе — набранное со знаками,
 * в теле — узлы и разметка. Знак, набранный текстом, оформлением не становится и в тело уходит текстом.
 */
class EditorTypedBodyTest {

    private fun model() = EditorModel(ComposedDoc.of(""), TypedOverQuickMarkdown).also {
        it.changed(0, TextFieldValue("", TextRange(0)))
    }

    private fun EditorModel.type(s: String) {
        for (ch in s) {
            val t = field.text
            val c = field.selection.start
            changed(0, TextFieldValue(t.substring(0, c) + ch + t.substring(c), TextRange(c + 1)))
        }
    }

    @Test
    fun набранное_знаками_уходит_оформленным() {
        val m = model()
        m.type("# Сбор\nв **9:00** у входа")
        assertEquals("Сбор\nв 9:00 у входа", m.field.text, "в поле знаков нет")
        val content = ComposedBodiesOverKodium.content(m.doc)
        assertEquals(listOf("Сбор", "в 9:00 у входа"), content.nodes)
        assertEquals(BlockType.HEADING, content.markup!!.blocks[0].type)
        assertEquals(EntityType.BOLD, content.markup!!.entities.single().type)
    }

    @Test
    fun знаки_выключены_уходят_текстом() {
        val m = model()
        m.toggleMarks()
        m.type("# 2*3 = **шесть**")
        assertEquals("# 2*3 = **шесть**", m.field.text)
        val content = ComposedBodiesOverKodium.content(m.doc)
        assertEquals("# 2*3 = **шесть**", content.plainText())
        assertFalse(content.hasMarkup, "ни заголовка, ни жирного")
    }

    @Test
    fun ж_и_к_на_одном_слове_и_адрес_с_плюсом() {
        val m = model()
        m.type("слово https://ya.ru/?q=a+b")
        m.changed(0, TextFieldValue(m.field.text, TextRange(0, 5)))
        m.inline(InlineKind.BOLD)
        m.inline(InlineKind.ITALIC)
        val content = ComposedBodiesOverKodium.content(m.doc)
        assertEquals("слово https://ya.ru/?q=a+b", content.plainText(), "адрес цел, косых нет")
        assertEquals(setOf(EntityType.BOLD, EntityType.ITALIC), content.markup!!.entities.map { it.type }.toSet())
    }

    @Test
    fun черновик_туда_и_обратно() {
        val m = model()
        m.type("- пункт **жирный**\n--> цитата")
        val saved = ComposedBodiesOverKodium.save(m.doc)
        val back = EditorModel(ComposedBodiesOverKodium.load(saved)!!, TypedOverQuickMarkdown)
        assertEquals(m.focusedTyped, back.typedAt(io.tima.domain.chat.Spot(0)))
        assertTrue(back.focusedTyped!!.spans.isNotEmpty())
    }
}
