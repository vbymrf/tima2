package io.tima.feature.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.tima.domain.chat.ComposedDoc
import io.tima.domain.chat.ComposedItem
import io.tima.domain.chat.ComposedLayout
import io.tima.domain.chat.ComposedLists
import io.tima.domain.chat.Drop
import io.tima.domain.chat.EditHistory
import io.tima.domain.chat.InlineKind
import io.tima.domain.chat.LineKind
import io.tima.domain.chat.ParagraphKind
import io.tima.domain.chat.PlainTyped
import io.tima.domain.chat.Spot
import io.tima.domain.chat.Typed
import io.tima.domain.chat.TypedEdits
import io.tima.domain.chat.TypedSource

/**
 * Состояние редактора между нажатиями (Решения §10): документ, где курсор, отменить и повторить.
 *
 * В истории — документ с местом и выделением: «отменить» возвращает и текст, и место, где человек
 * был. Набор букв подряд — один шаг; пробел, перевод строки и кнопка начинают новый: иначе
 * пятьдесят шагов кончились бы на одном слове.
 *
 * Место — [Spot]: кусок сверху вниз, ячейка ряда, виджет в ней или текст ячейки.
 *
 * Текст в документе — набранное со знаками; поле правит его без знаков ([Typed], ADR-0048): курсор
 * и выделение — по видимым буквам, Ж/К/Ч ставят отрезок, «Знаки md» превращают набранную пару в
 * оформление. Перевод туда и обратно — [source].
 */
class EditorModel(initial: ComposedDoc, val source: TypedSource = PlainTyped) {

    /** Шаг истории: документ, место и выделение в видимом тексте этого места. */
    data class Snapshot(
        val doc: ComposedDoc,
        val focus: Int,
        val start: Int,
        val end: Int,
        val cell: Int = -1,
        val widget: Int = -1,
    ) {
        val spot: Spot get() = Spot(focus, cell, widget)
    }

    /** Пункты строками (из строки ввода, прежний черновик) — сразу куском «Список» (ADR-0049 §4). */
    private val start: ComposedDoc = ComposedLists.lift(initial, source, "", Spot(0), 0).doc

    private val history = EditHistory(
        start.items.indexOfLast { it is ComposedItem.Text }.coerceAtLeast(0).let { at ->
            val len = (start.items.getOrNull(at) as? ComposedItem.Text)?.source?.let { source.read(it).text.length } ?: 0
            Snapshot(start, at, len, len)
        },
    )

    /** Заголовок пункта, который встаёт над пунктом ступени без родителя (ADR-0049 §4) — из словаря. */
    var untitled: String = ""

    var snapshot by mutableStateOf(history.current)
        private set

    /**
     * Набранное под курсором без знаков, как его правят: отрезки с пробелом на краю (жирный
     * продолжится после пробела) запись в знаках обрезала бы — поле держит их, пока текст тот же.
     */
    private class Live(val spot: Spot, val stored: String, val typed: Typed)

    private var live: Live? = null

    /**
     * Поле под курсором — с набором клавиатуры (`composition`): собирать его заново из снимка значило
     * бы обрывать набор подсказками на каждой букве.
     */
    var field by mutableStateOf(fieldOf(history.current))
        private set

    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    /** Последний шаг — набор букв на этом месте: следующая буква склеивается с ним. */
    private var typingIn: Spot? = null

    /** Оформление, которое наденется на следующие набранные буквы вместо продолжения (Ж без выделения, Word). */
    var pending by mutableStateOf<Set<InlineKind>?>(null)
        private set

    /** Просьба к окну поставить курсор в текст под курсором — после «писать после всего». */
    var focusAsk by mutableStateOf(0)
        private set

    val doc: ComposedDoc get() = snapshot.doc
    val focus: Int get() = snapshot.focus
    val spot: Spot get() = snapshot.spot

    /** Набранное со знаками в месте под курсором: кусок-текст или текст ячейки; курсор не в тексте — `null`. */
    val focusedText: String? get() = textAt(doc, spot)

