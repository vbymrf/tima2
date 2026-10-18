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
 * Ряд в пузыре (ADR-0044 §6; заказчик 2026-10-18): всегда в одну строку — столбиком не встаёт,
 * горизонтальной прокрутки нет; пустая ячейка видна; слитая занимает прямоугольник. Фон ячейки —
 * именем палитры, в двух темах свой. Снимки — в `build/снимки`.
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

    private fun empty(width: Int, background: String, merge: String = "") =
        RowCell(width, null, style = BlockStyle(background = background), merge = merge)

    /** Три пустые ячейки с фоном на узком (200) месте: все три видны, в одну строку. */
    @Test
    fun узкий_ряд_в_одну_строку_и_пустые_ячейки_видны() {
        val cells = RichContent(listOf(RichBlock.Row(listOf(empty(33, "amber-soft"), empty(34, "blue-soft"), empty(33, "red-soft")))))
        val shot = capture("ряд-пустые-узкий", 200, 120, dark = false) {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.width(200.dp)) { RichBody(cells) }
        }
        val surface = shot.color(195, 110)
        val a = shot.color(20, 10)
        val b = shot.color(100, 10)
        val c = shot.color(170, 10)
        assertTrue(setOf(a, b, c).size == 3, "три ячейки в строку — три разных фона на одной высоте")
        assertTrue(surface !in setOf(a, b, c), "фон пустой ячейки виден")
    }

    /** 2 × 2, верхняя строка слита: слитая — на оба столбца, нижние — свои. */
    @Test
    fun слитая_ячейка_на_два_столбца() {
        val grid = RichContent(
            listOf(RichBlock.Row(listOf(empty(50, "amber-soft"), empty(50, "amber-soft", "left"), empty(50, "blue-soft"), empty(50, "red-soft")), columns = 2)),
        )
        val shot = capture("ряд-слитый", 200, 120, dark = false) { RichBody(grid) }
        assertTrue(shot.color(20, 10) == shot.color(150, 10), "верхняя — одна ячейка на всю ширину")
        assertTrue(shot.color(20, 48) != shot.color(150, 48), "нижние — две")
    }

    @Test
    fun палитра_в_двух_темах_своя() {
        val shots = bothThemes("ряд-темы", 360, 420) { RichBody(row) }
        assertTrue(shots.getValue("светлая").difference(shots.getValue("тёмная")) > 0.0)
    }
}
