package io.tima.core.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Линии в один пиксель — `border-bottom: 1px` и его родня.
 *
 * Отдельным элементом такая линия не рисуется намеренно: разделитель-элемент приходится
 * вставлять вызывающему, и он однажды забудет — либо поставит лишний в конце списка.
 * Линия принадлежит границе того, у чего она есть.
 *
 * Рисуется ПОВЕРХ содержимого, а не под ним. Под ним — `drawBehind` — она пропадает,
 * как только полоса закрашена своим фоном: у рейки и колонки содержимое заполняет всю
 * область, и граница уезжает под него. Так и было в первой редакции, и поймал это
 * пиксельный тест, а не глаз.
 *
 * Нижняя линия видна наружу дизайн-системы: её просит ряд фильтров окна, который
 * живёт в `feature-shell`. Верхняя пока нужна только здесь и остаётся `internal` —
 * открывать её «заодно» значит предлагать применить.
 */
fun Modifier.bottomLine(color: Color): Modifier = drawWithContent {
    drawContent()
    val thickness = 1.dp.toPx()
    drawLine(
        color = color,
        start = Offset(0f, size.height - thickness / 2),
        end = Offset(size.width, size.height - thickness / 2),
        strokeWidth = thickness,
    )
}

internal fun Modifier.topLine(color: Color): Modifier = drawWithContent {
    drawContent()
    val thickness = 1.dp.toPx()
    drawLine(
        color = color,
        start = Offset(0f, thickness / 2),
        end = Offset(size.width, thickness / 2),
        strokeWidth = thickness,
    )
}

internal fun Modifier.rightLine(color: Color): Modifier = drawWithContent {
    drawContent()
    val thickness = 1.dp.toPx()
    drawLine(
        color = color,
        start = Offset(size.width - thickness / 2, 0f),
        end = Offset(size.width - thickness / 2, size.height),
        strokeWidth = thickness,
    )
}

internal fun Modifier.leftLine(color: Color): Modifier = drawWithContent {
    drawContent()
    val thickness = 1.dp.toPx()
    drawLine(
        color = color,
        start = Offset(thickness / 2, 0f),
        end = Offset(thickness / 2, size.height),
        strokeWidth = thickness,
    )
}
