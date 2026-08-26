package io.tima.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Мелкие знаки — **нарисованные, а не набранные шрифтом**.
 *
 * Это следствие находки, которую поймал снимок: символ «✉» из макета вышел пустым
 * прямоугольником — такого глифа нет в шрифте, которым рисует система, а подстановки для
 * него не нашлось. В браузере макет выглядит правильно, потому что там своя цепочка
 * подстановки шрифтов; у Compose она другая. Значит **символ из макета не проверен, пока
 * его не увидели нарисованным**, и полагаться на присутствие глифа в чужом шрифте нельзя.
 *
 * Галочка, круг, крест и стрелка — это две-три линии. Нарисованные, они выглядят
 * одинаково на всех платформах, не зависят от набора шрифтов устройства и проверяются
 * пиксельным тестом. Набор значков (К5) заменит их картинками, но не раньше, чем такой
 * набор появится.
 */

/** Что стало с моим сообщением. Только для исходящих: у входящих отметок не бывает. */
enum class MarkKind {
    /** Ждёт отправки: сеть, срок повтора, очередь. Пустой круг. */
    Waits,

    /** Сервер принял. Галочка. */
    Left,

    /**
     * Не ушло и не уйдёт — требует решения человека. Крест.
     *
     * **Без цвета**: красного в палитре нет вовсе, и опасное отличается знаком и словом,
     * а не цветом. Крест здесь того же цвета, что текст.
     */
    NotLeft,
}

/** Отметка о судьбе сообщения. Размер из макета: `.мета` — мелкий знак у времени. */
@Composable
fun Mark(kind: MarkKind, modifier: Modifier = Modifier, side: Dp = 12.dp) {
    val colors = Tima.colors
    val color = when (kind) {
        MarkKind.Waits -> colors.text3
        MarkKind.Left -> colors.text2
        MarkKind.NotLeft -> colors.text
    }
    Canvas(modifier.size(side)) {
        val thickness = size.minDimension * ТОЛЩИНА_ДОЛЯ
        val outline = Stroke(width = thickness, cap = StrokeCap.Round)
        when (kind) {
            MarkKind.Waits -> drawCircle(
                color = color,
                radius = size.minDimension / 2 - thickness / 2,
                style = outline,
            )

            MarkKind.Left -> {
                // Галочка двумя отрезками: короткий вниз, длинный вверх.
                line(color, thickness, 0.18f, 0.55f, 0.42f, 0.80f)
                line(color, thickness, 0.42f, 0.80f, 0.86f, 0.24f)
            }

            MarkKind.NotLeft -> {
                line(color, thickness, 0.22f, 0.22f, 0.78f, 0.78f)
                line(color, thickness, 0.78f, 0.22f, 0.22f, 0.78f)
            }
        }
    }
}

/** Куда смотрит стрелка. */
enum class Side { Left, Right, Up }

/**
 * Стрелка: «назад» в шапке подокна и «отправить» в зоне ввода.
 *
 * Рисуется по той же причине, что и отметки: «‹» и «→» есть не во всяком шрифте, а
 * пропавший знак навигации — это кнопка без надписи.
 */
@Composable
fun Arrow(
    side: Side,
    modifier: Modifier = Modifier,
    color: Color? = null,
    // sizeDp, а не size: внутри Canvas имя size занято DrawScope, и параметр его
    // закрывал бы — а там оно значит размер холста, а не заданную ширину значка.
    sizeDp: Dp = 16.dp,
) {
    val drawing = color ?: Tima.colors.text
    Canvas(modifier.size(sizeDp)) {
        val thickness = size.minDimension * ТОЛЩИНА_ДОЛЯ
        // Стрелка — три отрезка: древко и два пера. Так она читается и в 12 точек.
        when (side) {
            Side.Left -> {
                line(drawing, thickness, 0.80f, 0.50f, 0.22f, 0.50f)
                line(drawing, thickness, 0.22f, 0.50f, 0.48f, 0.24f)
                line(drawing, thickness, 0.22f, 0.50f, 0.48f, 0.76f)
            }

            Side.Right -> {
                line(drawing, thickness, 0.20f, 0.50f, 0.78f, 0.50f)
                line(drawing, thickness, 0.78f, 0.50f, 0.52f, 0.24f)
                line(drawing, thickness, 0.78f, 0.50f, 0.52f, 0.76f)
            }

            Side.Up -> {
                line(drawing, thickness, 0.50f, 0.80f, 0.50f, 0.22f)
                line(drawing, thickness, 0.50f, 0.22f, 0.24f, 0.48f)
                line(drawing, thickness, 0.50f, 0.22f, 0.76f, 0.48f)
            }
        }
    }
}

/** Толщина линии знака — доля от стороны: знак остаётся собой и в 12 точек, и в 24. */
private const val ТОЛЩИНА_ДОЛЯ = 0.14f

/** Отрезок в долях стороны: координаты знака описываются один раз и не зависят от размера. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.line(
    color: Color,
    thickness: Float,
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
) = drawLine(
    color = color,
    start = Offset(size.width * x1, size.height * y1),
    end = Offset(size.width * x2, size.height * y2),
    strokeWidth = thickness,
    cap = StrokeCap.Round,
)