    /** То же без знаков — что видно в поле. */
    val focusedTyped: Typed? get() = focusedText?.let { typedAt(spot) }

    val hasSelection: Boolean get() = focusedText != null && snapshot.start != snapshot.end

    private fun textAt(d: ComposedDoc, at: Spot): String? = when (val item = d.items.getOrNull(at.item)) {
        is ComposedItem.Text -> item.source.takeIf { at.cell == -1 }
        is ComposedItem.Row -> if (at.widget == Spot.TEXT) item.cells.getOrNull(at.cell)?.text else null
        is ComposedItem.ItemList -> item.items.getOrNull(at.cell)?.let {
            when (at.widget) {
                Spot.TITLE -> it.title
                Spot.TEXT -> it.text
                else -> null
            }
        }
        else -> null
    }

    /** Набранное места [at] без знаков — для поля и его вида. */
    fun typedAt(at: Spot): Typed {
        val stored = textAt(doc, at).orEmpty()
        live?.let { if (it.spot == at && it.stored == stored) return it.typed }
        return source.read(stored)
    }

    private fun typedIn(d: ComposedDoc, at: Spot): Typed {
        val stored = textAt(d, at).orEmpty()
        live?.let { if (it.spot == at && it.stored == stored) return it.typed }
        return source.read(stored)
    }

    private fun fieldOf(s: Snapshot): TextFieldValue {
        val text = if (textAt(s.doc, s.spot) == null) "" else typedIn(s.doc, s.spot).text
        return TextFieldValue(text, TextRange(s.start.coerceIn(0, text.length), s.end.coerceIn(0, text.length)))
    }

    /** Выделение по видимому тексту места под курсором: от меньшего к большему. */
    private fun selection(t: Typed): Pair<Int, Int> {
        val s = minOf(snapshot.start, snapshot.end).coerceIn(0, t.text.length)
        val e = maxOf(snapshot.start, snapshot.end).coerceIn(0, t.text.length)
        return s to e
    }

    private fun publish(push: Boolean, next: Snapshot) {
        if (push) history.push(next) else history.replace(next)
        snapshot = history.current
        canUndo = history.canUndo
        canRedo = history.canRedo
    }

    private fun withText(d: ComposedDoc, at: Spot, text: String): ComposedDoc = d.copy(
        items = d.items.mapIndexed { i, it ->
            when {
                i != at.item -> it
                it is ComposedItem.Text && at.cell == -1 -> it.copy(source = text)
                it is ComposedItem.Row && at.widget == Spot.TEXT ->
                    it.copy(cells = it.cells.mapIndexed { c, cell -> if (c == at.cell) cell.copy(text = text) else cell })
                it is ComposedItem.ItemList && at.widget == Spot.TITLE ->
                    it.copy(items = it.items.mapIndexed { c, item -> if (c == at.cell) item.copy(title = text) else item })
                it is ComposedItem.ItemList && at.widget == Spot.TEXT ->
                    it.copy(items = it.items.mapIndexed { c, item -> if (c == at.cell) item.copy(text = text) else item })
                else -> it
            }
        },
    )

    /** Человек набрал или сдвинул курсор в куске [index]. */
    fun changed(index: Int, value: TextFieldValue) = changedAt(Spot(index), value)

    /** То же — в тексте ячейки ряда. */
    fun changedCell(index: Int, cell: Int, value: TextFieldValue) = changedAt(Spot(index, cell, Spot.TEXT), value)

    /** То же — в заголовке ([Spot.TITLE]) или содержимом ([Spot.TEXT]) пункта списка. */
    fun changedItem(index: Int, item: Int, part: Int, value: TextFieldValue) = changedAt(Spot(index, item, part), value)

    private fun inList(at: Spot) = doc.items.getOrNull(at.item) is ComposedItem.ItemList

