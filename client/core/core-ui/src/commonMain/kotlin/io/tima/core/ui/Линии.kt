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
 */
internal fun Modifier.линияСнизу(цвет: Color): Modifier = drawWithContent {
    drawContent()
    val толщина = 1.dp.toPx()
    drawLine(
        color = цвет,
        start = Offset(0f, size.height - толщина / 2),
        end = Offset(size.width, size.height - толщина / 2),
        strokeWidth = толщина,
    )
}

internal fun Modifier.линияСверху(цвет: Color): Modifier = drawWithContent {
    drawContent()
    val толщина = 1.dp.toPx()
    drawLine(
        color = цвет,
        start = Offset(0f, толщина / 2),
        end = Offset(size.width, толщина / 2),
        strokeWidth = толщина,
    )
}

internal fun Modifier.линияСправа(цвет: Color): Modifier = drawWithContent {
    drawContent()
    val толщина = 1.dp.toPx()
    drawLine(
        color = цвет,
        start = Offset(size.width - толщина / 2, 0f),
        end = Offset(size.width - толщина / 2, size.height),
        strokeWidth = толщина,
    )
}

internal fun Modifier.линияСлева(цвет: Color): Modifier = drawWithContent {
    drawContent()
    val толщина = 1.dp.toPx()
    drawLine(
        color = цвет,
        start = Offset(толщина / 2, 0f),
        end = Offset(толщина / 2, size.height),
        strokeWidth = толщина,
    )
}
