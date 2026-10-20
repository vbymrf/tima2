package io.tima.core.media

import kotlin.test.Test
import kotlin.test.assertEquals

/** Имя файла для «Открыть» и «Поделиться» (меню файла, заказчик 2026-10-20). */
class SafeFileNameTest {

    @Test
    fun кириллица_и_точка_сохраняются() = assertEquals("отчёт 2026.pdf", safeFileName("отчёт 2026.pdf"))

    @Test
    fun путь_и_запрещённые_знаки_заменяются() = assertEquals("_.._etc_pass_word_", safeFileName("/../etc/pass:word?"))

    @Test
    fun пустое_имя_становится_file() = assertEquals("file", safeFileName(" .. "))
}
