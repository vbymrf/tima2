package io.tima.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Полосы авторов — решение заказчика 2026-09-19: сто цветов, у каждой темы свой набор,
 * владелец салатовый, выключатель. Числа — из пробы `цвета-авторов-тьма.html`.
 */
class AuthorStripsTest {

    @Test
    fun в_каждом_наборе_сто_цветов_и_все_разные() {
        for (table in listOf(AuthorStrips.light, AuthorStrips.dark)) {
            assertEquals(100, table.size)
            assertEquals(100, table.toSet().size, "цвета повторяются — набор сгенерирован неверно")
        }
    }

    @Test
    fun первые_десять_авторов_не_повторяются_и_нулевой_пропущен() {
        val ten = (0 until 10).map { AuthorStrips.of(it, dark = false) }
        assertEquals(10, ten.toSet().size)
        assertNotEquals(AuthorStrips.light[0], ten.first(), "№0 близок к салатовому владельца и пропускается")
        // После 99 — по кругу, а не за край таблицы.
        assertEquals(AuthorStrips.of(0, dark = false), AuthorStrips.of(99, dark = false))
    }

    @Test
    fun тёмный_набор_читается_на_тёмном_пузыре_а_светлый_на_белом() {
        // Тот самый замер из пробы: набор одной светлоты с #024408 на #3d3d3d давал 1,06 : 1.
        // Средний набор обязан быть заметно выше — иначе полос в тёмной теме не видно.
        val darkBubble = Color(0xFF3D3D3D)
        val worstDark = AuthorStrips.dark.minOf { contrast(it, darkBubble) }
        assertTrue(worstDark >= 1.8, "самая тихая полоса тёмного набора на #3d3d3d: $worstDark : 1")
        val worstLight = AuthorStrips.light.minOf { contrast(it, Color.White) }
        assertTrue(worstLight >= 2.0, "самая тихая полоса светлого набора на белом: $worstLight : 1")
    }

    @Test
    fun тип_темы_и_выключатель_переживают_запись() {
        val look = Appearance.byDefault(systemDark = false).copy(
            choice = ThemeChoice.Custom,
            kind = ThemeKind.Dark,
            authorStrips = false,
        )
        val back = Appearance.read(look.write(), systemDark = false)
        assertEquals(ThemeKind.Dark, back.kind)
        assertEquals(false, back.authorStrips)
        assertTrue(back.dark, "своя тема с типом «тёмная» обязана считаться тёмной")
        assertEquals(StripLook(dark = true, colored = false), back.strips)
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble() + 0.05
        val lb = b.luminance().toDouble() + 0.05
        return maxOf(la, lb) / minOf(la, lb)
    }
}