    private fun changedAt(at: Spot, typed: TextFieldValue) {
        textAt(doc, at) ?: return
        val before = typedAt(at)
        if (at != spot) typingIn = null
        // Заголовок пункта — одна строка: перевод строки — новый пункт или конец списка (Word).
        if (inList(at) && at.widget == Spot.TITLE && '\n' in typed.text) {
            val nl = typed.text.indexOf('\n')
            val moved = ComposedLists.enter(doc, at, before, nl, source) ?: return
            moved(moved)
            return
        }
        if (typed.text == before.text) {
            // Только курсор — не шаг истории; курсор ушёл — ожидавшее оформление снято.
            if (at != spot || typed.selection.start != snapshot.start || typed.selection.end != snapshot.end) pending = null
            field = typed
            publish(push = false, Snapshot(doc, at.item, typed.selection.start, typed.selection.end, at.cell, at.widget))
            return
        }
        val change = TypedEdits.change(
            before, typed.text, typed.selection.end, pending, convert = doc.marks,
            lineSigns = !(inList(at) && at.widget == Spot.TITLE),
            // В содержимом пункта вложенный пункт — ступенью, а не строкой со знаком.
            listSigns = !inList(at),
            untitled = untitled,
        )
        pending = null
        val after = change.typed
        val cursor = change.cursor
        val value = when {
            cursor != null -> TextFieldValue(after.text, TextRange(cursor))
            after.text == typed.text -> typed
            else -> TextFieldValue(after.text, TextRange(typed.selection.end.coerceIn(0, after.text.length)))
        }
        val stored = source.write(after)
        live = Live(at, stored, after)
        field = value
        val next = Snapshot(withText(doc, at, stored), at.item, value.selection.start, value.selection.end, at.cell, at.widget)
        val added = after.text.length - before.text.length
        val lastChar = after.text.getOrNull(value.selection.start - 1)
        val word = change.cursor == null && added == 1 && lastChar != null && !lastChar.isWhitespace()
        val glue = word && typingIn == at
        publish(push = !glue, next)
        typingIn = if (word) at else null
        // Набрали «- » в куске-тексте — строка стала пунктом: кусок «Список» (ADR-0049 §4).
        if (lifts(at, after)) moved(ComposedLists.lift(doc, source, untitled, at, value.selection.end))
    }

    private fun lifts(at: Spot, t: Typed) = doc.items.getOrNull(at.item) is ComposedItem.Text &&
        t.lines.any { it == LineKind.LIST1 || it == LineKind.LIST2 || it == LineKind.LIST3 }

    /** Документ переделан списком: курсор — на новое место, окно ставит туда фокус. */
    private fun moved(m: ComposedLists.Moved) {
        typingIn = null
        pending = null
        live = null
        publish(push = true, Snapshot(m.doc, m.spot.item, m.cursor, m.cursor, m.spot.cell, m.spot.widget))
        field = fieldOf(snapshot)
        focusAsk++
    }

    /**
     * Какие кнопки ленты нажаты: оформление там, где курсор, или ожидающее; при выделении — то, что
     * охватывает выделенное целиком (повторное нажатие его снимет).
     */
    fun activeInline(): Set<InlineKind> {
        val t = focusedTyped ?: return emptySet()
        pending?.let { return it }
        val (s, e) = selection(t)
        return if (s == e) TypedEdits.active(t, s) else TypedEdits.covering(t, s, e)
    }

    /** Курсор пришёл в кусок [index] (нажали на него). */
    fun focused(index: Int) = focusedAt(Spot(index))

    /** Курсор пришёл в текст ячейки. */
    fun focusedCell(index: Int, cell: Int) = focusedAt(Spot(index, cell, Spot.TEXT))

    /** Курсор пришёл в заголовок или содержимое пункта списка. */
    fun focusedItem(index: Int, item: Int, part: Int) = focusedAt(Spot(index, item, part))

    private fun focusedAt(at: Spot) {
        if (at == spot) return
        typingIn = null
        pending = null
        val len = if (textAt(doc, at) == null) 0 else typedAt(at).text.length
        val next = Snapshot(doc, at.item, len, len, at.cell, at.widget)
        publish(push = false, next)
        field = fieldOf(next)
    }

