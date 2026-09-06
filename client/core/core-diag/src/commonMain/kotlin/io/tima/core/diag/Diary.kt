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
 * **Границы две, и обе нужны** (решение заказчика 2026-09-06). По времени — чтобы отчёт
 * рассказывал про сегодняшнюю поломку, а не про прошлую неделю. По числу записей — чтобы
 * одна разговорчивая ошибка в цикле не вытеснила всё, что было до неё, и чтобы отчёт не
 * рос неограниченно.
 *
 * **Часы приходят снаружи.** Журнал, который сам зовёт «сейчас», нельзя проверить на
 * вытеснение по времени, не прождав сутки.
 */
class Diary(
    private val now: () -> Long,
    /** Сколько держим записи. Сутки: отчёт про сегодня, а не про неделю. */
    private val keepMillis: Long = DAY,
    /** Предел числа записей — защита от разговорчивого цикла. */
    private val maxNotes: Int = MAX_NOTES,
    /** Предел длины одной записи: длинное здесь всегда либо дамп, либо секрет. */
    private val maxLength: Int = MAX_LENGTH,
) {
    private val notes = ArrayDeque<Note>()

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
        }
    }

    /** То же, но для беды: отдельный уровень, чтобы её было видно в отчёте. */
    fun trouble(code: String, text: String = "", vararg details: Pair<String, Any?>) =
        note(code, text, *details, level = Level.Trouble)

    /** Что уйдёт в отчёт: всё, что уложилось в обе границы, от старого к новому. */
    fun tail(): List<Note> {
        val edge = now() - keepMillis
        return synchronizedNotes {
            while (notes.isNotEmpty() && notes.first().atMillis < edge) notes.removeFirst()
            notes.toList()
        }
    }

    /**
     * Журнал текстом — ровно то, что человек увидит по кнопке «Смотреть» и что уйдёт на
     * сервер. Одно и то же: показывать одно, а отправлять другое нельзя.
     */
    fun dump(): String = tail().joinToString("\n") { it.line() }

    fun clear() = synchronizedNotes { notes.clear() }

    /** Сколько записей сейчас — для экрана «Что приложится». */
    fun size(): Int = tail().size

    private inline fun <T> synchronizedNotes(block: () -> T): T = block()

    companion object {
        const val DAY: Long = 24L * 60 * 60 * 1000

        /** Хранение — трое суток (решение заказчика 2026-09-06); в отчёт уходят сутки. */
        const val KEEP_DAYS: Long = 3 * DAY
        const val MAX_NOTES: Int = 4000
        const val MAX_LENGTH: Int = 300

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
