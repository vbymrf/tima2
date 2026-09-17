package io.tima.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.tima.testui.Snapshot
import io.tima.testui.capture
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * **Полоса автора огибает угол пузыря.**
 *
 * Заведено 2026-09-17 после того, как одно и то же место чинили трижды, и каждый раз
 * несоответствие макету находил глаз заказчика на телефоне. Проверки, которая ловила бы
 * это раньше, не было вовсе — теперь есть.
 *
 * В макете полоса — левая граница рамки: `border-left: 4px` у пузыря со скруглением.
 * Браузер ведёт её по всей угловой дуге, плавно сводя к толщине верхней рамки. Прежние
 * редакции обрывали её на углу: сперва плоским срезом, потом вертикальным.
 */
class BubbleStripTest {

    private fun shot(): Snapshot = capture("полоса-угол", WIDTH, HEIGHT, dark = false) {
        Box(modifier = Modifier.padding(INSET.dp)) {
            Bubble(my = false, strip = STRIP_COLOR) { Secondary("привет") }
        }
    }

    /**
     * Точка берётся на дуге под 45°: центр угловой окружности — (радиус, радиус), дуга
     * проходит через (радиус − радиус·cos45) по обеим осям. Прямоугольная полоса и
     * обводка постоянной ширины сюда не доставали — обе обрывались раньше.
     */
    @Test
    fun `полоса доходит до диагонали угла`() {
        val shot = shot()
        val offset = (RADIUS - RADIUS * 0.7071).toInt()
        val x = INSET + offset
        val y = INSET + offset
        val around = (x - 2..x + 2).flatMap { cx -> (y - 2..y + 2).map { cy -> shot.color(cx, cy) } }
        assertTrue(
            around.any { near(it, STRIP_COLOR) },
            "на диагонали угла ($x, $y) цвета полосы нет — значит она обрывается, не дойдя " +
                "до угла, и пузырь не такой, как в макете",
        )
    }

    /**
     * Вторая редакция рисовала обводку по центру линии: половина ширины уходила наружу, и
     * зелёное торчало над верхним краем. Проверяются пиксели ВЫШЕ пузыря — там её быть не
     * может.
     */
    @Test
    fun `полоса не выходит за верхний край пузыря`() {
        val shot = shot()
        val above = (0 until INSET).flatMap { y -> (0 until 40).map { x -> shot.color(x, y) } }
        assertTrue(
            above.none { near(it, STRIP_COLOR) },
            "цвет полосы найден выше пузыря — она нарисована за его границей",
        )
    }

    private fun near(a: Color, b: Color): Boolean =
        abs(a.red - b.red) < 0.12f && abs(a.green - b.green) < 0.12f && abs(a.blue - b.blue) < 0.12f

    private companion object {
        const val WIDTH = 320
        const val HEIGHT = 120

        /** Отступ от края снимка до пузыря — чтобы было где искать «снаружи». */
        const val INSET = 12

        /** `TimaShapes.radius` в точках; снимок рисуется плотностью 1. */
        const val RADIUS = 16

        /** Цвет полосы из макета: `--полоса: #2f9c8f` у первой реплики. */
        val STRIP_COLOR = Color(0xFF2F9C8F)
    }
}