    /** Выбрать место без курсора в тексте — виджет, ячейку, ряд. */
    fun select(at: Spot) {
        if (at.item !in doc.items.indices) return
        typingIn = null
        pending = null
        publish(push = false, Snapshot(doc, at.item, 0, 0, at.cell, at.widget))
        field = fieldOf(snapshot)
    }

    fun select(index: Int) = select(Spot(index))

    /** Правка кнопкой: набранное без знаков [after], курсор или выделение [start]…[end]. */
    private fun applyTyped(after: Typed, start: Int, end: Int = start) {
        typingIn = null
        pending = null
        val stored = source.write(after)
        live = Live(spot, stored, after)
        val next = Snapshot(withText(doc, spot, stored), focus, start, end, snapshot.cell, snapshot.widget)
        publish(push = true, next)
        field = fieldOf(next)
    }

    /**
     * «Абзац ▾» — у куска-текста, в ячейке и в пункте списка. В заголовке пункта «Список», «Список 2»
     * — его ступень, остальное делает пункт строкой текста; в содержимом пункта списка нет — вложенный
     * пункт задаётся ступенью (ADR-0049 §4). В куске-тексте «Список» делает строку пунктом.
     */
    fun paragraph(kind: ParagraphKind) {
        val line = LineKind.of(kind)
        val list = line == LineKind.LIST1 || line == LineKind.LIST2 || line == LineKind.LIST3
        if (inList(spot) && spot.widget != Spot.TEXT) {
            val m = if (list) ComposedLists.depth(doc, spot, if (line == LineKind.LIST1) 1 else 2, untitled)
            else ComposedLists.toText(doc, spot, line, source)
            m?.let { moved(it.copy(cursor = if (list) snapshot.start else it.cursor)) }
            return
        }
        if (inList(spot) && list) return
        val t = focusedTyped ?: return
        val (s, e) = selection(t)
        val (after, shift) = TypedEdits.parents(TypedEdits.lines(t, s, e, line), untitled)
        applyTyped(after, shift(snapshot.start), shift(snapshot.end))
        if (lifts(spot, after)) moved(ComposedLists.lift(doc, source, untitled, spot, shift(snapshot.start)))
    }

    /** Вид строки под курсором — для отметки в «Абзац ▾»; в заголовке пункта — ступень. */
    val paragraphHere: ParagraphKind
        get() {
            val list = doc.items.getOrNull(spot.item) as? ComposedItem.ItemList
            if (list != null && spot.widget != Spot.TEXT) {
                val depth = list.items.getOrNull(spot.cell)?.depth ?: return ParagraphKind.PARAGRAPH
                return if (depth == 1) ParagraphKind.LIST else ParagraphKind.LIST2
            }
            val t = focusedTyped ?: return ParagraphKind.PARAGRAPH
            return t.lines.getOrElse(t.lineAt(selection(t).first)) { LineKind.PARAGRAPH }.paragraph
        }

    /**
     * Ж, К, Ч, З, М, код. Выделено — отрезок на выделенном (или снят, если он там уже весь). Не
     * выделено — как в Word: вид наденется на следующие буквы или перестанет продолжаться.
     */
    fun inline(kind: InlineKind) {
        val t = focusedTyped ?: return
        val (s, e) = selection(t)
        if (s != e) {
            applyTyped(TypedEdits.toggle(t, s, e, kind), snapshot.start, snapshot.end)
            return
        }
        val now = pending ?: TypedEdits.active(t, s)
        pending = if (kind in now) now - kind else now + kind
    }

    /** «Символ»: знак встаёт текстом — оформлением он не станет. */
    fun symbol(symbol: String) {
        val t = focusedTyped ?: return
        val (s, e) = selection(t)
        val change = TypedEdits.insert(t, s, e, Typed.plain(symbol))
        applyTyped(change.typed, change.cursor ?: s)
    }

