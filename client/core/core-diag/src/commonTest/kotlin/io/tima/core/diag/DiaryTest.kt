package io.tima.core.diag

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Журнал приложения (ПЛАН-ОТЛАДКИ.md, Б1).
 *
 * Проверяется не «записалось ли», а обе границы и чистка: журнал, который растёт без
 * предела, однажды съест память телефона, а журнал, пропускающий ключи, отменяет
 * обещание, ради которого мы вообще шифруем переписку.
 */
class DiaryTest {

    private var now = 1_000_000L
    private fun diary(keepMillis: Long = Diary.DAY, maxNotes: Int = Diary.MAX_NOTES) =
        Diary(now = { now }, keepMillis = keepMillis, maxNotes = maxNotes)

    @Test
    fun пишет_и_отдаёт_по_порядку() {
        val diary = diary()
        diary.note(LogCode.NET_CALL, "пошёл запрос")
        now += 5
        diary.trouble(LogCode.NET_ERROR, "отказ", "код" to 500)

        val notes = diary.tail()
        assertEquals(2, notes.size)
        assertEquals("пошёл запрос", notes[0].text)
        assertEquals(Level.Trouble, notes[1].level, "беда обязана отличаться от хода дела")
    }

    @Test
    fun старое_вытесняется_по_времени() {
        val diary = diary(keepMillis = 100)
        diary.note(LogCode.SCREEN_OPEN, "вчерашнее")
        now += 500
        diary.note(LogCode.SCREEN_OPEN, "сегодняшнее")

        val notes = diary.tail()
        assertEquals(1, notes.size, "запись старше границы обязана уйти")
        assertEquals("сегодняшнее", notes[0].text)
    }

    @Test
    fun разговорчивый_цикл_не_вытесняет_всё() {
        // Предел по числу записей нужен ровно для этого случая: ошибка в цикле пишет
        // тысячу строк за секунду, и по времени они все свежие.
        val diary = diary(maxNotes = 10)
        repeat(100) { diary.note(LogCode.NET_CALL, "попытка $it") }

        val notes = diary.tail()
        assertEquals(10, notes.size)
        assertEquals("попытка 99", notes.last().text, "последнее обязано остаться")
    }

    @Test
    fun длинное_обрезается() {
        val diary = diary()
        diary.note(LogCode.SCREEN_OPEN, "я".repeat(1000))

        assertTrue(diary.tail()[0].text.length <= Diary.MAX_LENGTH)
    }

    @Test
    fun похожее_на_ключ_вырезается() {
        // Никто не собирается писать ключи в журнал. Но однажды в текст ошибки попадёт
        // заголовок с токеном — и уедет молча, если не вырезать.
        val token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9aaaaaaaaaaaaaaaa"
        val diary = diary()
        diary.trouble(LogCode.NET_ERROR, "отказ, заголовок Authorization: Bearer $token")

        val line = diary.dump()
        assertFalse(line.contains(token), "токен уехал бы в отчёт целиком")
        assertContains(line, "<вырезано")
        assertContains(line, "отказ, заголовок", message = "человеческая часть обязана остаться")
    }

    @Test
    fun короткое_не_режется() {
        // Идентификаторы и имена методов в журнале нужны: без них он бесполезен.
        val diary = diary()
        diary.note(LogCode.AUTH_OK, "user_id=abc12345 шаг=verify")

        assertContains(diary.dump(), "user_id=abc12345")
        assertContains(diary.dump(), "verify")
    }

    @Test
    fun выгрузка_и_показ_совпадают() {
        // Показывать человеку одно, а отправлять другое нельзя — это обман в чистом виде.
        val diary = diary()
        diary.note(LogCode.SCREEN_OPEN, "открыли настройки")

        assertEquals(diary.dump(), diary.tail().joinToString("\n") { it.line() })
        assertEquals(1, diary.size())
    }
}
