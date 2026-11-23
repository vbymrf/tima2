package io.tima.feature.group

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** «Найти» страницы сущности (2026-11-23): что находится и каким отрывком показывается. */
class EntitySearchTest {

    @Test
    fun находит_без_учёта_регистра() {
        assertEquals("Встреча в Пятницу", foundSnippet("Встреча в Пятницу", "пятниц"))
    }

    @Test
    fun короткий_запрос_и_промах_не_находят() {
        assertNull(foundSnippet("Встреча в пятницу", "п"))
        assertNull(foundSnippet("Встреча в пятницу", "  "))
        assertNull(foundSnippet("Встреча в пятницу", "суббота"))
    }

    @Test
    fun длинное_показывается_с_места_совпадения() {
        val text = "а".repeat(100) + " ключ " + "б".repeat(20)
        val snippet = foundSnippet(text, "ключ")!!
        assert(snippet.startsWith("…")) { snippet }
        assert(snippet.indexOf("ключ") in 1..40) { snippet }
    }

    @Test
    fun перенос_строки_не_рвёт_отрывок() {
        assertEquals("первая вторая", foundSnippet("первая\nвторая", "втор"))
    }
}
