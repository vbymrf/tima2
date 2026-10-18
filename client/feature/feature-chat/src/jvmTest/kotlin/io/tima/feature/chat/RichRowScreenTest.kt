package io.tima.feature.chat

import io.tima.domain.chat.BlockStyle
import io.tima.domain.chat.RichBlock
import io.tima.domain.chat.RichContent
import io.tima.domain.chat.RichText
import io.tima.domain.chat.RowCell
import io.tima.testui.bothThemes
import io.tima.testui.capture
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Ряд в пузыре (ADR-0044 §6): вмещается — ячейки в строку, не вмещается — столбиком, без
 * горизонтальной прокрутки. Фон ячейки — именем палитры, в двух темах свой. Снимки — в
 * `build/снимки`.
 */
class RichRowScreenTest {

    private val row = RichContent(
        listOf(
            RichBlock.Heading(1, RichText("Поход")),
            RichBlock.Row(
                listOf(
                    RowCell(30, RichText("Сбор"), style = BlockStyle(background = "amber-soft")),
                    RowCell(40, RichText("в 9:00 у входа"), style = BlockStyle(background = "blue-soft", align = "center")),
                    RowCell(30, null, widgets = listOf(RichBlock.Emoji("🎒"))),
                ),
                BlockStyle(divider = "2", dividerColor = "grey"),
            ),
            RichBlock.Paragraph(RichText("Возьмите воду"), BlockStyle(color = "red", size = "larger")),
            RichBlock.Place(55.75, 37.61, "Вход в парк"),
            RichBlock.Contact("", "Аня", "+7 900 000 00 00"),
        ),
    )

    @Test
    fun узкий_экран_ставит_ячейки_столбиком() {
        val wide = capture("ряд-широкий", 360, 420, dark = false) { RichBody(row) }
        val narrow = capture("ряд-узкий", 360, 420, dark = false) {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.width(200.dp)) { RichBody(row) }
        }
        assertTrue(wide.difference(narrow) > 0.0, "узкий и широкий нарисованы одинаково")
    }

    @Test
    fun палитра_в_двух_темах_своя() {
        val shots = bothThemes("ряд-темы", 360, 420) { RichBody(row) }
        assertTrue(shots.getValue("светлая").difference(shots.getValue("тёмная")) > 0.0)
    }
}
