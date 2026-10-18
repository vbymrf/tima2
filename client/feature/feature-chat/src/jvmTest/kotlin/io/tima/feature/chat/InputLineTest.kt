package io.tima.feature.chat

import io.tima.testui.capture
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Строка ввода (Решения §9): пустая — Эмоджи, Галерея, Скрепка; набран символ — остаётся Эмоджи.
 * Только эмоджи — крупно без пузыря (Решения §2).
 */
class InputLineTest {

    @Test
    fun набранное_убирает_галерею_и_скрепку() {
        val empty = capture("строка-пустая", 360, 120, dark = false) {
            InputLine(typed = "", onSet = {}, onSend = {}, onEditor = {}, onAttach = {})
        }
        val typed = capture("строка-набрано", 360, 120, dark = false) {
            InputLine(typed = "привет", onSet = {}, onSend = {}, onEditor = {}, onAttach = {})
        }
        assertTrue(empty.difference(typed) > 0.0)
    }

    /** Заказчик 2026-10-18: эмоджи из области встаёт туда, где курсор, и курсор уходит за него. */
    @Test
    fun эмоджи_встаёт_у_курсора_и_курсор_за_ним() {
        val middle = androidx.compose.ui.text.input.TextFieldValue("ая", androidx.compose.ui.text.TextRange(1)).inserted("🙂")
        kotlin.test.assertEquals("а🙂я", middle.text)
        kotlin.test.assertEquals(androidx.compose.ui.text.TextRange(1 + "🙂".length), middle.selection)
        val end = androidx.compose.ui.text.input.TextFieldValue("ок", androidx.compose.ui.text.TextRange(2)).inserted("👍")
        kotlin.test.assertEquals(androidx.compose.ui.text.TextRange(end.text.length), end.selection, "в конце — курсор за эмоджи")
        val chosen = androidx.compose.ui.text.input.TextFieldValue("да нет", androidx.compose.ui.text.TextRange(3, 6)).inserted("❤️")
        kotlin.test.assertEquals("да ❤️", chosen.text, "выделенное заменяется")
    }

    @Test
    fun только_эмоджи() {
        assertTrue(isEmojiOnly("😀"))
        assertTrue(isEmojiOnly(" 👍👍 "))
        assertTrue(isEmojiOnly("❤️"))
        assertFalse(isEmojiOnly("ок 👍"))
        assertFalse(isEmojiOnly("123"))
        assertFalse(isEmojiOnly(""))
        assertFalse(isEmojiOnly("😀😀😀😀😀😀😀"), "длинная строка эмоджи — уже текст, пузырём")
    }
}
