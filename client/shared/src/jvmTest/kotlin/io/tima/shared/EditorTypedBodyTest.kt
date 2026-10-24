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
    fun знаки_md_в_редакторе_уходят_текстом() {
        // ADR-0053: в редакторе знаки md — буквы; оформление — кнопками.
        val m = model()
        m.type("# Сбор в **9:00**")
        assertEquals("# Сбор в **9:00**", m.field.text)
        val content = ComposedBodiesOverKodium.content(m.doc)
        assertEquals("# Сбор в **9:00**", content.plainText())
        assertFalse(content.hasMarkup, "ни заголовка, ни жирного")
    }

    @Test
    fun знаки_выключены_уходят_текстом() {
        val m = model()
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

    /** ADR-0049: в пункт кладутся подборка и блок кода — в тело уходит пункт-контейнер. */
    @Test
    fun пункт_с_подборкой_и_кодом() {
        val m = model()
        m.untitled = "Без имени"
        fun EditorModel.typeHere(s: String) {
            for (ch in s) {
                val t = field.text
                val c = field.selection.start
                val v = TextFieldValue(t.substring(0, c) + ch + t.substring(c), TextRange(c + 1))
                if (doc.items[spot.item] is io.tima.domain.chat.ComposedItem.ItemList) changedItem(spot.item, spot.cell, spot.widget, v) else changed(spot.item, v)
            }
        }
        // Знаков md в редакторе нет (ADR-0053): список и код — кнопками.
        m.paragraph(io.tima.domain.chat.ParagraphKind.LIST)
        m.typeHere("Заглавие\nПодборка")
        m.paragraph(io.tima.domain.chat.ParagraphKind.LIST2)
        m.focusedItem(0, 1, io.tima.domain.chat.Spot.TEXT)
        m.code()
        m.typeHere("код без форматирования")
        m.select(io.tima.domain.chat.Spot(0, 1))
        m.insert(io.tima.domain.chat.ComposedItem.Widget(io.tima.domain.chat.ComposedWidget.Quoted("group", "g-1", 42)))
        val content = ComposedBodiesOverKodium.content(m.doc)
        assertTrue(io.tima.crypto.MarkupRules.check(content, strict = true).isEmpty(), io.tima.crypto.MarkupRules.check(content, strict = true).toString())
        assertEquals(listOf("Заглавие", "Подборка", "код без форматирования"), content.nodes)
        val nested = content.markup!!.blocks.single().children.single().children.single()
        assertEquals(listOf(BlockType.CODE_BLOCK, BlockType.QUOTED), nested.children.single().children.map { it.type })
    }

    @Test
    fun черновик_туда_и_обратно() {
        val m = model()
        // Оформление в редакторе — кнопками (ADR-0053).
        m.type("пункт жирный")
        m.changed(0, TextFieldValue(m.field.text, TextRange(6, 12)))
        m.inline(InlineKind.BOLD)
        m.paragraph(io.tima.domain.chat.ParagraphKind.HEADING1)
        val saved = ComposedBodiesOverKodium.save(m.doc)
        val back = EditorModel(ComposedBodiesOverKodium.load(saved)!!, TypedOverQuickMarkdown)
        assertEquals(m.focusedTyped, back.typedAt(io.tima.domain.chat.Spot(0)))
        assertTrue(back.focusedTyped!!.spans.isNotEmpty())
    }
}
