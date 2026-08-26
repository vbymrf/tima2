package io.tima.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.tima.testui.Snapshot
import io.tima.testui.capture
import io.tima.testui.theme
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Знаки: нарисованные, а не набранные шрифтом.
 *
 * Тест существует ровно из-за находки, которую поймал снимок: символ «✉» из макета вышел
 * пустым прямоугольником — глифа нет в шрифте, подстановки не нашлось. Значит **знак не
 * проверен, пока его не увидели нарисованным**, и проверка обязана уметь отличить
 * «нарисовалось» от «ничего не нарисовалось».
 */
class MarksTest {

    /**
     * Каждый знак действительно рисует.
     *
     * Именно это ломается молча: пропавший глиф, нулевая толщина, знак цвета фона. Экран
     * при этом выглядит целым — просто на нём нет отметки, стрелки или галочки.
     */
    @Test
    fun каждый_знак_рисует_хоть_что_то() {
        for ((name, content) in ALL_GLYPHS) {
            for (dark in listOf(false, true)) {
                val snapshot = capture("знак-$name", 32, 32, dark, content = content)
                val background = theme(if (dark) "тёмная" else "светлая").surface
                val own = snapshot.pixels().count { !Snapshot.close(it, background) }
                assertTrue(
                    own > 8,
                    "$name (${if (dark) "dark" else "light"}): знак не нарисовался — " +
                        "своих пикселей всего $own",
                )
            }
        }
    }

    /**
     * Знаки различимы между собой.
     *
     * Три отметки означают три разные вещи: «ждёт», «ушло», «не ушло». Если два знака
     * рисуются одинаково, человек не различает состояний, а тест на «нарисовалось» этого
     * не заметит.
     */
    @Test
    fun знаки_не_повторяют_друг_друга() {
        val snapshots = ALL_GLYPHS.map { (name, content) ->
            name to capture("различие-$name", 32, 32, dark = false, content = content)
        }
        for (i in snapshots.indices) {
            for (j in i + 1 until snapshots.size) {
                val difference = snapshots[i].second.difference(snapshots[j].second)
                assertTrue(
                    difference > 0.01,
                    "${snapshots[i].first} и ${snapshots[j].first} нарисованы одинаково",
                )
            }
        }
    }

    private companion object {
        val ALL_GLYPHS: List<Pair<String, @Composable () -> Unit>> = listOf(
            "ждёт" to { Box(Modifier.padding(10.dp)) { Mark(MarkKind.Waits) } },
            "ушло" to { Box(Modifier.padding(10.dp)) { Mark(MarkKind.Left) } },
            "не-ушло" to { Box(Modifier.padding(10.dp)) { Mark(MarkKind.NotLeft) } },
            "влево" to { Box(Modifier.padding(8.dp)) { Arrow(Side.Left) } },
            "вправо" to { Box(Modifier.padding(8.dp)) { Arrow(Side.Right) } },
            "вверх" to { Box(Modifier.padding(8.dp)) { Arrow(Side.Up) } },
        )
    }
}
