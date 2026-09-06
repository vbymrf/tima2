package io.tima.core.diag

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Журнал приложения (ПЛАН-ОТЛАДКИ.md Б1, ПЛАН-ПАМЯТИ.md).
 *
 * Проверяется не «записалось ли», а границы, чистка и уборка: журнал, который растёт без
 * предела, однажды съест память телефона, а журнал, пропускающий ключи, отменяет
 * обещание, ради которого мы вообще шифруем переписку.
 */
class DiaryTest {

    private var now = 1_757_000_000_000L // 2025-09-04, чтобы имена дней были настоящими

    /** Дни в памяти — ровно то, что делает платформа с каталогом файлов. */
    private class Files {
        val kept = LinkedHashMap<String, StringBuilder>()

        val port = DiaryFiles(
            days = { kept.keys.toList() },
            append = { day, text -> kept.getOrPut(day) { StringBuilder() }.append(text) },
            read = { day -> kept[day]?.toString() },
            remove = { day -> kept.remove(day) },
            size = { day -> kept[day]?.length?.toLong() ?: 0 },
        )
    }

    private fun diary(
        files: DiaryFiles = DiaryFiles.Forgetful,
        policy: DiaryPolicy = DiaryPolicy(),
        maxNotes: Int = Diary.MAX_NOTES,
    ) = Diary(now = { now }, maxNotes = maxNotes, files = files, policy = policy)

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
    fun разговорчивый_цикл_не_вытесняет_всё() {
        // Предел в памяти нужен ровно для этого случая: ошибка в цикле пишет тысячу строк
        // за секунду, и по времени они все свежие.
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
        val files = Files()
        val diary = diary(files.port)
        diary.trouble(LogCode.NET_ERROR, "отказ, заголовок Authorization: Bearer $token")

        val line = diary.dump()
        assertFalse(line.contains(token), "токен уехал бы в отчёт целиком")
        assertContains(line, "<вырезано")
        assertContains(line, "отказ, заголовок", message = "человеческая часть обязана остаться")
    }

    @Test
    fun короткое_не_режется() {
        // Идентификаторы и имена методов в журнале нужны: без них он бесполезен.
        val files = Files()
        val diary = diary(files.port)
        diary.note(LogCode.AUTH_OK, "user_id=abc12345 шаг=verify")

        assertContains(diary.dump(), "user_id=abc12345")
        assertContains(diary.dump(), "verify")
    }

    // ── Дни на диске (решение заказчика 2026-09-06) ───────────────────────────

    @Test
    fun журнал_переживает_перезапуск() {
        // Главное здесь. Жалуются после перезапуска, и журнал, начинающийся с этого
        // запуска, рассказывает про всё, кроме поломки.
        val files = Files()
        val first = diary(files.port)
        first.note(LogCode.NET_ERROR, "вчерашний отказ")
        first.flush()

        now += 60_000
        val second = diary(files.port)
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
        val files = Files()
        val diary = diary(files.port)
        diary.note(LogCode.NET_CALL, "обычный вызов")
        assertTrue(files.kept.isEmpty(), "ход дела на диск не пишется — это износ памяти телефона")

        diary.trouble(LogCode.QUEUE_STUCK, "очередь не двигается")
        assertContains(files.kept.values.joinToString(), "очередь не двигается")
    }

    @Test
    fun пишем_в_день_сброса() {
        // Решение заказчика: имя файла — день сброса, а не день записи. Раскладывать
        // записи по дням значило бы усложнять запись ради порядка, которого не читают.
        val files = Files()
        val diary = diary(files.port)
        diary.note(LogCode.SCREEN_OPEN, "до полуночи")
        now += Diary.DAY
        diary.flush()

        assertEquals(1, files.kept.size, "всё легло в один файл — тот, в чей день сбросили")
        assertContains(files.kept.keys.first(), "-")
    }

    @Test
    fun старые_дни_удаляются_по_сроку() {
        val files = Files()
        val diary = diary(files.port, DiaryPolicy(days = 7))
        diary.trouble(LogCode.NET_ERROR, "древний отказ")

        // Восемь суток спустя тот день уже за сроком, и уборка идёт при запуске.
        now += 8 * Diary.DAY
        val fresh = diary(files.port, DiaryPolicy(days = 7))
        fresh.note(LogCode.APP_START, "новый запуск")

        assertFalse(
            files.kept.values.joinToString().contains("древний отказ"),
            "день старше срока обязан уйти целиком",
        )
    }

