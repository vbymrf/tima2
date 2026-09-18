package io.tima.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Значок раздела — рисованный (решение заказчика 2026-09-18).
 *
 * **Почему рисованный, а не эмодзи.** Эмодзи рисует прошивка, и каждая по-своему: цвет,
 * форма и даже наличие знака у неё свои. Рисованный значок одинаков на всех платформах и
 * красится цветом темы — как «назад», отметки и стрелки в этом же пакете.
 *
 * **Почему на сетке 24 и штрихом.** Все двенадцать построены в квадрате 24×24 линией
 * толщиной 2 с круглыми концами. Одна манера на набор — иначе «дом» и «звезда» выглядят
 * взятыми из разных мест, что они и есть, если рисовать каждый как получится.
 *
 * **Рисуется по индексу, а не по перечню домена.** `core-ui` — набор кирпичей и от домена
 * не зависит; состав набора и смысл индексов задаёт `SectionIcon` в `domain-chat`. Два
 * списка на одну правду — риск, и держит их вместе тест в `feature-chat`, который видит
 * оба: у каждого индекса из перечня обязан быть рисунок.
 *
 * Неизвестный индекс (набор старше клиента) рисуется как «без значка»: пустой квадрат
 * честнее чужого рисунка.
 */
@Composable
fun SectionGlyph(
    index: Int,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    color: Color = Tima.colors.text,
) {
    Canvas(modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val stroke = Stroke(width = 2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawSectionGlyph(index, u, color, stroke)
    }
}

/** Есть ли рисунок у индекса. Для теста, сверяющего набор с перечнем домена. */
fun hasSectionGlyph(index: Int): Boolean = index in 1..LAST_GLYPH

/** Последний индекс, у которого есть рисунок. Дописывается вместе с новым значком. */
private const val LAST_GLYPH = 16

private fun DrawScope.drawSectionGlyph(index: Int, u: Float, color: Color, stroke: Stroke) {
    // Номера — те же, что в `SectionIcon`: 1 дом … 12 звёздочка. Переставлять нельзя:
    // индекс хранится в базе и уезжает на другие устройства.
    when (index) {
        1 -> home(u, color, stroke)
        2 -> work(u, color, stroke)
        3 -> study(u, color, stroke)
        4 -> family(u, color, stroke)
        5 -> friends(u, color, stroke)
        6 -> money(u, color, stroke)
        7 -> health(u, color, stroke)
        8 -> shopping(u, color, stroke)
        9 -> travel(u, color, stroke)
        10 -> sport(u, color, stroke)
        11 -> music(u, color, stroke)
        12 -> star(u, color, stroke)
        13 -> news(u, color, stroke)
        14 -> movies(u, color, stroke)
        15 -> parcels(u, color, stroke)
        16 -> tools(u, color, stroke)
        else -> Unit
    }
}

// ── двенадцать рисунков ─────────────────────────────────────────────────────
//
// Координаты — в клетках сетки 24; `u` переводит их в пиксели. Каждый рисунок — один-два
// контура, чтобы читался и в 16 точек на полосе разделов.

private fun DrawScope.line(u: Float, color: Color, stroke: Stroke, vararg pts: Float) {
    val path = Path()
    path.moveTo(pts[0] * u, pts[1] * u)
    var i = 2
    while (i < pts.size) {
        path.lineTo(pts[i] * u, pts[i + 1] * u)
        i += 2
    }
    drawPath(path, color, style = stroke)
}

private fun DrawScope.closed(u: Float, color: Color, stroke: Stroke, vararg pts: Float) {
    val path = Path()
    path.moveTo(pts[0] * u, pts[1] * u)
    var i = 2
    while (i < pts.size) {
        path.lineTo(pts[i] * u, pts[i + 1] * u)
        i += 2
    }
    path.close()
    drawPath(path, color, style = stroke)
}

private fun DrawScope.ring(u: Float, color: Color, stroke: Stroke, cx: Float, cy: Float, r: Float) {
    drawCircle(color, radius = r * u, center = Offset(cx * u, cy * u), style = stroke)
}

/** Дом: крыша и стены. */
private fun DrawScope.home(u: Float, c: Color, s: Stroke) {
    line(u, c, s, 4f, 12f, 12f, 4f, 20f, 12f)
    closed(u, c, s, 6f, 11f, 6f, 20f, 18f, 20f, 18f, 11f)
}

/** Работа: портфель. */
private fun DrawScope.work(u: Float, c: Color, s: Stroke) {
    closed(u, c, s, 3f, 8f, 21f, 8f, 21f, 19f, 3f, 19f)
    line(u, c, s, 9f, 8f, 9f, 5f, 15f, 5f, 15f, 8f)
    line(u, c, s, 3f, 13f, 21f, 13f)
}

/** Учёба: раскрытая книга. */
private fun DrawScope.study(u: Float, c: Color, s: Stroke) {
    line(u, c, s, 12f, 6f, 12f, 20f)
    line(u, c, s, 12f, 6f, 8f, 4f, 3f, 5f, 3f, 19f, 8f, 18f, 12f, 20f)
    line(u, c, s, 12f, 6f, 16f, 4f, 21f, 5f, 21f, 19f, 16f, 18f, 12f, 20f)
}

/** Семья: сердце. */
private fun DrawScope.family(u: Float, c: Color, s: Stroke) {
    val path = Path().apply {
        moveTo(12f * u, 20f * u)
        cubicTo(4f * u, 14f * u, 3f * u, 9f * u, 6f * u, 6f * u)
        cubicTo(8.5f * u, 4f * u, 11f * u, 5f * u, 12f * u, 7.5f * u)
        cubicTo(13f * u, 5f * u, 15.5f * u, 4f * u, 18f * u, 6f * u)
        cubicTo(21f * u, 9f * u, 20f * u, 14f * u, 12f * u, 20f * u)
        close()
    }
    drawPath(path, c, style = s)
}

/** Друзья: две головы. */
private fun DrawScope.friends(u: Float, c: Color, s: Stroke) {
    ring(u, c, s, 8.5f, 8f, 3f)
    ring(u, c, s, 16f, 9f, 2.5f)
    line(u, c, s, 3f, 19f, 3f, 17f, 6f, 14f, 11f, 14f, 14f, 17f, 14f, 19f)
    line(u, c, s, 15f, 15f, 18f, 15f, 21f, 17f, 21f, 19f)
}

/** Деньги: монета с чертой. */
private fun DrawScope.money(u: Float, c: Color, s: Stroke) {
    ring(u, c, s, 12f, 12f, 8.5f)
    line(u, c, s, 12f, 7f, 12f, 17f)
    line(u, c, s, 15f, 9.5f, 10.5f, 9.5f, 10.5f, 12f, 13.5f, 12f, 13.5f, 14.5f, 9f, 14.5f)
}

/** Здоровье: крест. */
private fun DrawScope.health(u: Float, c: Color, s: Stroke) {
    closed(u, c, s, 9f, 3f, 15f, 3f, 15f, 9f, 21f, 9f, 21f, 15f, 15f, 15f, 15f, 21f, 9f, 21f, 9f, 15f, 3f, 15f, 3f, 9f, 9f, 9f)
}

/** Покупки: пакет с ручками. */
private fun DrawScope.shopping(u: Float, c: Color, s: Stroke) {
    closed(u, c, s, 4f, 8f, 20f, 8f, 19f, 21f, 5f, 21f)
    val handle = Path().apply {
        moveTo(8.5f * u, 8f * u)
        cubicTo(8.5f * u, 3f * u, 15.5f * u, 3f * u, 15.5f * u, 8f * u)
    }
    drawPath(handle, c, style = s)
}

/** Поездки: стрелка-самолёт. */
private fun DrawScope.travel(u: Float, c: Color, s: Stroke) {
    closed(u, c, s, 21f, 3f, 3f, 11f, 10f, 13f, 12f, 20f, 15f, 14f)
    line(u, c, s, 10f, 13f, 21f, 3f)
}

/** Спорт: мяч. */
private fun DrawScope.sport(u: Float, c: Color, s: Stroke) {
    ring(u, c, s, 12f, 12f, 8.5f)
    line(u, c, s, 3.5f, 12f, 20.5f, 12f)
    val seam = Path().apply {
        moveTo(12f * u, 3.5f * u)
        cubicTo(6f * u, 7f * u, 6f * u, 17f * u, 12f * u, 20.5f * u)
        moveTo(12f * u, 3.5f * u)
        cubicTo(18f * u, 7f * u, 18f * u, 17f * u, 12f * u, 20.5f * u)
    }
    drawPath(seam, c, style = s)
}

/** Музыка: нота. */
private fun DrawScope.music(u: Float, c: Color, s: Stroke) {
    ring(u, c, s, 8f, 17f, 3f)
    ring(u, c, s, 17f, 15f, 3f)
    line(u, c, s, 11f, 17f, 11f, 5f, 20f, 3f, 20f, 15f)
}

/** Новости: газета — лист с заголовком и строками. */
private fun DrawScope.news(u: Float, c: Color, s: Stroke) {
    closed(u, c, s, 4f, 5f, 20f, 5f, 20f, 19f, 4f, 19f)
    closed(u, c, s, 7f, 8f, 12f, 8f, 12f, 12f, 7f, 12f)
    line(u, c, s, 14f, 8f, 17f, 8f)
    line(u, c, s, 14f, 12f, 17f, 12f)
    line(u, c, s, 7f, 15.5f, 17f, 15.5f)
}

/** Кино: хлопушка. */
private fun DrawScope.movies(u: Float, c: Color, s: Stroke) {
    closed(u, c, s, 3f, 10f, 21f, 10f, 21f, 20f, 3f, 20f)
    line(u, c, s, 3f, 10f, 5f, 4f, 21f, 6f, 21f, 10f)
    line(u, c, s, 9f, 4.5f, 11f, 9.5f)
    line(u, c, s, 15f, 5.2f, 17f, 10f)
}

/** Посылки: коробка с крышкой. */
private fun DrawScope.parcels(u: Float, c: Color, s: Stroke) {
    closed(u, c, s, 4f, 9f, 20f, 9f, 20f, 20f, 4f, 20f)
    line(u, c, s, 4f, 9f, 6f, 4f, 18f, 4f, 20f, 9f)
    line(u, c, s, 12f, 4f, 12f, 9f)
    line(u, c, s, 10f, 13f, 14f, 13f)
}

/** Инструменты: гаечный ключ. */
private fun DrawScope.tools(u: Float, c: Color, s: Stroke) {
    ring(u, c, s, 16.5f, 7.5f, 4f)
    line(u, c, s, 13.5f, 10.5f, 4.5f, 19.5f)
    line(u, c, s, 3.5f, 18.5f, 5.5f, 20.5f)
    line(u, c, s, 15f, 5f, 19f, 9f)
}

/** Звёздочка: пять лучей. */
private fun DrawScope.star(u: Float, c: Color, s: Stroke) {
    val path = Path()
    val cx = 12f
    val cy = 12.5f
    for (i in 0 until 10) {
        val r = if (i % 2 == 0) 9f else 4f
        val a = -PI / 2 + i * PI / 5
        val x = (cx + r * cos(a)).toFloat() * u
        val y = (cy + r * sin(a)).toFloat() * u
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, c, style = s)
}

