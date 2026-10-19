package io.tima.feature.chat

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.tima.domain.chat.ComposedDoc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Сочетания клавиш редактора на ПК (Решения §10.2). */
class EditorKeysTest {

    private class Press(val key: Key, val ctrl: Boolean, val shift: Boolean)

    private fun key(code: Key, ctrl: Boolean = true, shift: Boolean = false) = Press(code, ctrl, shift)

    @Test
    fun оформление_заголовки_отмена_отправка_назад() {
        val m = EditorModel(ComposedDoc.of("сбор в 9"), MarkdownTyped)
        m.changed(0, TextFieldValue("сбор в 9", TextRange(0, 4)))
        var sent = 0
        var back = 0
        fun press(e: Press, canSend: Boolean = true) = editorCommand(e.key, e.ctrl, e.shift, m, canSend, onSend = { sent++ }, onBack = { back++ })

        assertTrue(press(key(Key.B)))
        assertEquals("**сбор** в 9", m.focusedText)
        assertTrue(press(key(Key.Two)))
        assertEquals("## **сбор** в 9", m.focusedText)
        assertTrue(press(key(Key.Z)))
        assertEquals("**сбор** в 9", m.focusedText)
        assertTrue(press(key(Key.Z, shift = true)))
        assertEquals("## **сбор** в 9", m.focusedText)
        assertTrue(press(key(Key.Enter)))
        assertEquals(1, sent)
        press(key(Key.Enter), canSend = false)
        assertEquals(1, sent, "нельзя отправить — Ctrl+Enter ничего не шлёт")
        assertTrue(press(key(Key.Escape, ctrl = false)))
        assertEquals(1, back)
        assertFalse(press(key(Key.B, ctrl = false)), "буква без Ctrl — набор, а не команда")
    }
}
