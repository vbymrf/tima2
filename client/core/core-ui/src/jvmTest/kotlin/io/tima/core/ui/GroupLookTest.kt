package io.tima.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.tima.testui.Snapshot
import io.tima.testui.capture
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Три вида группы (пробы `пробы-вид-группы.html`, заказчик 2026-10-07): где аватар автора.
 * Лента — как в `ChatScreen.Feed`: поле 12 по бокам. Картинки — в `build/снимки/`.
 */
class GroupLookTest {

    private fun feed(look: AvatarLook): Snapshot = capture("вид-группы-${look.name.lowercase()}", WIDTH, HEIGHT, dark = false) {
        ProvidePlace(TextPlace.MESSAGES) {
            Column(
                Modifier.padding(horizontal = FEED.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Bubble(my = false, author = "Анна Ковалёва", avatar = "АК", strip = STRIP, look = look, modifier = Modifier.padding(top = 6.dp)) { Secondary("da, все на месте") }
                Bubble(my = false, continuation = true, author = "Анна Ковалёва", strip = STRIP, look = look) { Secondary("ещё одно подряд") }
                Bubble(my = true, look = look) { Secondary("и моё") }
            }
        }
    }

    /** Есть ли не белый пиксель (рамка аватара) в столбце [x] на высоте первого сообщения. */
    private fun Snapshot.inkAt(x: Int): Boolean = (20 until 110).any { y -> dark(color(x, y)) }

    @Test
    fun у_края_аватар_в_двух_точках_от_края_экрана() {
        val edge = feed(AvatarLook.Edge)
        val free = feed(AvatarLook.Free)
        assertTrue(edge.inkAt(3), "«у края»: у левого края экрана рамки аватара нет")
        assertFalse(free.inkAt(3), "«свободно»: аватар не должен стоять у края экрана")
    }

    @Test
    fun имя_в_аватаре_сдвигает_ленту_на_колонку() {
        val inside = feed(AvatarLook.Inside)
        // Полоса автора (цвет пузыря) начинается не раньше колонки 2 + 43 + 2.
        val stripStart = (0 until WIDTH).firstOrNull { x -> (20 until 200).any { y -> near(inside.color(x, y), STRIP) } }
        assertTrue(stripStart != null && stripStart >= 45, "«имя в аватаре»: пузырь начинается с $stripStart, ждали после колонки аватаров")
        assertTrue(inside.inkAt(3), "«имя в аватаре»: аватара в колонке у края нет")
    }

    private fun dark(c: Color) = c.red + c.green + c.blue < 2.8f

    private fun near(a: Color, b: Color) = abs(a.red - b.red) < 0.08f && abs(a.green - b.green) < 0.08f && abs(a.blue - b.blue) < 0.08f

    private companion object {
        const val WIDTH = 360
        const val HEIGHT = 320
        const val FEED = 12
        val STRIP = Color(0xFFD9488F)
    }
}