    /**
     * Вставка: [text] — набранное со знаками («С оформл.» из HTML: знаки в нём и есть оформление);
     * [plain] — простой текст из буфера, знаки в нём — текст. Курсор не в тексте — новым куском
     * после выбранного.
     */
    fun paste(text: String, plain: Boolean = false) {
        if (text.isEmpty()) return
        val piece = if (plain) Typed.plain(text) else source.read(text)
        val t = focusedTyped
        if (t == null) {
            insert(ComposedItem.Text(source.write(piece)))
            return
        }
        val (s, e) = selection(t)
        val change = TypedEdits.insert(t, s, e, piece)
        applyTyped(change.typed, change.cursor ?: s)
    }

    /** Ширина рамкой (заказчик 2026-10-18): между столбцами [boundary] − 1 и [boundary] ряда [item]. */
    fun resize(item: Int, boundary: Int, delta: Int) =
        layout { d, s -> ComposedLayout.resize(d, item, boundary, delta) to s }

    /** «С левой», «С верхней»: `false` — слить нельзя. */
    fun join(direction: String): Boolean {
        val joined = ComposedLayout.join(doc, spot, direction) ?: return false
        layout { _, _ -> joined }
        return true
    }

    /** Список под курсором (ADR-0049 §3): его свойства задаёт «Разметка → Список». */
    val listHere: ComposedItem.ItemList? get() = doc.items.getOrNull(focus) as? ComposedItem.ItemList

    /** «Развернуть по умолчанию», «Без отступа» — на весь список под курсором; шаг истории. */
    fun listProps(open: Boolean, flat: Boolean) {
        val list = listHere ?: return
        typingIn = null
        publish(push = true, snapshot.copy(doc = doc.copy(items = doc.items.toMutableList().apply { set(focus, list.copy(open = open, flat = flat)) })))
    }

    /** «Знаки md» — шаг истории: отменяется, как и всё остальное. */
    fun toggleMarks() {
        typingIn = null
        publish(push = true, snapshot.copy(doc = doc.copy(marks = !doc.marks)))
    }

    fun level(level: Int) {
        typingIn = null
        publish(push = true, snapshot.copy(doc = doc.copy(level = level)))
    }

    /**
     * Поставить кусок после того, где курсор, и встать в него. Выбрана ячейка ряда, а вставляется
     * виджет, — он встаёт в ячейку, под её виджеты (Решения §10.3).
     */
    fun insert(item: ComposedItem) {
        typingIn = null
        // Виджет в пункт списка — под его содержимое (ADR-0049 §2).
        val list = doc.items.getOrNull(focus) as? ComposedItem.ItemList
        if (item is ComposedItem.Widget && list != null && snapshot.cell in list.items.indices) {
            val point = list.items[snapshot.cell]
            val items = list.items.toMutableList().apply { set(snapshot.cell, point.copy(widgets = point.widgets + item.widget)) }
            val next = Snapshot(doc.copy(items = doc.items.toMutableList().apply { set(focus, list.copy(items = items)) }), focus, 0, 0, snapshot.cell, point.widgets.size)
            publish(push = true, next)
            field = fieldOf(next)
            return
        }
        val row = doc.items.getOrNull(focus) as? ComposedItem.Row
        if (item is ComposedItem.Widget && row != null && snapshot.cell in row.cells.indices) {
            val cell = row.cells[snapshot.cell]
            val cells = row.cells.toMutableList().apply { set(snapshot.cell, cell.copy(widgets = cell.widgets + item.widget)) }
            val next = Snapshot(doc.copy(items = doc.items.toMutableList().apply { set(focus, row.copy(cells = cells)) }), focus, 0, 0, snapshot.cell, cell.widgets.size)
            publish(push = true, next)
            field = fieldOf(next)
            return
        }
        val at = (focus + 1).coerceIn(0, doc.items.size)
        val items = doc.items.toMutableList().apply { add(at, item) }
        // После виджета — пустой текст, чтобы было куда писать дальше.
        if (item !is ComposedItem.Text && items.getOrNull(at + 1) !is ComposedItem.Text) items.add(at + 1, ComposedItem.Text(""))
        val next = Snapshot(doc.copy(items = items), at, 0, 0)
        publish(push = true, next)
        field = fieldOf(next)
    }

