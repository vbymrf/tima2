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
data class RichContent(val blocks: List<RichBlock>)

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
    data class Paragraph(val text: RichText) : RichBlock

    /** Уровень 1–3. */
    data class Heading(val level: Int, val text: RichText) : RichBlock

    /** Строки цитаты — каждая своей строкой. */
    data class Quote(val lines: List<RichText>) : RichBlock

    data class Bullets(val items: List<Bullet>) : RichBlock

    data class CodeBlock(val text: String) : RichBlock

    data object Divider : RichBlock

    /** Строки таблицы; ячейка `null` — слита с верхней или левой (`^^`, `<<`). */
    data class Table(val rows: List<List<TableCell>>) : RichBlock

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
