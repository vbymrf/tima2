package io.tima.core.diag

import kotlinx.datetime.Instant

/**
 * Журнал приложения — то, что человек приложит к отчёту о проблеме
 * (ПЛАН-ОТЛАДКИ.md, срез Б1).
 *
 * **Свой журнал, а не системный, и на это две причины.** На Android приложение читает
 * только собственный процесс, и то с оговорками по версиям; у пакета ПК, собранного
 * jpackage, консоли нет вовсе — это уже стоило нам полдня, когда искали падение по
 * `java.sql`. Свой буфер работает одинаково на обеих платформах и переживает то, что
 * вывод некуда печатать.
 *
 * **В памяти живёт только несброшенное.** Прошлые дни лежат на диске файлами и читаются
 * лишь тогда, когда составляется отчёт. До 2026-09-06 журнал целиком читался в память при
 * каждом запуске — на трёх сутках это было незаметно, на месяце стало бы дорого.
 *
 * **Часы приходят снаружи.** Журнал, который сам зовёт «сейчас», нельзя проверить на
 * вытеснение по времени, не прождав сутки.
 */
class Diary(
    private val now: () -> Long,
    /** Сколько журнала прикладывается к отчёту. Сутки — и это держится твёрдо. */
    private val reportMillis: Long = DAY,
    /** Предел записей В ПАМЯТИ — защита от разговорчивого цикла между сбросами. */
    private val maxNotes: Int = MAX_NOTES,
    /** Предел длины одной записи: длинное здесь всегда либо дамп, либо секрет. */
    private val maxLength: Int = MAX_LENGTH,
    /** Где лежат дни. По умолчанию нигде — журнал живёт до перезапуска. */
    private val files: DiaryFiles = DiaryFiles.Forgetful,
    policy: DiaryPolicy = DiaryPolicy(),
) {
    /**
     * Сколько держим — меняется из настроек на живом журнале.
     *
     * `var`, а не параметр конструктора: человек правит срок в «Памяти и трафике», и
     * пересоздавать журнал ради этого значило бы потерять несброшенные записи.
     */
    var policy: DiaryPolicy = policy
        set(value) {
            field = value
            sweep()
        }

    /** Что видно на экране «Что приложится» и что уйдёт в отчёт из текущего запуска. */
    private val notes = ArrayDeque<Note>()

    /** Что ещё не дописано на диск. Пишется в конец файла дня и очищается. */
    private val pending = ArrayList<Note>()

    /** День, в который сбрасывали в прошлый раз, — по нему видно, что наступил новый. */
    private var lastDay: String = dayOf(now())

    init {
        // Уборка при запуске, а не по расписанию: другого надёжного повода нет — фоновой
        // работы у приложения пока не бывает вовсе.
        sweep()
    }

    /**
     * Записать.
     *
     * @param code код из [LogCode] — машинная часть строки: по нему ищут, считают и
     *   выбирают отчёты. Первая часть кода и есть область.
     * @param text человеческая часть: что это значит. Меняется свободно — на поиск она
     *   не влияет.
     * @param details пары «имя=значение» для того, что считается и сравнивается: путь,
     *   код ответа, миллисекунды, длина очереди.
     */
    fun note(
        code: String,
        text: String = "",
        vararg details: Pair<String, Any?>,
        level: Level = Level.Info,
    ) {
        val note = Note(
            atMillis = now(),
            level = level,
            code = code,
            text = scrub(text).take(maxLength),
            details = details.mapNotNull { (name, value) ->
                value?.let { name to scrub(it.toString()).take(DETAIL_LENGTH) }
            },
        )
        synchronizedNotes {
            notes.addLast(note)
            while (notes.size > maxNotes) notes.removeFirst()
            pending.add(note)
        }
        // Беда сбрасывается сразу: после неё процесс вполне может не дожить до следующей
        // пачки — падение и убийство системой случаются именно в такие моменты.
        if (level == Level.Trouble || pending.size >= FLUSH_EVERY) flush()
    }

    /** То же, но для беды: отдельный уровень, чтобы её было видно в отчёте. */
    fun trouble(code: String, text: String = "", vararg details: Pair<String, Any?>) =
        note(code, text, *details, level = Level.Trouble)

    /** Записи текущего запуска, от старого к новому. */
    fun tail(): List<Note> = synchronizedNotes { notes.toList() }

    /**
     * Журнал текстом — ровно то, что человек увидит по кнопке «Смотреть» и что уйдёт на
     * сервер. Одно и то же: показывать одно, а отправлять другое нельзя.
     *
     * Читает с диска столько дней, сколько нужно для [reportMillis], и отбрасывает в них
     * всё, что старше. Прошлых дней читается ровно два — сегодня и вчера: жалоба «вчера
     * не приходили сообщения» иначе пришла бы с журналом, начинающимся сегодня.
     */
    fun dump(depthMillis: Long = reportMillis): String {
        flush()
        val since = now() - depthMillis
        // Дней столько, сколько накрывает срез: при «сегодня» это один-два, при «раньше»
        // — весь месяц. Больше журнал и не хранится.
        val wanted = generateSequence(dayOf(since)) { day ->
            if (day >= dayOf(now())) null else nextDay(day)
        }.take(MAX_DAYS_READ).toList()
        val fromDisk = wanted
            .mapNotNull { day -> runCatching { files.read(day) }.getOrNull() }
            .joinToString("\n")
        val onDisk = linesSince(fromDisk, since, MAX_REPORT_LINES)
        val inMemory = synchronizedNotes { notes.filter { it.atMillis >= since }.map { it.line() } }
        // Диск и память складываются, а не заменяют друг друга. Память — это текущий
        // запуск, диск — прошлые дни плюс уже сброшенная часть текущего; их пересечение
        // убирается по совпадению строки целиком (время там с миллисекундами).
        //
        // Без сложения журнал оказался бы пустым на платформе, которая хранить ещё не
        // умеет, — и отчёт о проблеме приходил бы пустым именно оттуда, откуда он нужнее
        // всего. Сортировать при этом нечего: обе части идут по времени, а хвост памяти
        // всегда новее всего, что лежит на диске.
        val kept = LinkedHashSet<String>(onDisk.size + inMemory.size)
        kept.addAll(onDisk)
        kept.addAll(inMemory)
        return withinLimit(kept.toList())
    }

    /**
     * Уложить в предел, оставив **свежее**.
     *
     * Набираем с конца, пока влезает. Если что-то отброшено, первой строкой идёт честная
     * пометка: читающий обязан знать, что журнал начинается не с начала поломки.
     *
     * **Режем здесь, а не отдаём это приёму.** Сервер укорачивает журнал до полумегабайта
     * и до 2026-09-06 отбрасывал последние знаки — то есть при большом журнале сохранял
     * самое старое, а самое свежее, ради которого отчёт и шлют, терял.
     */
    private fun withinLimit(lines: List<String>): String {
        var total = 0
        var from = lines.size
        while (from > 0) {
            val length = lines[from - 1].length + 1
            if (total + length > MAX_REPORT_CHARS) break
            total += length
            from--
        }
        if (from == 0) return lines.joinToString("\n")
        return (listOf("… журнал обрезан: не поместилось $from строк, показано свежее") +
            lines.subList(from, lines.size)).joinToString("\n")
    }

    /**
     * Сбросить на диск — дописать несброшенное в файл сегодняшнего дня.
     *
     * **Пачками, а не на каждую запись** (решение заказчика 2026-09-06): запись на диск на
     * каждый сетевой вызов — это лишний расход батареи и износ памяти телефона. Поводов
     * четыре: беда, каждые [FLUSH_EVERY] записей, уход в фон и составление отчёта.
     */
    fun flush() {
        val today = dayOf(now())
        val text = synchronizedNotes {
            if (pending.isEmpty()) return@synchronizedNotes ""
            val block = pending.joinToString("") { it.line() + "\n" }
            pending.clear()
            block
        }
        if (text.isNotEmpty()) {
            // Ошибка записи гасится: журнал — не то, ради чего стоит ронять приложение.
            // Потерянный сброс означает лишь, что часть строк не переживёт перезапуск.
            runCatching { files.append(today, text) }
        }
        // Новый день — повод убраться: старший файл мог выйти за срок именно сейчас.
        if (today != lastDay) {
            lastDay = today
            sweep()
        }
    }

    /**
     * Убрать лишнее: сначала по сроку, потом по объёму.
     *
     * Порядок именно такой. Срок — это обещание человеку («держим месяц»), объём —
     * страховка от одного разговорчивого дня. Сначала исполняем обещание, потом смотрим,
     * не осталось ли всё равно слишком много.
     */
    fun sweep() {
        runCatching {
            val edge = dayOf(now() - policy.days.toLong() * DAY)
            val days = files.days().sorted()
            val kept = ArrayList<String>()
            for (day in days) {
                when {
                    // В каталоге журнала лежат только дни. Всё прочее — обрывок, чужое
                    // или наследство прошлой схемы; это правило, а не разовая миграция,
                    // и потому оно не превращается в код, который потом некому убрать.
                    !looksLikeDay(day) -> files.remove(day)
                    // Сравнение строк, а не дат: `2026-09-06` в лексикографическом порядке
                    // совпадает с хронологическим — на то и выбран этот вид записи.
                    day < edge -> files.remove(day)
                    else -> kept.add(day)
                }
            }
            var total = kept.sumOf { files.size(it) }
            // С самого старого: свежее нужнее. Последний день не трогаем никогда — иначе
            // на устройстве с одним огромным днём журнал исчезал бы целиком.
            var index = 0
            while (total > policy.bytes && index < kept.size - 1) {
                val day = kept[index]
                total -= files.size(day)
                files.remove(day)
                index++
            }
        }
    }

    /** Сколько всего занимают дни на диске — для экрана «Память и трафик». */
    fun occupied(): Long = runCatching { files.days().sumOf { files.size(it) } }.getOrDefault(0)

    /** Стереть журнал целиком: и память, и диск. Кнопка «Очистить сейчас». */
    fun clear() {
        synchronizedNotes {
            notes.clear()
            pending.clear()
        }
        runCatching { files.days().forEach { files.remove(it) } }
    }

    /** Сколько записей сейчас — для экрана «Что приложится». */
    fun size(): Int = tail().size

    private inline fun <T> synchronizedNotes(block: () -> T): T = block()

    companion object {
        const val DAY: Long = 24L * 60 * 60 * 1000

        /**
         * Через сколько записей сбрасывать на диск.
         *
         * Полсотни: на глаз это несколько минут обычной работы. Реже — теряется больше при
         * внезапном убийстве; чаще — запись на диск начинает стоить заметно.
         */
        const val FLUSH_EVERY: Int = 50
        const val MAX_NOTES: Int = 4000
        const val MAX_LENGTH: Int = 300

        /**
         * Предел строк в отчёте.
         *
         * Держится отдельно от предела в памяти: журнал на диске теперь месячный, а отчёт
         * обязан оставаться отправляемым. Xiaomi 2026-09-06 прислал 78 697 знаков за одни
         * сутки — этого достаточно для разбора и уже много для одного письма.
         */
        const val MAX_REPORT_LINES: Int = 40_000

        /**
         * Предел отчёта в знаках.
         *
         * Согласован с приёмом: сервер режет журнал до 512 000 знаков, и упереться в его
         * предел значит отдать обрезку тому, кто не знает, какой конец важнее. Четыреста
         * тысяч — с запасом на снимок состояния, который идёт первым блоком.
         */
        const val MAX_REPORT_CHARS: Int = 400_000

        /** Сколько дней читаем разом: месяц с запасом. Дольше журнал и не хранится. */
        const val MAX_DAYS_READ: Int = 40

        /** Предел значения в хвосте: там числа и пути, длинному там взяться неоткуда. */
        const val DETAIL_LENGTH: Int = 120
    }
}

