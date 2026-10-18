package io.tima.feature.chat

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.tima.domain.chat.ComposedDoc
import io.tima.domain.chat.ComposedItem
import io.tima.domain.chat.MessageCircle
import io.tima.domain.chat.RichBlock
import io.tima.domain.chat.RichContent
import io.tima.domain.chat.RichText
import io.tima.testui.capture
import kotlin.test.Test
import kotlin.test.assertTrue

/** Окно редактора (Решения §10) рисуется: шапка, разделы, лента, текст; выделение — полоска Ж К Ч З М. */
class EditorScreenTest {

    private fun screen(model: EditorModel) = @androidx.compose.runtime.Composable {
        EditorScreen(
            model = model,
            levels = MessageCircle.forGroup(personal = false),
            defaultLevel = 2,
            typingMarks = null,
            preview = { doc -> RichContent(listOf(RichBlock.Paragraph(RichText(doc.firstLine)))) },
            problems = { emptyList() },
            onBack = {},
            onSend = {},
        )
    }

    @Test
    fun окно_и_полоска_над_выделенным() {
        val plain = EditorModel(ComposedDoc(listOf(ComposedItem.Text("# Сбор\nв **9:00** у входа"))))
        val a = capture("редактор", 360, 640, dark = false) { screen(plain)() }
        val chosen = EditorModel(ComposedDoc(listOf(ComposedItem.Text("# Сбор\nв **9:00** у входа"))))
        chosen.changed(0, TextFieldValue(chosen.focusedText!!, TextRange(0, 6)))
        val b = capture("редактор-выделение", 360, 640, dark = true) { screen(chosen)() }
        assertTrue(a.difference(b) > 0.0)
    }

    @Test
    fun ряд_из_ячеек_в_редакторе() {
        val doc = ComposedDoc(
            listOf(
                ComposedItem.Text("до ряда"),
                ComposedItem.Row(listOf(io.tima.domain.chat.ComposedCell(40, "слева"), io.tima.domain.chat.ComposedCell(60, "справа"))),
            ),
        )
        val model = EditorModel(doc)
        model.select(io.tima.domain.chat.Spot(1, 1))
        val a = capture("редактор-ряд", 360, 640, dark = false) { screen(model)() }
        val b = capture("редактор", 360, 640, dark = false) { screen(EditorModel(ComposedDoc.of("до ряда")))() }
        assertTrue(a.difference(b) > 0.0)
    }

    /**
     * Samsung 2026-10-18: вставили из подборки в ячейку ряда — приложение закрылось. Виджет в
     * редакторе рисуется пузырём (ряд во всю ширину), а ряд редактора меряет высоту ячеек по
     * содержимому — прежний ряд пузыря на такой вопрос падал.
     */
    @Test
    fun виджет_в_ячейке_ряда_не_роняет_редактор() {
        val doc = ComposedDoc(
            listOf(
                ComposedItem.Row(
                    listOf(
                        io.tima.domain.chat.ComposedCell(50, "слева", widgets = listOf(io.tima.domain.chat.ComposedWidget.Emoji("🎉"))),
                        io.tima.domain.chat.ComposedCell(50, "справа"),
                    ),
                ),
            ),
        )
        val asBubble: (ComposedDoc) -> RichContent? = {
            RichContent(listOf(RichBlock.Row(listOf(io.tima.domain.chat.RowCell(100, null, listOf(RichBlock.Emoji("🎉")))))))
        }
        val shot = capture("редактор-виджет-в-ячейке", 360, 400, dark = false) {
            EditorScreen(
                model = EditorModel(doc),
                levels = MessageCircle.forGroup(personal = false),
                defaultLevel = 2,
                typingMarks = null,
                preview = asBubble,
                problems = { emptyList() },
                onBack = {},
                onSend = {},
            )
        }
        val empty = capture("редактор", 360, 400, dark = false) { screen(EditorModel(ComposedDoc.of("до ряда")))() }
        assertTrue(shot.difference(empty) > 0.0)
    }

    /** Стиль куска виден в самом редакторе, как обычное оформление (заказчик 2026-10-18). */
    @Test
    fun стиль_куска_виден_в_редакторе() {
        val plain = capture("редактор-без-стиля", 360, 200, dark = false) { screen(EditorModel(ComposedDoc(listOf(ComposedItem.Text("сбор")))))() }
        val styled = capture("редактор-стиль-фон", 360, 200, dark = false) {
            screen(EditorModel(ComposedDoc(listOf(ComposedItem.Text("сбор", mapOf("background" to "amber-soft", "align" to "center"))))))()
        }
        assertTrue(plain.difference(styled) > 0.001, "фон и выравнивание куска не видны в редакторе")
    }

    @Test
    fun панель_стиля_палитра_ступени_json() {
        val model = EditorModel(ComposedDoc(listOf(ComposedItem.Text("x", mapOf("background" to "amber-soft")))))
        model.select(io.tima.domain.chat.Spot(0))
        val palette = capture("стиль-фон", 360, 160, dark = false) { StylePanel("background", model) { emptyList() } }
        val json = capture("стиль-json", 360, 160, dark = false) { StylePanel("json", model) { emptyList() } }
        assertTrue(palette.difference(json) > 0.0)
        model.layout { d, s -> io.tima.domain.chat.ComposedStyle.with(d, s, "padding", "large") to s }
        assertTrue(model.doc.items[0].style["padding"] == "large")
        model.undo()
        assertTrue(model.doc.items[0].style["padding"] == null, "стиль отменяется, как всё")
    }
}
