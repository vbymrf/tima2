package io.tima.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.floor

/**
 * QR-код на экране.
 *
 * **Тихая зона обязательна.** Четыре светлых модуля по краям — не отступ для красоты:
 * сканеру нужно, где закончился код. Без неё код на цветном фоне часто не читается, и
 * выглядит это как «телефон плохой».
 *
 * **Клетка округляется до целых пикселей.** Дробный шаг даёт шов между модулями там, где
 * округление легло в разные стороны, — и сканер видит полосу, которой нет. Поэтому размер
 * клетки берётся целым, а остаток уходит в поля.
 *
 * @param данные строка, которую кодируем. Слишком длинная — не рисуется вовсе: пустое
 *   место честнее кода, который не отсканируется.
 */
@Composable
fun QrCodeImage(data: String, modifier: Modifier = Modifier) {
    val matrix = remember(data) { QrCode.matrix(data) }
    if (matrix == null) {
        EmptyArea(title = "Код не показать", explanation = "Он слишком длинный для QR", modifier = modifier)
        return
    }

    val ink = Tima.colors.inkCode
    val paper = Tima.colors.paperCode

    Box(modifier = modifier.fillMaxWidth().aspectRatio(1f)) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
            draw(matrix, ink, paper)
        }
    }
}

private fun DrawScope.draw(
    matrix: QrMatrix,
    ink: androidx.compose.ui.graphics.Color,
    paper: androidx.compose.ui.graphics.Color,
) {
    val total = matrix.size + 2 * QUIET_ZONE
    val cell = floor(minOf(size.width, size.height) / total)
    if (cell < 1f) return

    val side = cell * total
    val shiftX = (size.width - side) / 2
    val shiftY = (size.height - side) / 2

    drawRect(color = paper, topLeft = Offset(shiftX, shiftY), size = Size(side, side))

    for (y in 0 until matrix.size) {
        for (x in 0 until matrix.size) {
            if (!matrix.dark(x, y)) continue
            drawRect(
                color = ink,
                topLeft = Offset(
                    shiftX + (x + QUIET_ZONE) * cell,
                    shiftY + (y + QUIET_ZONE) * cell,
                ),
                size = Size(cell, cell),
            )
        }
    }
}

/** Четыре модуля — минимум по ISO/IEC 18004. Меньше делать нельзя, больше незачем. */
private const val QUIET_ZONE = 4
