package io.tima.domain.chat

/**
 * Документ редактора (Решения §10): сверху вниз — текстовые куски, виджеты и ряды. Сообщение
 * собирается из него тем же конвейером, что и набранное (`core-encryption`), и получатель видит
 * узлы и разметку, а не документ.
 *
 * **Текст хранится набранным, со знаками быстрого оформления**; знак, набранный текстом, в нём
 * экранирован. Правится он без знаков — строками и отрезками ([Typed], ADR-0048): так у редактора и
 * строки ввода одна грамматика, а курсор идёт по видимым буквам.
 *
 * @param marks «Знаки md» (ADR-0048): включены — набранная пара и знак строки сразу становятся
 *   оформлением; выключены — остаются текстом. На сборку тела не влияют.
 * @param level уровень сообщения из шапки; `null` — уровень переписки по умолчанию.
 */
data class ComposedDoc(
    val items: List<ComposedItem> = listOf(ComposedItem.Text("")),
    val marks: Boolean = true,
    val level: Int? = null,
) {
    /** Пусто — отправлять нечего: ни текста, ни виджета. */
    val isEmpty: Boolean get() = items.all { it.isEmpty || it is ComposedItem.Continuation }

    /**
     * Основное и продолжение (Решения §11.3): всё до метки «Продолжение» — основное сообщение,
     * после неё — отдельное сообщение уровня метки. Метки нет — продолжения нет.
     */
    fun split(): Pair<ComposedDoc, ComposedDoc?> {
        val at = items.indexOfFirst { it is ComposedItem.Continuation }
        if (at < 0) return this to null
        val marker = items[at] as ComposedItem.Continuation
        val main = copy(items = items.subList(0, at).ifEmpty { listOf(ComposedItem.Text("")) })
        val rest = items.subList(at + 1, items.size).filterNot { it is ComposedItem.Continuation }
        val cont = ComposedDoc(rest.ifEmpty { listOf(ComposedItem.Text("")) }, marks, marker.level)
        return main to cont.takeUnless { it.isEmpty }
    }

    /** Первая строка текста — для чипа «Оформлено в редакторе» (Решения §9). */
    val firstLine: String
        get() = items.firstNotNullOfOrNull { item ->
            when (item) {
                is ComposedItem.Text -> item.source
                is ComposedItem.Row -> item.cells.firstOrNull { it.text.isNotBlank() }?.text
                is ComposedItem.ItemList -> item.items.firstOrNull { it.title.isNotBlank() }?.title
                is ComposedItem.Widget, is ComposedItem.Continuation -> null
            }?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }
        }.orEmpty()

    /** Сколько рядов сверху вниз — «Оформлено в редакторе · N рядов». */
    val rows: Int get() = items.count { !it.isEmpty && it !is ComposedItem.Continuation }

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

    /**
     * Ряд строк × столбцов (Решения §10.3, заказчик 2026-10-18): до 20 строк и до 7 столбцов,
     * [cells] — слева направо, строка за строкой. Пустой ряд — тоже содержимое: пустая ячейка уходит
     * и её фон виден.
     */
    data class Row(
        val cells: List<ComposedCell>,
        override val style: Map<String, String> = emptyMap(),
        val columns: Int = cells.size,
    ) : ComposedItem {
        override val isEmpty: Boolean get() = cells.isEmpty()
        val lines: Int get() = if (columns <= 0) 0 else (cells.size + columns - 1) / columns
    }

    /**
     * Список (ADR-0049): пункты по порядку со ступенью 1–3; пункт — как ячейка ряда. [open] —
     * «Развернуть по умолчанию», [flat] — «Без отступа»: свойства всего списка.
     */
    data class ItemList(
        val items: List<ComposedListItem>,
        override val style: Map<String, String> = emptyMap(),
        val open: Boolean = true,
        val flat: Boolean = false,
    ) : ComposedItem {
        override val isEmpty: Boolean get() = items.isEmpty()
    }

    /**
     * Метка «Продолжение» (Решения §11.3): что ниже — отдельное сообщение уровня [level] с пометкой
     * «только продолжение», подложенное в конец основного. Уходит первым.
     */
    data class Continuation(val level: Int) : ComposedItem {
        override val style: Map<String, String> get() = emptyMap()
        override val isEmpty: Boolean get() = false
    }
}

/**
 * Ячейка ряда: один узел текста (оформление внутри строки) и виджеты под ним (ADR-0044: ячейка —
 * один узел). [width] — проценты столбца, в строке в сумме 100. [merge] — слита с левой (`left`) или
 * с верхней (`up`): своего содержимого нет, место занимает соседняя.
 */
data class ComposedCell(
    val width: Int,
    val text: String = "",
    val widgets: List<ComposedWidget> = emptyList(),
    val style: Map<String, String> = emptyMap(),
    val merge: String = "",
) {
    val isEmpty: Boolean get() = text.isBlank() && widgets.isEmpty()
}

/**
 * Пункт списка (ADR-0049 §2): [depth] — ступень 1–3; [title] — заголовок, одна строка набранного со
 * знаками; [text] — содержимое под ним, как текст ячейки; [widgets] — виджеты под содержимым.
 */