    @Test
    fun объём_режет_раньше_срока() {
        // Второй предел нужен для одного разговорчивого дня: по сроку он свежий, а места
        // занимает столько, что месяц становится неважен.
        val files = Files()
        files.kept["2025-09-01"] = StringBuilder("x".repeat(600))
        files.kept["2025-09-02"] = StringBuilder("y".repeat(600))
        files.kept["2025-09-03"] = StringBuilder("z".repeat(600))

        diary(files.port, DiaryPolicy(days = 365, bytes = 1000))

        assertFalse(files.kept.containsKey("2025-09-01"), "убирают с самого старого")
        assertTrue(files.kept.containsKey("2025-09-03"), "свежий день обязан остаться")
    }

    @Test
    fun последний_день_не_удаляется_никогда() {
        // Иначе на устройстве с одним огромным днём журнал исчезал бы целиком — и отчёт
        // о проблеме приходил бы пустым именно тогда, когда что-то не так.
        val files = Files()
        files.kept["2025-09-03"] = StringBuilder("z".repeat(5000))

        diary(files.port, DiaryPolicy(days = 365, bytes = 100))

        assertTrue(files.kept.containsKey("2025-09-03"))
    }

    @Test
    fun смена_срока_убирает_сразу() {
        // Человек поставил неделю вместо месяца и обязан увидеть освободившееся место —
        // иначе решит, что настройка не работает.
        val files = Files()
        val diary = diary(files.port, DiaryPolicy(days = 365))
        diary.trouble(LogCode.NET_ERROR, "старое")
        now += 10 * Diary.DAY
        diary.note(LogCode.APP_START, "новое")
        diary.flush()
        assertEquals(2, files.kept.size)

        diary.policy = DiaryPolicy(days = 7)

        assertEquals(1, files.kept.size, "срок обязан подействовать сразу")
    }

    @Test
    fun в_отчёт_идут_сутки() {
        // Журнал на диске месячный, а отчёт обязан оставаться отправляемым.
        val files = Files()
        val diary = diary(files.port)
        diary.trouble(LogCode.NET_ERROR, "позавчерашнее")
        diary.flush()
        now += 2 * Diary.DAY
        diary.note(LogCode.APP_START, "сегодняшнее")

        val text = diary.dump()
        assertFalse(text.contains("позавчерашнее"), "в отчёт уходят сутки, а не весь журнал")
        assertContains(text, "сегодняшнее")
    }

    @Test
    fun испорченный_день_не_ломает_журнал() {
        // Файл переживает смену версии и обрыв записи. Строка без разбираемого времени —
        // это либо повреждение, либо чужой формат; доверять ей нечего.
        val files = Files()
        files.kept[dayOf(now)] = StringBuilder("мусор без времени\n\nещё мусор\n")
        val diary = diary(files.port)
        diary.note(LogCode.APP_START, "запустились")

        val text = diary.dump()
        assertFalse(text.contains("мусор"), "непонятные строки в отчёт не идут")
        assertContains(text, "запустились")
    }

    @Test
    fun очистка_убирает_и_память_и_диск() {
        val files = Files()
        val diary = diary(files.port)
        diary.trouble(LogCode.NET_ERROR, "было")

        diary.clear()

        assertEquals(0, diary.size())
        assertTrue(files.kept.isEmpty())
        assertEquals(0, diary.occupied())
    }

    @Test
    fun занятое_место_считается() {
        val files = Files()
        files.kept["2025-09-01"] = StringBuilder("x".repeat(100))
        files.kept["2025-09-02"] = StringBuilder("y".repeat(50))

        assertEquals(150, diary(files.port).occupied())
    }
}

/** Настройка хранения: строка туда и обратно. */
class DiaryPolicyTest {

    @Test
    fun по_умолчанию_месяц() {
        // Решение заказчика 2026-09-06. Месяц — ровно 30 дней, а не календарный.
        assertEquals(30, DiaryPolicy().days)
        assertEquals(20 * DiaryPolicy.MB, DiaryPolicy().bytes)
    }

    @Test
    fun строка_читается_обратно() {
        val policy = DiaryPolicy(days = 14, bytes = 50 * DiaryPolicy.MB)
        assertEquals(policy, DiaryPolicy.read(policy.write()))
    }

    @Test
    fun непонятная_строка_даёт_умолчание() {
        // Файл настройки переживает смену версии и обрыв записи: испорченный не должен
        // означать «не хранить вовсе».
        assertEquals(DiaryPolicy(), DiaryPolicy.read("мусор"))
        assertEquals(DiaryPolicy(), DiaryPolicy.read(null))
        assertEquals(DiaryPolicy(), DiaryPolicy.read("0 0"))
    }
}
