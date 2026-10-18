package io.tima.feature.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.tima.domain.chat.ComposedDoc
import io.tima.domain.chat.ComposedItem
import io.tima.domain.chat.ComposedText
import io.tima.domain.chat.EditHistory
import io.tima.domain.chat.InlineKind
import io.tima.domain.chat.ParagraphKind
import io.tima.domain.chat.TextEdit

/**
 * Состояние редактора между нажатиями (Решения §10): документ, где курсор, отменить и повторить.
 *
 * В истории — документ с выделением: «отменить» возвращает и текст, и место, где человек был. Набор
 * букв подряд — один шаг; пробел, перевод строки и кнопка начинают новый: иначе пятьдесят шагов
 * кончились бы на одном слове.
 */
class EditorModel(initial: ComposedDoc) {

    /** Шаг истории: документ и выделение в куске [focus]. */
    data class Snapshot(val doc: ComposedDoc, val focus: Int, val start: Int, val end: Int)

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

    /** Последний шаг — набор букв в этом куске: следующая буква склеивается с ним. */
    private var typingIn: Int? = null

    val doc: ComposedDoc get() = snapshot.doc
    val focus: Int get() = snapshot.focus

    /** Текст куска под курсором; курсор не в тексте — `null`. */
    val focusedText: String? get() = (doc.items.getOrNull(focus) as? ComposedItem.Text)?.source

    val hasSelection: Boolean get() = focusedText != null && snapshot.start != snapshot.end

    private fun fieldOf(s: Snapshot): TextFieldValue {
        val text = (s.doc.items.getOrNull(s.focus) as? ComposedItem.Text)?.source.orEmpty()
        return TextFieldValue(text, TextRange(s.start.coerceIn(0, text.length), s.end.coerceIn(0, text.length)))
    }

    private fun publish(push: Boolean, next: Snapshot) {
        if (push) history.push(next) else history.replace(next)
        snapshot = history.current
        canUndo = history.canUndo
        canRedo = history.canRedo
    }

    /** Человек набрал или сдвинул курсор в куске [index]. */
    fun changed(index: Int, value: TextFieldValue) {
        val item = doc.items.getOrNull(index) as? ComposedItem.Text ?: return
        field = value
        val next = Snapshot(withText(index, value.text), index, value.selection.start, value.selection.end)
        if (value.text == item.source) {
            // Только курсор — не шаг истории.
            publish(push = false, next)
            return
        }
        val added = value.text.length - item.source.length
        val lastChar = value.text.getOrNull(value.selection.start - 1)
        val word = added == 1 && lastChar != null && !lastChar.isWhitespace()
        val glue = word && typingIn == index
        publish(push = !glue, next)
        typingIn = if (word) index else null
    }

    /** Курсор пришёл в кусок [index] (нажали на него). */
    fun focused(index: Int) {
        if (index == focus) return
        typingIn = null
        val len = (doc.items.getOrNull(index) as? ComposedItem.Text)?.source?.length ?: 0
        val next = Snapshot(doc, index, len, len)
        publish(push = false, next)
        field = fieldOf(next)
    }

    private fun withText(index: Int, text: String): ComposedDoc =
        doc.copy(items = doc.items.mapIndexed { i, it -> if (i == index && it is ComposedItem.Text) it.copy(source = text) else it })

    private fun applyText(edit: TextEdit) {
        typingIn = null
        val next = Snapshot(withText(focus, edit.source), focus, edit.start, edit.end)
        publish(push = true, next)
        field = fieldOf(next)
    }

    /** «Абзац ▾». */
    fun paragraph(kind: ParagraphKind) {
        val text = focusedText ?: return
        applyText(ComposedText.paragraph(text, snapshot.start, kind))
    }

    /** Вид строки под курсором — для отметки в «Абзац ▾». */
    val paragraphHere: ParagraphKind
        get() = focusedText?.let { ComposedText.paragraphAt(it, snapshot.start) } ?: ParagraphKind.PARAGRAPH

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

    /** «Знаки md» — шаг истории: отменяется, как и всё остальное. */
    fun toggleMarks() {
        typingIn = null
        publish(push = true, snapshot.copy(doc = doc.copy(marks = !doc.marks)))
    }

    fun level(level: Int) {
        typingIn = null
        publish(push = true, snapshot.copy(doc = doc.copy(level = level)))
    }

    /** Поставить кусок после того, где курсор, и встать в него (вставка виджета, ряда). */
    fun insert(item: ComposedItem) {
        typingIn = null
        val at = (focus + 1).coerceIn(0, doc.items.size)
        val items = doc.items.toMutableList().apply { add(at, item) }
        // После виджета — пустой текст, чтобы было куда писать дальше.
        if (item !is ComposedItem.Text && items.getOrNull(at + 1) !is ComposedItem.Text) items.add(at + 1, ComposedItem.Text(""))
        val next = Snapshot(doc.copy(items = items), at, 0, 0)
        publish(push = true, next)
        field = fieldOf(next)
    }

    /** Заменить кусок [index] целиком (ряд, стиль, виджет). */
    fun replace(index: Int, item: ComposedItem) {
        if (index !in doc.items.indices) return
        typingIn = null
        publish(push = true, snapshot.copy(doc = doc.copy(items = doc.items.toMutableList().apply { set(index, item) })))
        field = fieldOf(snapshot)
    }

    /** Убрать кусок [index]; последний текст остаётся — писать должно быть куда. */
    fun remove(index: Int) {
        if (index !in doc.items.indices) return
        typingIn = null
        val items = doc.items.toMutableList().apply { removeAt(index) }.ifEmpty { listOf(ComposedItem.Text("")) }
        val at = (index - 1).coerceIn(0, items.lastIndex)
        val next = Snapshot(doc.copy(items = items), at, 0, 0)
        publish(push = true, next)
        field = fieldOf(next)
    }

    /** Сдвинуть кусок выше (−1) или ниже (+1). */
    fun move(index: Int, by: Int) {
        val to = index + by
        if (index !in doc.items.indices || to !in doc.items.indices) return
        typingIn = null
        val items = doc.items.toMutableList().apply { add(to, removeAt(index)) }
        val next = Snapshot(doc.copy(items = items), to, 0, 0)
        publish(push = true, next)
        field = fieldOf(next)
    }

    /** Выбрать кусок без курсора в тексте — виджет или ряд. */
    fun select(index: Int) {
        if (index !in doc.items.indices) return
        typingIn = null
        publish(push = false, snapshot.copy(focus = index, start = 0, end = 0))
        field = fieldOf(snapshot)
    }

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