    /**
     * Нажали на пустое место под сообщением — как Enter после всего (заказчик 2026-10-18): в конце
     * текст — курсор в его конец с новой строки; в конце виджет, ряд или метка — новый пустой текст.
     */
    fun writeAtEnd() {
        typingIn = null
        pending = null
        val last = doc.items.lastOrNull()
        if (last is ComposedItem.Text) {
            val i = doc.items.lastIndex
            val t = typedIn(doc, Spot(i))
            val more = t.text.isNotEmpty() && !t.text.endsWith("\n")
            val after = if (more) Typed(t.text + "\n", t.lines + LineKind.PARAGRAPH, t.spans) else t
            val stored = if (more) source.write(after) else last.source
            if (more) live = Live(Spot(i), stored, after)
            publish(push = more, Snapshot(withText(doc, Spot(i), stored), i, after.text.length, after.text.length))
        } else {
            val items = doc.items + ComposedItem.Text("")
            publish(push = true, Snapshot(doc.copy(items = items), items.lastIndex, 0, 0))
        }
        field = fieldOf(snapshot)
        focusAsk++
    }

    /** Где кончается основное сообщение: перед меткой продолжения или в самом конце. */
    private val mainEnd: Int get() = doc.items.indexOfFirst { it is ComposedItem.Continuation }.let { if (it < 0) doc.items.size else it }

    /** Подкладка — только в конец сообщения, несколько — списком (Решения §11.3). */
    fun appendToMain(item: ComposedItem) {
        typingIn = null
        val at = mainEnd
        val next = Snapshot(doc.copy(items = doc.items.toMutableList().apply { add(at, item) }), at, 0, 0)
        publish(push = true, next)
        field = fieldOf(next)
    }

    /** Метка «Продолжение» уровня [level] в конце и пустой текст под ней; уже есть — встать в неё. */
    fun addContinuation(level: Int) {
        typingIn = null
        val existing = doc.items.indexOfFirst { it is ComposedItem.Continuation }
        if (existing >= 0) {
            select(existing)
            return
        }
        val items = doc.items + ComposedItem.Continuation(level) + ComposedItem.Text("")
        val next = Snapshot(doc.copy(items = items), items.lastIndex, 0, 0)
        publish(push = true, next)
        field = fieldOf(next)
    }

    /** Заменить кусок [index] целиком (стиль, виджет). */
    fun replace(index: Int, item: ComposedItem) {
        if (index !in doc.items.indices) return
        typingIn = null
        publish(push = true, snapshot.copy(doc = doc.copy(items = doc.items.toMutableList().apply { set(index, item) })))
        field = fieldOf(snapshot)
    }

    /** Действие раздела «Разметка» над выбранным местом — шаг истории. */
    fun layout(op: (ComposedDoc, Spot) -> Pair<ComposedDoc, Spot>) {
        typingIn = null
        val (next, at) = op(doc, spot)
        if (next == doc && at == spot) return
        val len = if (textAt(next, at) == null) 0 else typedIn(next, at).text.length
        publish(push = true, Snapshot(next, at.item, len, len, at.cell, at.widget))
        field = fieldOf(snapshot)
    }

    /** Убрать кусок [index]; последний текст остаётся — писать должно быть куда. */
    fun remove(index: Int) = layout { d, _ -> ComposedLayout.removeAt(d, Spot(index)) }

    /** Сдвинуть кусок выше (−1) или ниже (+1). */
    fun move(index: Int, by: Int) = layout { d, _ -> ComposedLayout.shift(d, Spot(index), by) }

    /** Перенос пальцем или мышью (Решения §10.3). */
    fun drop(from: Spot, to: Drop) = layout { d, _ -> ComposedLayout.move(d, from, to) }

    fun undo() {
        typingIn = null
        history.undo()
        snapshot = history.current
        canUndo = history.canUndo
        canRedo = history.canRedo
        field = fieldOf(snapshot)
    }

    fun redo() {
        typingIn = null
        history.redo()
        snapshot = history.current
        canUndo = history.canUndo
        canRedo = history.canRedo
        field = fieldOf(snapshot)
    }
}
