package io.tima.domain.chat

/**
 * Документ редактора (Решения §10): сверху вниз — текстовые куски, виджеты и ряды. Сообщение
 * собирается из него тем же конвейером, что и набранное (`core-encryption`), и получатель видит
 * узлы и разметку, а не документ.
 *
 * **Текст хранится набранным, со знаками быстрого оформления** (Решения §7): «Абзац ▾», «Ж», «К» и
 * прочие кнопки ставят и снимают знаки, а не держат свою модель. Так у редактора и строки ввода
 * одна грамматика, и «Знаки md» видны как есть.
 *
 * @param marks «Знаки md»: выключены — текст уходит как набран, знаки остаются знаками.
 * @param level уровень сообщения из шапки; `null` — уровень переписки по умолчанию.
 */
data class ComposedDoc(
    val items: List<ComposedItem> = listOf(ComposedItem.Text("")),
    val marks: Boolean = true,
    val level: Int? = null,
) {
    /** Пусто — отправлять нечего: ни текста, ни виджета. */
    val isEmpty: Boolean get() = items.all { it.isEmpty }

    /** Первая строка текста — для чипа «Оформлено в редакторе» (Решения §9). */
    val firstLine: String
        get() = items.firstNotNullOfOrNull { item ->
            when (item) {
                is ComposedItem.Text -> item.source
                is ComposedItem.Row -> item.cells.firstOrNull { it.text.isNotBlank() }?.text
                is ComposedItem.Widget -> null
            }?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }
        }.orEmpty()

    /** Сколько рядов сверху вниз — «Оформлено в редакторе · N рядов». */
    val rows: Int get() = items.count { !it.isEmpty }

    companion object {
        /** Документ из набранного в строке ввода: человек открыл редактор, не потеряв текста. */
        fun of(typed: String, marks: Boolean = true) = ComposedDoc(listOf(ComposedItem.Text(typed)), marks)
    }
}

/** Кусок документа сверху вниз. [style] — стиль именами палитры и ступенями (ADR-0044 §4). */
sealed interface ComposedItem {
    val style: Map<String, String>
    val isEmpty: Boolean

    /** Набранный текст со знаками: абзацы, заголовки, списки, цитаты, код, таблицы. */
    data class Text(val source: String, override val style: Map<String, String> = emptyMap()) : ComposedItem {
        override val isEmpty: Boolean get() = source.isBlank()
    }

    /** Виджет во всю ширину — ряд из одной ячейки. */
    data class Widget(val widget: ComposedWidget, override val style: Map<String, String> = emptyMap()) : ComposedItem {
        override val isEmpty: Boolean get() = false
    }

    /** Ряд из 1–4 ячеек (Решения §10.3). */
    data class Row(val cells: List<ComposedCell>, override val style: Map<String, String> = emptyMap()) : ComposedItem {
        override val isEmpty: Boolean get() = cells.all { it.isEmpty }
    }
}

/**
 * Ячейка ряда: один узел текста (оформление внутри строки) и виджеты под ним (ADR-0044: ячейка —
 * один узел). [width] — проценты, в ряду в сумме 100.
 */
data class ComposedCell(
    val width: Int,
    val text: String = "",
    val widgets: List<ComposedWidget> = emptyList(),
    val style: Map<String, String> = emptyMap(),
) {
    val isEmpty: Boolean get() = text.isBlank() && widgets.isEmpty()
}

/** Виджеты редактора (Решения §10.4). */
sealed interface ComposedWidget {
    /** Картинка — уже загружена и закрыта своим ключом. */
    data class Image(val media: OutMedia) : ComposedWidget

    /** Ссылка: `plain`, `button`, `card`; у карточки — описание и картинка со страницы (Решения §4). */
    data class Link(
        val url: String,
        val title: String = "",
        val mode: String = "plain",
        val description: String = "",
        val image: OutMedia? = null,
    ) : ComposedWidget

    /** Файл вне телефона: `file`, `document`, `audio`, `video`. */
    data class Cloud(val url: String, val kind: String, val title: String = "") : ComposedWidget

    data class Place(val lat: Double, val lon: Double, val label: String = "") : ComposedWidget

    data class Contact(val name: String, val phone: String = "", val userId: String = "") : ComposedWidget

    data class Emoji(val text: String) : ComposedWidget

    /** Ответ на сообщение — `@Ник` (Решения §7). */
    data class Reply(val target: ReplyTarget) : ComposedWidget

    /**
     * Из подборки — ссылка на сообщение, а не копия (Решения §10.4, §11.2). [author] — галочка
     * «имя владельца»; [cut] — `full` или `cut` (по высоте соседа в ряду, нет соседа — три строки).
     */
    data class Quoted(val kind: String, val container: String, val message: Long, val author: Boolean = true, val cut: String = "") : ComposedWidget
}

/** Вид абзаца — «Абзац ▾» (Решения §10.2). */
enum class ParagraphKind(val sign: String) {
    PARAGRAPH(""),
    HEADING1("# "),
    HEADING2("## "),
    HEADING3("### "),
    QUOTE("--> "),
    LIST("- "),
    LIST2("    - "),
    CODE("```"),
}