data class ComposedListItem(
    val depth: Int = 1,
    val title: String = "",
    val text: String = "",
    val widgets: List<ComposedWidget> = emptyList(),
)

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
        /** Цвет — имя палитры, стилем блока ссылки; пусто — цвет ссылок у читающего (2026-10-19). */
        val color: String = "",
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

    /**
     * Подкладка (Решения §11.3): часть сообщения-источника ниже, без пузыря, [lines] строк и
     * «Продолжить чтение»; [author] — автор первым. Только в конце сообщения, несколько — списком.
     */
    data class Underlay(val kind: String, val container: String, val message: Long, val lines: Int = 3, val author: Boolean = false) : ComposedWidget
}

/** Вид абзаца — «Абзац ▾» (Решения §10.2). */
enum class ParagraphKind(val sign: String) {
    PARAGRAPH(""),
    HEADING1("# "),
    HEADING2("## "),
    HEADING3("### "),
    QUOTE("--> "),
    LIST("- "),
    LIST2("  - "),
    LIST3("    - "),
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

    /** Открытое тело записи страницы; есть файлы (картинка) — `null`: у записи канала их нет. */
    fun open(doc: ComposedDoc): OpenBody? = null
}

/** Чем кончилась отправка собранного. */
sealed interface ComposedResult {
    data class Queued(val dedupKey: String) : ComposedResult
    data object Empty : ComposedResult
    data class Invalid(val problems: List<String>) : ComposedResult
    /** Таблица шире пузыря: «99» не помещается в ячейку (Решения §10.3). */
    data class TableTooWide(val maxColumns: Int) : ComposedResult

    /**
     * Ушло продолжение (Решения §11.3: сначала оно, потом основное со ссылкой на него). Основное
     * [main] ждёт номера продолжения от сервера — его отправит `sendMain`.
     */
    data class ContinuationFirst(val dedupKey: String, val main: ComposedDoc) : ComposedResult
}

/** Отправка собранного в редакторе: тело — порт, очередь — та же, что у набранного. */
class SendComposed(
    private val queue: OutgoingQueue,
    private val bodies: ComposedBodies,
    private val keys: DedupKeys,
    /** Пометка «только продолжение» у своего исходящего; `null` — продолжений нет (личная). */
    private val continuations: ContinuationMarks? = null,
) {
    fun send(chatId: String, doc: ComposedDoc, level: Int, reply: ReplyTarget? = null): ComposedResult {
        if (doc.isEmpty) return ComposedResult.Empty
        if (TableFit.tooWide(doc).isNotEmpty()) return ComposedResult.TableTooWide(TableFit.maxColumns())
        val (main, cont) = doc.split()
        if (cont != null && continuations != null) {
            // Сначала продолжение — иначе ссылка основного вела бы в пустоту (Решения §11.3).
            val contBody = bodies.encode(cont) ?: return ComposedResult.Invalid(bodies.problems(cont))
            if (main.isEmpty) return ComposedResult.Empty
            bodies.problems(main.withUnderlay("group", chatId, 1)).takeIf { it.isNotEmpty() }?.let { return ComposedResult.Invalid(it) }
            val key = keys.newKey()
            queue.enqueue(key, chatId, contBody, cont.level ?: level, 0, KIND_TEXT)
            continuations.mark(key)
            return ComposedResult.ContinuationFirst(key, main)
        }
        val body = bodies.encode(main, reply) ?: return ComposedResult.Invalid(bodies.problems(main))
        val key = keys.newKey()
        queue.enqueue(key, chatId, body, main.level ?: level, 0, KIND_TEXT)
        return ComposedResult.Queued(key)
    }

    /** Основное после продолжения: подкладка со ссылкой на номер продолжения — в конец. */
    fun sendMain(chatId: String, main: ComposedDoc, kind: String, continuation: Long, level: Int, reply: ReplyTarget? = null): ComposedResult =
        send(chatId, main.withUnderlay(kind, chatId, continuation), level, reply)

    fun problems(doc: ComposedDoc): List<String> = bodies.problems(doc)

    fun preview(doc: ComposedDoc): RichContent? = bodies.preview(doc)

    fun save(doc: ComposedDoc): String = bodies.save(doc)

    fun load(saved: String): ComposedDoc? = bodies.load(saved)
}

/**
 * «Только продолжение» у своего исходящего (Решения §11.3): закрытая часть — отдельное сообщение
 * своего уровня, в ленте само не показывается. Пометку ставит отправка собранного, читает отправка
 * группы — поле `only_continuation` в запросе.
 */
interface ContinuationMarks {
    fun mark(dedupKey: String)
    fun marked(dedupKey: String): Boolean
}

/** Подкладка в конец документа — к основному после ушедшего продолжения (Решения §11.3). */
fun ComposedDoc.withUnderlay(kind: String, container: String, message: Long, lines: Int = 3): ComposedDoc =
    copy(items = items + ComposedItem.Widget(ComposedWidget.Underlay(kind, container, message, lines)))
