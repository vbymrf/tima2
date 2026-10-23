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
    /** Имя файла вне пузыря (Решения §6); пусто у фото. */
    val name: String = "",
    val size: Long = 0,
    /** Голосовое (ПЛАН-(ВМ) §2.2): длительность и рисунок волны — от отправителя. */
    val voice: Boolean = false,
    val durationMs: Long = 0,
    val wave: ByteArray = ByteArray(0),
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
    data class Quote(val lines: List<RichText>, val style: BlockStyle = BlockStyle.NONE) : RichBlock

    /** Список (ADR-0049 §3): [closed] — показать свёрнутым, [flat] — без отступа вложенного. */
    data class Bullets(val items: List<Bullet>, val closed: Boolean = false, val flat: Boolean = false, val style: BlockStyle = BlockStyle.NONE) : RichBlock

    data class CodeBlock(val text: String, val style: BlockStyle = BlockStyle.NONE) : RichBlock

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
    /** [share] — доля ширины места, 20…100 % (2026-10-21); выравнивание — по стилю содержимого. */
    data class Image(val media: Int, val share: Int = 100) : RichBlock

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
    data class Quoted(
        val kind: String,
        val chatId: String,
        val messageId: Long,
        val author: Boolean,
        /** Строк; 0 — целиком (ADR-0050 §1). */
        val lines: Int = 3,
        val more: String = "",
        val moreText: String = "",
        val tap: Boolean = false,
    ) : RichBlock

    /** Крупный эмоджи. */
    data class Emoji(val text: String) : RichBlock

    /**
     * Фото без текста — вне пузыря, одно или несколько (Решения §3): превью из тела, полное — по
     * нажатию, от почтальона. Номера — в [RichContent.media].
     */
    data class Album(val media: List<Int>) : RichBlock

    /** Файл вне пузыря (Решения §6): имя, размер, скачать и «Сохранить как». */
    data class File(val media: Int) : RichBlock

    /** Голосовое вне пузыря (ПЛАН-(ВМ) §2.4): ▶, волна, время, скорость. */
    data class Voice(val media: Int) : RichBlock

    /** Видео по ссылке (ПЛАН-(ВМ) §3.6): [picture] — своя картинка в [RichContent.media], -1 — нет. */
    data class Video(
        val url: String,
        val description: String,
        val title: String = "",
        val showTitle: Boolean = false,
        val look: String = "picture",
        val picture: Int = -1,
    ) : RichBlock

    /** Звук по ссылке, играет в сообщении (§3.7). */
    data class Audio(val url: String, val description: String, val durationMs: Long = 0) : RichBlock

    /** Прямой эфир, только звук (§3.8). */
    data class Stream(val url: String, val description: String) : RichBlock

    /** Метки к видео или аудио этого сообщения по описанию [bind] (§3.6). */
    data class Marks(val bind: String, val marks: List<MediaMark>) : RichBlock

    /** Подкладка в конце: [lines] строк исходного и «Продолжить чтение». */
    data class Underlay(
        val kind: String,
        val chatId: String,
        val messageId: Long,
        val author: Boolean,
        /** Строк; 0 — целиком (ADR-0050 §1). */
        val lines: Int,
        val more: String = "",
        val moreText: String = "",
        val tap: Boolean = false,
        /** Разделитель после — у склеенных подкладок (стиль «Раздел.» блока). */
        val style: BlockStyle = BlockStyle.NONE,
    ) : RichBlock

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
 * Ячейка ряда: ширина в процентах (`null` — поровну), текст ячейки, её блоки (заголовки, списки,
 * цитаты, код — ADR-0048 §4) и виджеты под ними; [merge] — слита с левой (`left`) или верхней
 * (`up`) и сама не рисуется.
 */
data class RowCell(
    val width: Int?,
    val text: RichText?,
    val widgets: List<RichBlock> = emptyList(),
    val style: BlockStyle = BlockStyle.NONE,
    val merge: String = "",
    val blocks: List<RichBlock> = emptyList(),
)

/** Пункт списка и вложенные в него пункты. */
/** Пункт — контейнер (ADR-0049 §2): заголовок, свои блоки и виджеты, вложенные пункты. */
data class Bullet(val text: RichText, val children: List<Bullet> = emptyList(), val blocks: List<RichBlock> = emptyList(), val style: BlockStyle = BlockStyle.NONE)

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

/** [OPEN] — открывающий знак без пары: серым, пока не набран закрывающий (ADR-0048). */
enum class TypingStyle { MARKER, OPEN, BOLD, ITALIC, UNDERLINE, STRIKE, HIGHLIGHT, CODE, MENTION, HASHTAG, HEADING, QUOTE }
