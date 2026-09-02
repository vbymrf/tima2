package io.tima.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Выбор цвета: квадрат оттенков и полоса цветов — как в графических редакторах.
 *
 * ── ПОЧЕМУ ИМЕННО ТАК, А НЕ НАБОРОМ ОБРАЗЦОВ ────────────────────────────────
 *
 * Первая редакция палитры предлагала готовые квадратики — все значения светлой и тёмной
 * тем. Этого хватает, чтобы вернуться к известному цвету, и не хватает совсем, чтобы
 * подобрать свой: между двумя образцами нет ничего. Заказчик назвал нужное устройство
 * прямо 2026-09-03: «квадрат с оттенками и полоса цветов».
 *
 * **Полоса задаёт цвет, квадрат — насыщенность и яркость.** Это разложение HSV, и оно
 * выбрано не за красоту, а за то, что два движения не мешают друг другу: сменив цвет
 * полосой, человек не теряет подобранную яркость.
 *
 * ── ДВЕ ТОНКОСТИ, КОТОРЫЕ ЛОМАЮТ НАИВНУЮ РЕАЛИЗАЦИЮ ─────────────────────────
 *
 * **Цвет полосы хранится отдельно от выбранного цвета.** У чёрного и у белого оттенка
 * нет вовсе: посчитать его из значения нельзя, а если считать — ползунок полосы прыгает
 * в ноль, стоит увести квадрат в угол. Поэтому полоса помнит своё положение сама.
 *
 * **Непрозрачность сохраняется.** Половина цветов темы полупрозрачна — линия, рамка,
 * тихие подложки, — и выбор цвета не должен незаметно делать линию сплошной полосой.
 */
@Composable
fun ColorPicker(
    color: Color,
    onPick: (Color) -> Unit,
    modifier: Modifier = Modifier,
) {
    val (hue0, saturation, value) = color.hueSatVal()
    // Цвет полосы — своя память: у чёрного и белого оттенка нет, и считать его
    // из выбранного значило бы ронять ползунок в ноль при каждом заходе в угол.
    var hue by remember { mutableStateOf(hue0) }

    fun pick(s: Float, v: Float) = onPick(Color.hsv(hue, s, v, color.alpha))

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(TimaSpacing.about2),
    ) {
        // ── Квадрат: слева направо насыщенность, сверху вниз яркость ──────────
        androidx.compose.foundation.Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(SQUARE)
                .clip(RoundedCornerShape(TimaShapes.smallRadius))
                .border(1.dp, Tima.colors.line, RoundedCornerShape(TimaShapes.smallRadius))
                .follow { at, size ->
                    pick(
                        (at.x / size.first).coerceIn(0f, 1f),
                        1f - (at.y / size.second).coerceIn(0f, 1f),
                    )
                },
        ) {
            drawRect(Brush.horizontalGradient(listOf(TimaFixed.paper, Color.hsv(hue, 1f, 1f))))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, TimaFixed.ink)))
            marker(Offset(saturation * size.width, (1f - value) * size.height))
        }

        // ── Полоса цветов ────────────────────────────────────────────────────
        androidx.compose.foundation.Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(STRIPE)
                .clip(RoundedCornerShape(TimaShapes.circle))
                .background(Brush.horizontalGradient(RAINBOW), RoundedCornerShape(TimaShapes.circle))
                .follow { at, size ->
                    hue = (at.x / size.first).coerceIn(0f, 1f) * 360f
                    pick(max(saturation, LEAST), max(value, LEAST))
                },
        ) {
            marker(Offset(hue / 360f * size.width, size.height / 2))
        }
    }
}

/**
 * Нажатие и протяжку — одним жестом.
 *
 * `detectTapGestures` и `detectDragGestures` порознь здесь не годятся: первый не даёт
 * вести пальцем, второй срабатывает только после порога сдвига, то есть простое касание
 * теряется. Ожидание нажатия с последующей протяжкой покрывает оба случая сразу.
 */
private fun Modifier.follow(report: (Offset, Pair<Float, Float>) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val measures = size.width.toFloat() to size.height.toFloat()
        val down = awaitFirstDown()
        report(down.position, measures)
        down.consume()
        drag(down.id) { change ->
            report(change.position, measures)
            change.consume()
        }
    }
}

/**
 * Отметка выбранного места: два кольца, светлое и тёмное.
 *
 * Одного кольца мало по устройству самого квадрата — он весь построен на переходе от
 * белого к чёрному, и кольцо любого одного цвета пропадает на своей половине.
 */
private fun DrawScope.marker(at: Offset) {
    drawCircle(TimaFixed.ink, radius = MARKER.toPx(), center = at, style = Stroke(width = 3f))
    drawCircle(TimaFixed.paper, radius = MARKER.toPx() - 2f, center = at, style = Stroke(width = 3f))
}

/**
 * Цвет, насыщенность и яркость — обратное к [Color.hsv].
 *
 * Нужно, чтобы отметки стояли там, где стоит нынешний цвет: без этого выбор начинался бы
 * каждый раз из угла, а человек правит цвет, а не набирает заново.
 */
internal fun Color.hueSatVal(): Triple<Float, Float, Float> {
    val high = max(red, max(green, blue))
    val low = min(red, min(green, blue))
    val span = high - low
    val hue = when {
        span < 1e-6f -> 0f
        high == red -> 60f * (((green - blue) / span) % 6f)
        high == green -> 60f * (((blue - red) / span) + 2f)
        else -> 60f * (((red - green) / span) + 4f)
    }
    return Triple(
        if (hue < 0f) hue + 360f else hue,
        if (high < 1e-6f) 0f else span / high,
        high,
    )
}

/** Высота квадрата оттенков. Квадратом он выглядит только на телефоне — и это верно. */
private val SQUARE = 170.dp

/** Полоса цветов: толщиной с чип, чтобы в неё попадали пальцем. */
private val STRIPE = 26.dp

private val MARKER = 9.dp

/**
 * Наименьшая насыщенность и яркость при выборе цвета полосой.
 *
 * Без этого полоса выглядит сломанной: у чёрного цвета нет, и сколько ни води по радуге,
 * чёрное остаётся чёрным. Малый подъём делает выбор видимым, а дальше человек ведёт
 * квадратом.
 */
private const val LEAST = 0.15f

private val RAINBOW: List<Color> = (0..6).map { Color.hsv(it * 60f % 360f, 1f, 1f) }

/** Тождество для проверок: разложение и сборка обязаны сходиться. */
internal fun sameColor(first: Color, second: Color): Boolean =
    abs(first.red - second.red) < 0.01f &&
        abs(first.green - second.green) < 0.01f &&
        abs(first.blue - second.blue) < 0.01f