/** Оформление части текста — знак, которым оно набирается (Решения §7). */
enum class InlineKind(val sign: String) {
    BOLD("**"),
    ITALIC("*"),
    UNDERLINE("++"),
    STRIKE("~~"),
    HIGHLIGHT("=="),
    CODE("`"),
}

/** Текст и выделение после правки кнопкой. [start] == [end] — курсор. */
data class TextEdit(val source: String, val start: Int, val end: Int)

/**
 * Правки набранного текста кнопками редактора. Чистые функции над строкой: кнопка ставит или снимает
 * знак, и результат тот же, что набрал бы человек руками.
 */
object ComposedText {

    private val LINE_SIGN = Regex("""^(\s*)(#{1,3} |--> ?|- )""")

    /** Знаки, которые в начале строки или внутри неё что-то значат (Решения §7). */
    private const val INLINE_SIGNS = "*+=~`\\|"
    private const val LINE_SIGNS = "#->"

    /** Сменить вид строки, где стоит курсор. Код — строками ```` ``` ```` над и под ней. */
    fun paragraph(source: String, cursor: Int, kind: ParagraphKind): TextEdit {
        val at = cursor.coerceIn(0, source.length)
        val lineStart = source.lastIndexOf('\n', at - 1) + 1
        val lineEnd = source.indexOf('\n', at).let { if (it < 0) source.length else it }
        val line = source.substring(lineStart, lineEnd)
        // Строка уже в блоке кода — снять его: «Абзац» над кодом значит «больше не код».
        val fenceAbove = lineStart >= 4 && source.substring(0, lineStart - 1).substringAfterLast('\n').trim() == "```"
        val fenceBelow = lineEnd < source.length && source.substring(lineEnd + 1).substringBefore('\n').trim() == "```"
        if (fenceAbove && fenceBelow) {
            val before = source.substring(0, lineStart - 1).let { it.substring(0, it.lastIndexOf('\n') + 1) }
            val afterFence = source.substring(lineEnd + 1).let { rest -> rest.indexOf('\n').let { if (it < 0) "" else rest.substring(it) } }
            val unwrapped = before + line + afterFence
            val shift = before.length - lineStart
            return if (kind == ParagraphKind.CODE) TextEdit(unwrapped, at + shift, at + shift)
            else paragraph(unwrapped, at + shift, kind)
        }
        val bare = LINE_SIGN.find(line)?.let { m -> line.substring(m.range.last + 1) } ?: line.trimStart()
        val replaced = when (kind) {
            ParagraphKind.CODE -> "```\n$bare\n```"
            else -> kind.sign + bare
        }
        val next = source.substring(0, lineStart) + replaced + source.substring(lineEnd)
        val prefix = if (kind == ParagraphKind.CODE) 4 else kind.sign.length
        val inLine = (at - lineStart - (line.length - bare.length)).coerceIn(0, bare.length)
        val caret = lineStart + prefix + inLine
        return TextEdit(next, caret, caret)
    }

    /** Вид строки, где стоит курсор, — чтобы «Абзац ▾» показал выбранное. */
    fun paragraphAt(source: String, cursor: Int): ParagraphKind {
        val at = cursor.coerceIn(0, source.length)
        val lineStart = source.lastIndexOf('\n', at - 1) + 1
        val line = source.substring(lineStart).substringBefore('\n')
        val trimmed = line.trimStart()
        return when {
            trimmed.startsWith("### ") -> ParagraphKind.HEADING3
            trimmed.startsWith("## ") -> ParagraphKind.HEADING2
            trimmed.startsWith("# ") -> ParagraphKind.HEADING1
            trimmed.startsWith("-->") -> ParagraphKind.QUOTE
            trimmed.startsWith("- ") -> if (line.length - trimmed.length >= 2) ParagraphKind.LIST2 else ParagraphKind.LIST
            else -> ParagraphKind.PARAGRAPH
        }
    }

    /**
     * Оформить выделенное или снять оформление. Пробелы по краям выделения остаются снаружи: знак
     * открывается перед непробельным и закрывается после него (Решения §7), иначе он стал бы текстом.
     */
    fun inline(source: String, start: Int, end: Int, kind: InlineKind): TextEdit {
        var s = minOf(start, end).coerceIn(0, source.length)
        var e = maxOf(start, end).coerceIn(0, source.length)
        while (s < e && source[s].isWhitespace()) s++
        while (e > s && source[e - 1].isWhitespace()) e--
        val sign = kind.sign
        if (s == e) {
            // Ничего не выделено — пара знаков, курсор между ними: дальше человек пишет оформленным.
            val next = source.substring(0, s) + sign + sign + source.substring(s)
            return TextEdit(next, s + sign.length, s + sign.length)
        }
        val inside = source.substring(s, e)
        // Знаки внутри выделения — снять.
        if (inside.length >= 2 * sign.length && inside.startsWith(sign) && inside.endsWith(sign) && !wider(inside, sign)) {
            val bare = inside.substring(sign.length, inside.length - sign.length)
            return TextEdit(source.substring(0, s) + bare + source.substring(e), s, s + bare.length)
        }
        // Знаки вокруг выделения — снять.
        val before = source.substring(0, s)
        val after = source.substring(e)
        if (before.endsWith(sign) && after.startsWith(sign) && !wider(before.takeLast(sign.length + 1), sign, atEnd = true)) {
            val next = before.dropLast(sign.length) + inside + after.drop(sign.length)
            return TextEdit(next, s - sign.length, e - sign.length)
        }
        val next = before + sign + inside + sign + after
        return TextEdit(next, s + sign.length, e + sign.length)
    }

