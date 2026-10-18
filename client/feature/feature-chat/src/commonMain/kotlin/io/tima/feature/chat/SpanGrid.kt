package io.tima.feature.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.tima.domain.chat.TableFit

/**
 * Сетка ряда (заказчик 2026-10-18): столбцы по процентам, слитая ячейка занимает прямоугольник
 * мест, строка — высотой самой высокой своей ячейки. Всегда в одну строку: столбиком не встаёт.
 *
 * Своя раскладка, а не `BoxWithConstraints`: ряд спрашивают «какой ты высоты по содержимому» (ряд
 * внутри ряда редактора) — подсостав на такой вопрос роняет приложение.
 *
 * @param widths проценты столбцов; сумма не 100 — делятся пропорционально.
 * @param spans на каждую ячейку — сколько столбцов и строк она занимает; `0 × 0` — слитое место,
 *   не рисуется.
 * @param cell ячейка с номером — рисуется только у ячеек с ненулевым прямоугольником.
 */
@Composable
internal fun SpanGrid(
    columns: Int,
    widths: List<Int>,
    spans: List<Pair<Int, Int>>,
    gap: Dp,
    modifier: Modifier = Modifier,
    minCellHeight: Dp = MIN_CELL_HEIGHT,
    cell: @Composable (Int) -> Unit,
) {
    val cols = columns.coerceAtLeast(1)
    val anchors = spans.indices.filter { spans[it].first > 0 && spans[it].second > 0 }
    Layout(content = { for (i in anchors) cell(i) }, modifier = modifier) { measurables, constraints ->
        val gapPx = gap.roundToPx()
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else TableFit.CONTENT_DP.dp.roundToPx()
        val free = (width - gapPx * (cols - 1)).coerceAtLeast(0)
        val shares = List(cols) { c -> widths.getOrNull(c)?.coerceAtLeast(1) ?: 1 }
        val total = shares.sum()
        val colW = shares.map { free * it / total }.toMutableList()
        colW[colW.lastIndex] += free - colW.sum()
        val colX = colW.runningFold(0) { x, w -> x + w + gapPx }
        val lines = (spans.size + cols - 1) / cols
        val minH = minCellHeight.roundToPx()
        val heights = MutableList(lines.coerceAtLeast(0)) { minH }
        fun spanWidth(i: Int): Int {
            val c = i % cols
            val w = spans[i].first.coerceAtMost(cols - c)
            return (c until c + w).sumOf { colW[it] } + gapPx * (w - 1)
        }
        // Сначала строки по ячейкам в одну строку, потом слитые по вертикали добирают последней строкой.
        anchors.forEachIndexed { k, i ->
            val l = i / cols
            if (spans[i].second == 1) heights[l] = maxOf(heights[l], measurables[k].minIntrinsicHeight(spanWidth(i)))
        }
        anchors.forEachIndexed { k, i ->
            val l = i / cols
            val h = spans[i].second.coerceAtMost(lines - l)
            if (h > 1) {
                val need = measurables[k].minIntrinsicHeight(spanWidth(i))
                val have = (l until l + h).sumOf { heights[it] } + gapPx * (h - 1)
                if (need > have) heights[l + h - 1] += need - have
            }
        }
        val lineY = heights.runningFold(0) { y, h -> y + h + gapPx }
        val placed = anchors.mapIndexed { k, i ->
            val l = i / cols
            val h = spans[i].second.coerceAtMost(lines - l)
            val cellH = (l until l + h).sumOf { heights[it] } + gapPx * (h - 1)
            measurables[k].measure(Constraints.fixed(spanWidth(i), cellH))
        }
        val height = (heights.sum() + gapPx * (lines - 1).coerceAtLeast(0)).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            anchors.forEachIndexed { k, i -> placed[k].place(colX[i % cols], lineY[i / cols]) }
        }
    }
}

/** Пустая ячейка видна (заказчик 2026-10-18): не ниже строки текста, её фон заметен. */
private val MIN_CELL_HEIGHT = 28.dp
