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

    /** Хранилище на одной строке: ровно то, что делает платформа с файлом. */
    private fun store(held: Array<String?>) =
        DiaryStore(load = { held[0] }, save = { held[0] = it })

    @Test
    fun журнал_переживает_перезапуск() {
        // Главное здесь. Жалуются после перезапуска, и журнал, начинающийся с этого
        // запуска, рассказывает про всё, кроме поломки.
        val held = arrayOf<String?>(null)
        val first = Diary(now = { now }, store = store(held))
        first.note(LogCode.NET_ERROR, "вчерашний отказ")
        first.flush()

        now += 60_000
        val second = Diary(now = { now }, store = store(held))
        second.note(LogCode.APP_START, "запустились заново")

        val text = second.dump()
        assertContains(text, "вчерашний отказ", message = "запись прошлого запуска обязана приехать с диска")
        assertContains(text, "запустились заново")
        assertTrue(
            text.indexOf("вчерашний отказ") < text.indexOf("запустились заново"),
            "старое обязано идти первым: журнал читают сверху вниз",
        )
    }

    @Test
    fun беда_сбрасывается_сразу() {
        // После беды процесс вполне может не дожить до сброса пачкой: падение и убийство
        // системой случаются именно в такие моменты.
        val held = arrayOf<String?>(null)
        val diary = Diary(now = { now }, store = store(held))
        diary.note(LogCode.NET_CALL, "обычный вызов")
        assertEquals(null, held[0], "ход дела на диск не пишется — это износ памяти телефона")

        diary.trouble(LogCode.QUEUE_STUCK, "очередь не двигается")
        assertContains(held[0].orEmpty(), "очередь не двигается")
    }

    @Test
    fun с_диска_не_берётся_старше_срока() {
        // Трое суток — решение заказчика. Файл, переживший отпуск, не должен превращать
        // отчёт в архив.
        val held = arrayOf<String?>(null)
        val old = Diary(now = { now }, store = store(held))
        old.trouble(LogCode.NET_ERROR, "древний отказ")

        now += 4 * Diary.DAY
        val fresh = Diary(now = { now }, store = store(held))
        fresh.note(LogCode.APP_START, "новый запуск")

        assertFalse(fresh.dump().contains("древний отказ"), "запись старше срока обязана уйти")
    }

    @Test
    fun испорченный_файл_не_ломает_журнал() {
        // Файл переживает смену версии и обрыв записи. Строка без разбираемого времени —
        // это либо повреждение, либо чужой формат; доверять ей нечего.
        val held = arrayOf<String?>("мусор без времени\n\nещё мусор")
        val diary = Diary(now = { now }, store = store(held))
        diary.note(LogCode.APP_START, "запустились")

        val text = diary.dump()
        assertFalse(text.contains("мусор"), "непонятные строки в отчёт не идут")
        assertContains(text, "запустились")
    }
}
