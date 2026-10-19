package io.tima.domain.chat

/**
 * Оформленное сообщение — то, что рисует экран (ПЛАН-(РС) РС4).
 *
 * Модель разметки живёт в `messenger-crypto` и выше `core-encryption` не поднимается (правило
 * архитектуры: крипто-фасад один). Поэтому у экрана своя модель: блоки и отрезки оформления,
 * без идентификаторов узлов, стилей проводного формата и прочего, что нужно только для
 * передачи. Переводит в неё `core-encryption`.
 *
 * `null` у строки переписки — оформления нет: обычный текст, как раньше.
 */
data class RichContent(
    val blocks: List<RichBlock>,
    /** Файлы сообщения (`MessageBody.media`): виджет картинки и карточка ссылки ссылаются по номеру. */
    val media: List<RichMedia> = emptyList(),
)

/**
 * Файл внутри сообщения: номер в хранилище и ключ, которым он закрыт (ключ едет в зашифрованном
 * теле, на сервере его нет). Размеры — от отправителя: место под картинку до её загрузки.
 */
class RichMedia(
    val mediaId: String,
    val key: ByteArray,
    val mime: String,
    val width: Int,
    val height: Int,
    /** Превью фото от отправителя — JPEG до 20 КБ внутри тела (Решения §3); пусто — нет. */
    val preview: ByteArray = ByteArray(0),
    /** Имя файла вне пузыря (Решения §6); пусто у фото. */
    val name: String = "",
    val size: Long = 0,
) {
    override fun equals(other: Any?) = other is RichMedia && other.mediaId == mediaId
    override fun hashCode() = mediaId.hashCode()
    override fun toString() = "RichMedia($mediaId, $mime, ${width}x$height)"
}

/**
 * Стиль блока — имена и ступени (ADR-0044 §4), `null` — по умолчанию. Цвета — **имена палитры**:
 * у имени своё значение в светлой и тёмной теме, его знает экран.
 */
data class BlockStyle(
    val background: String? = null,
    val color: String? = null,
    /** `smaller`, `normal`, `larger`. */
    val size: String? = null,
    /** `start`, `center`, `end`. */
    val align: String? = null,
    /** `top`, `center`, `bottom` — у ячейки. */
    val valign: String? = null,
    /** `none`, `small`, `normal`, `large`. */
    val padding: String? = null,
    /** Разделитель после блока: `hidden`, `1`, `2`, `4`. */
    val divider: String? = null,
    val dividerColor: String? = null,
) {
    companion object {
        val NONE = BlockStyle()
    }
}

/** Текст с отрезками оформления. Границы — в символах этого текста. */
data class RichText(val text: String, val spans: List<RichSpan> = emptyList())

data class RichSpan(
    val kind: SpanKind,
    val start: Int,
    val end: Int,
    /** Хештег — имя без `#`; упоминание — ник; ссылка — адрес. */
    val attribute: String = "",
)

enum class SpanKind { BOLD, ITALIC, UNDERLINE, STRIKE, HIGHLIGHT, CODE, LINK, MENTION, HASHTAG }

sealed interface RichBlock {
    data class Paragraph(val text: RichText, val style: BlockStyle = BlockStyle.NONE) : RichBlock

    /** Уровень 1–3. */
    data class Heading(val level: Int, val text: RichText, val style: BlockStyle = BlockStyle.NONE) : RichBlock

    /** Строки цитаты — каждая своей строкой. */
    data class Quote(val lines: List<RichText>) : RichBlock

    data class Bullets(val items: List<Bullet>) : RichBlock

    data class CodeBlock(val text: String) : RichBlock

    data object Divider : RichBlock

    /** Строки таблицы; ячейка `null` — слита с верхней или левой (`^^`, `<<`). */
    data class Table(val rows: List<List<TableCell>>, val style: BlockStyle = BlockStyle.NONE) : RichBlock

    /**
     * Ряд — строки × столбцы (ADR-0044 §2; заказчик 2026-10-18: до 20 строк и 7 столбцов): [cells]
     * слева направо, строка за строкой, [columns] в строке. Всегда на одной строке — столбиком не
     * встаёт, горизонтальной прокрутки нет (§6).
     */
    data class Row(val cells: List<RowCell>, val style: BlockStyle = BlockStyle.NONE, val columns: Int = cells.size) : RichBlock

