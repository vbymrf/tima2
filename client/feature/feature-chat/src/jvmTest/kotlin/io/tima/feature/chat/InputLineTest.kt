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
