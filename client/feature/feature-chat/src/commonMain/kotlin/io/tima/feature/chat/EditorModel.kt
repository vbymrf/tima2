package io.tima.feature.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.tima.domain.chat.ComposedDoc
import io.tima.domain.chat.ComposedItem
import io.tima.domain.chat.ComposedLayout
import io.tima.domain.chat.ComposedText
import io.tima.domain.chat.Drop
import io.tima.domain.chat.EditHistory
import io.tima.domain.chat.InlineKind
import io.tima.domain.chat.ParagraphKind
import io.tima.domain.chat.Spot
import io.tima.domain.chat.TextEdit

/**
 * Состояние редактора между нажатиями (Решения §10): документ, где курсор, отменить и повторить.
 *
 * В истории — документ с местом и выделением: «отменить» возвращает и текст, и место, где человек
 * был. Набор букв подряд — один шаг; пробел, перевод строки и кнопка начинают новый: иначе
 * пятьдесят шагов кончились бы на одном слове.
 *
 * Место — [Spot]: кусок сверху вниз, ячейка ряда, виджет в ней или текст ячейки.
 */
class EditorModel(initial: ComposedDoc) {

    /** Шаг истории: документ, место и выделение в тексте этого места. */
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

    private val history = EditHistory(
        initial.items.indexOfLast { it is ComposedItem.Text }.coerceAtLeast(0).let { at ->
            val len = (initial.items.getOrNull(at) as? ComposedItem.Text)?.source?.length ?: 0
            Snapshot(initial, at, len, len)
        },
    )

    var snapshot by mutableStateOf(history.current)
        private set

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

    val doc: ComposedDoc get() = snapshot.doc
    val focus: Int get() = snapshot.focus
    val spot: Spot get() = snapshot.spot

    /** Текст места под курсором: кусок-текст или текст ячейки; курсор не в тексте — `null`. */
    val focusedText: String? get() = textAt(doc, spot)

    val hasSelection: Boolean get() = focusedText != null && snapshot.start != snapshot.end

    private fun textAt(d: ComposedDoc, at: Spot): String? = when (val item = d.items.getOrNull(at.item)) {
        is ComposedItem.Text -> item.source.takeIf { at.cell == -1 }
        is ComposedItem.Row -> if (at.widget == Spot.TEXT) item.cells.getOrNull(at.cell)?.text else null
        else -> null
    }

    private fun fieldOf(s: Snapshot): TextFieldValue {
        val text = textAt(s.doc, s.spot).orEmpty()
        return TextFieldValue(text, TextRange(s.start.coerceIn(0, text.length), s.end.coerceIn(0, text.length)))
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
                else -> it
            }
        },
    )

    /** Человек набрал или сдвинул курсор в куске [index]. */
    fun changed(index: Int, value: TextFieldValue) = changedAt(Spot(index), value)

    /** То же — в тексте ячейки ряда. */
    fun changedCell(index: Int, cell: Int, value: TextFieldValue) = changedAt(Spot(index, cell, Spot.TEXT), value)

    private fun changedAt(at: Spot, value: TextFieldValue) {
        val before = textAt(doc, at) ?: return
        if (at != spot) typingIn = null
        field = value
        val next = Snapshot(withText(doc, at, value.text), at.item, value.selection.start, value.selection.end, at.cell, at.widget)
        if (value.text == before) {
            // Только курсор — не шаг истории.
            publish(push = false, next)
            return
        }
        val added = value.text.length - before.length
        val lastChar = value.text.getOrNull(value.selection.start - 1)
        val word = added == 1 && lastChar != null && !lastChar.isWhitespace()
        val glue = word && typingIn == at
        publish(push = !glue, next)
        typingIn = if (word) at else null
    }

    /** Курсор пришёл в кусок [index] (нажали на него). */
    fun focused(index: Int) = focusedAt(Spot(index))

    /** Курсор пришёл в текст ячейки. */
    fun focusedCell(index: Int, cell: Int) = focusedAt(Spot(index, cell, Spot.TEXT))

    private fun focusedAt(at: Spot) {
        if (at == spot) return
        typingIn = null
        val len = textAt(doc, at)?.length ?: 0
        val next = Snapshot(doc, at.item, len, len, at.cell, at.widget)
        publish(push = false, next)
        field = fieldOf(next)
    }

    /** Выбрать место без курсора в тексте — виджет, ячейку, ряд. */
    fun select(at: Spot) {
        if (at.item !in doc.items.indices) return
        typingIn = null
        publish(push = false, Snapshot(doc, at.item, 0, 0, at.cell, at.widget))
        field = fieldOf(snapshot)
    }

    fun select(index: Int) = select(Spot(index))

    private fun applyText(edit: TextEdit) {
        typingIn = null
        val next = Snapshot(withText(doc, spot, edit.source), focus, edit.start, edit.end, snapshot.cell, snapshot.widget)
        publish(push = true, next)
        field = fieldOf(next)
    }

    /** «Абзац ▾» — только у куска-текста: в ячейке ряда один узел, заголовков там нет. */
    fun paragraph(kind: ParagraphKind) {
        if (snapshot.cell != -1) return
        val text = focusedText ?: return
        applyText(ComposedText.paragraph(text, snapshot.start, kind))
    }

    /** Вид строки под курсором — для отметки в «Абзац ▾». */
    val paragraphHere: ParagraphKind
        get() = focusedText?.takeIf { snapshot.cell == -1 }?.let { ComposedText.paragraphAt(it, snapshot.start) } ?: ParagraphKind.PARAGRAPH

    /** Ж, К, Ч, З, М, код. */
    fun inline(kind: InlineKind) {
        val text = focusedText ?: return
        applyText(ComposedText.inline(text, snapshot.start, snapshot.end, kind))
    }

    /** «Символ». */
    fun symbol(symbol: String) {
        val text = focusedText ?: return
        applyText(ComposedText.symbol(text, snapshot.start, snapshot.end, symbol, doc.marks))
    }

    /**
     * «С оформл.» и обычная вставка: текст со знаками — туда, где курсор, без экранирования (знаки
     * в нём и есть оформление). Курсор не в тексте — новым куском после выбранного.
     */
    fun paste(text: String) {
        if (text.isEmpty()) return
        val current = focusedText
        if (current == null) {
            insert(ComposedItem.Text(text))
            return
        }
        val s = minOf(snapshot.start, snapshot.end).coerceIn(0, current.length)
        val e = maxOf(snapshot.start, snapshot.end).coerceIn(0, current.length)
        applyText(TextEdit(current.substring(0, s) + text + current.substring(e), s + text.length, s + text.length))
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
        val len = textAt(next, at)?.length ?: 0
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