    /** Картинка внутри сообщения: номер в [RichContent.media]. */
    data class Image(val media: Int) : RichBlock

    /** Ссылка: `plain` — текстом, `button` — кнопкой, `card` — карточкой с заголовком и картинкой. */
    data class Link(
        val url: String,
        val mode: String,
        val title: String,
        val description: String,
        val media: Int = -1,
        /** Имя палитры из стиля блока; пусто — цвет ссылок из «Цветов» (заказчик 2026-10-19). */
        val color: String = "",
    ) : RichBlock

    /** Облако: файл по ссылке на чужое хранилище. `kind`: `file`, `document`, `audio`, `video`. */
    data class Cloud(val url: String, val kind: String, val title: String) : RichBlock

    /** Место на карте. */
    data class Place(val lat: Double, val lon: Double, val label: String) : RichBlock

    /** Контакт: человек в TIMa ([userId]) или имя и номер. */
    data class Contact(val userId: String, val name: String, val phone: String) : RichBlock

    /** Вставленное из подборки — ссылка, показывается по своей переписке. `cut`: `full` или `cut`. */
    data class Quoted(val kind: String, val chatId: String, val messageId: Long, val author: Boolean, val cut: String) : RichBlock

    /** Крупный эмоджи. */
    data class Emoji(val text: String) : RichBlock

    /**
     * Фото без текста — вне пузыря, одно или несколько (Решения §3): превью из тела, полное — по
     * нажатию, от почтальона. Номера — в [RichContent.media].
     */
    data class Album(val media: List<Int>) : RichBlock

    /** Файл вне пузыря (Решения §6): имя, размер, скачать и «Сохранить как». */
    data class File(val media: Int) : RichBlock

    /** Подкладка в конце: [lines] строк исходного и «Продолжить чтение». */
    data class Underlay(val kind: String, val chatId: String, val messageId: Long, val author: Boolean, val lines: Int) : RichBlock

    /**
     * Ответ (Решения §7 «`@Ник`», §11.1): чьё и какое сообщение. Имя и строку из него рисует экран
     * по своей переписке — в теле лежит только ссылка.
     */
    data class Reply(val userId: String, val chatId: String, val messageId: Long) : RichBlock

    /**
     * Блок, который эта версия показать не умеет: ряд, виджет, подкладка — пока их нет на экране,
     * или незнакомое от более новой версии. Показывается текстом его узлов — сообщение не
     * теряется.
     */
    data class Fallback(val text: String) : RichBlock
}

/**
 * Ячейка ряда: ширина в процентах (`null` — поровну), текст ячейки и виджеты под ним; [merge] —
 * слита с левой (`left`) или верхней (`up`) и сама не рисуется.
 */
data class RowCell(
    val width: Int?,
    val text: RichText?,
    val widgets: List<RichBlock> = emptyList(),
    val style: BlockStyle = BlockStyle.NONE,
    val merge: String = "",
)

/** Пункт списка и вложенные в него пункты. */
data class Bullet(val text: RichText, val children: List<Bullet> = emptyList())

data class TableCell(
    val text: RichText?,
    /** `start`, `center`, `end` или `null` — по умолчанию. */
    val align: String? = null,
    /** Слита с верхней (`up`) или левой (`left`) — своего текста нет. */
    val merge: String? = null,
)

/**
 * Строка ввода: какие отрезки набранного показать серым знаком, а какие — уже оформленными
 * (ПЛАН-(РС) РС5, Решения §7). Набранный текст не меняется: знаки остаются знаками. Реализует
 * `core-encryption` тем же разбором, что уходит в сообщение, — разойтись им негде.
 */
fun interface TypingMarks {
    fun of(text: String): List<TypingSpan>
}

/** Отрезок набранного текста. */
data class TypingSpan(val style: TypingStyle, val start: Int, val end: Int)

enum class TypingStyle { MARKER, BOLD, ITALIC, UNDERLINE, STRIKE, HIGHLIGHT, CODE, MENTION, HASHTAG, HEADING, QUOTE }