/** Уровень записи. Два, а не пять: в отчёте важно одно — беда это или ход дела. */
enum class Level { Info, Trouble }

/** Одна запись журнала. */
data class Note(
    val atMillis: Long,
    val level: Level,
    val code: String,
    val text: String,
    val details: List<Pair<String, String>> = emptyList(),
) {
    /**
     * Строка отчёта: время, знак беды, код, текст, хвост из пар.
     *
     * Порядок не случаен. Время — чтобы сопоставлять с рассказом человека; знак — чтобы
     * беды находились взглядом; код — чтобы находились поиском; текст — чтобы читались;
     * хвост — чтобы считались.
     */
    fun line(): String {
        val stamp = Instant.fromEpochMilliseconds(atMillis).toString()
        val mark = if (level == Level.Trouble) "!" else " "
        val tail = if (details.isEmpty()) "" else
            "  " + details.joinToString(" ") { (name, value) -> "$name=$value" }
        val words = if (text.isBlank()) "" else " $text"
        return "$stamp $mark $code$words$tail"
    }
}

/**
 * Вырезать то, чего в журнале быть не должно.
 *
 * **Это не косметика, а исполнение обещания.** Мы обещаем человеку, что наружу не уходит
 * содержимое переписки и ключи; писать в журнал их никто не собирается, но однажды в
 * текст ошибки попадёт заголовок с токеном или байты ключа — и уйдут они молча.
 *
 * Правило простое и грубое: **длинная сплошная последовательность букв, цифр и знаков
 * base64 — это не слово, а материал**. Слова такой длины в наших сообщениях не
 * встречаются, а ключи, токены и подписи выглядят именно так. Заменяется на пометку, по
 * которой видно, что здесь что-то вырезано.
 */
fun scrub(text: String): String {
    val builder = StringBuilder(text.length)
    var run = StringBuilder()

    fun flush() {
        if (run.length >= SECRET_LIKE) builder.append("<вырезано ").append(run.length).append(">")
        else builder.append(run)
        run = StringBuilder()
    }

    for (symbol in text) {
        if (symbol.isLetterOrDigit() && symbol.code < 128 || symbol == '+' || symbol == '/' || symbol == '_' || symbol == '-' || symbol == '=') {
            run.append(symbol)
        } else {
            flush()
            builder.append(symbol)
        }
    }
    flush()
    return builder.toString()
}

/**
 * Порог «похоже на секрет».
 *
 * 24 знака: короче бывают слова и идентификаторы, которые в журнале нужны (`user_id` из
 * восьми знаков, имена методов), длиннее — уже ключ, токен или подпись. Граница грубая
 * намеренно: ошибиться здесь лучше в сторону вырезанного.
 */
private const val SECRET_LIKE = 24
