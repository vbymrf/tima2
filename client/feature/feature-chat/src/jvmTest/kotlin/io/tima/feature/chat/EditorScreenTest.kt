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
}