    /** `*` — часть `**`: курсив не снимается с жирного. */
    private fun wider(around: String, sign: String, atEnd: Boolean = false): Boolean {
        if (sign != "*") return false
        return if (atEnd) around.length >= 2 && around[around.length - 2] == '*' else around.startsWith("**")
    }

    /**
     * Вставить знак кнопкой «Символ». Знак разметки экранируется сам (Решения §10.2): человек
     * вставлял знак, а не оформление.
     */
    fun symbol(source: String, start: Int, end: Int, symbol: String, marks: Boolean): TextEdit {
        val s = minOf(start, end).coerceIn(0, source.length)
        val e = maxOf(start, end).coerceIn(0, source.length)
        val lineStart = source.lastIndexOf('\n', s - 1) + 1
        val atLineStart = source.substring(lineStart, s).isBlank()
        val escape = marks && symbol.length == 1 &&
            (symbol[0] in INLINE_SIGNS || (atLineStart && symbol[0] in LINE_SIGNS))
        val put = if (escape) "\\" + symbol else symbol
        val next = source.substring(0, s) + put + source.substring(e)
        return TextEdit(next, s + put.length, s + put.length)
    }
}

/**
 * Отменить и повторить — 50 шагов на черновик (Решения §10.1). Хранит состояния целиком: документ
 * редактора невелик, а разность состояний — второе место для одной правды.
 */
class EditHistory<T>(initial: T, private val max: Int = MAX_STEPS) {
    private val back = ArrayDeque<T>()
    private val forward = ArrayDeque<T>()

    var current: T = initial
        private set

    val canUndo: Boolean get() = back.isNotEmpty()
    val canRedo: Boolean get() = forward.isNotEmpty()

    /** Новое состояние. То же самое — не шаг: иначе «отменить» ничего не меняло бы на экране. */
    fun push(next: T) {
        if (next == current) return
        back.addLast(current)
        if (back.size > max) back.removeFirst()
        forward.clear()
        current = next
    }

    /** Поправить нынешнее без нового шага — набор букв подряд склеивается в один шаг. */
    fun replace(next: T) {
        current = next
    }

    fun undo(): T {
        if (back.isEmpty()) return current
        forward.addLast(current)
        current = back.removeLast()
        return current
    }

    fun redo(): T {
        if (forward.isEmpty()) return current
        back.addLast(current)
        current = forward.removeLast()
        return current
    }

    companion object {
        const val MAX_STEPS = 50
    }
}

/**
 * Тело сообщения из документа редактора — реализует `core-encryption` (разметка выше него не
 * поднимается). Проверка — та же строгая `MarkupRules`, что на отправке и в JSON.
 */
interface ComposedBodies {
    /** Нарушения словами; пусто — можно отправлять. */
    fun problems(doc: ComposedDoc): List<String>

    /** Тело; нарушил правила — `null`. */
    fun encode(doc: ComposedDoc, reply: ReplyTarget? = null): ByteArray?

    /** Как увидит получатель — тем же разбором, что входящее. */
    fun preview(doc: ComposedDoc): RichContent?

    /** Черновик редактора строкой — в зашифрованное хранилище черновиков. */
    fun save(doc: ComposedDoc): String

    fun load(saved: String): ComposedDoc?
}

/** Чем кончилась отправка собранного. */
sealed interface ComposedResult {
    data class Queued(val dedupKey: String) : ComposedResult
    data object Empty : ComposedResult
    data class Invalid(val problems: List<String>) : ComposedResult
}

/** Отправка собранного в редакторе: тело — порт, очередь — та же, что у набранного. */
class SendComposed(
    private val queue: OutgoingQueue,
    private val bodies: ComposedBodies,
    private val keys: DedupKeys,
) {
    fun send(chatId: String, doc: ComposedDoc, level: Int, reply: ReplyTarget? = null): ComposedResult {
        if (doc.isEmpty) return ComposedResult.Empty
        val body = bodies.encode(doc, reply) ?: return ComposedResult.Invalid(bodies.problems(doc))
        val key = keys.newKey()
        queue.enqueue(key, chatId, body, doc.level ?: level, 0, KIND_TEXT)
        return ComposedResult.Queued(key)
    }

    fun problems(doc: ComposedDoc): List<String> = bodies.problems(doc)

    fun preview(doc: ComposedDoc): RichContent? = bodies.preview(doc)

    fun save(doc: ComposedDoc): String = bodies.save(doc)

    fun load(saved: String): ComposedDoc? = bodies.load(saved)
}
