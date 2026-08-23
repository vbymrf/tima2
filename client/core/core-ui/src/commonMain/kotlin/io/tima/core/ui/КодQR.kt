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
fun КодQR(данные: String, modifier: Modifier = Modifier) {
    val матрица = remember(данные) { QrКод.матрица(данные) }
    if (матрица == null) {
        ПустаяОбласть(заголовок = "Код не показать", пояснение = "Он слишком длинный для QR", modifier = modifier)
        return
    }

    val чернила = Тима.цвета.кодЧернила
    val бумага = Тима.цвета.кодБумага

    Box(modifier = modifier.fillMaxWidth().aspectRatio(1f)) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
            нарисовать(матрица, чернила, бумага)
        }
    }
}

private fun DrawScope.нарисовать(
    матрица: QrМатрица,
    чернила: androidx.compose.ui.graphics.Color,
    бумага: androidx.compose.ui.graphics.Color,
) {
    val всего = матрица.размер + 2 * ТИХАЯ_ЗОНА
    val клетка = floor(minOf(size.width, size.height) / всего)
    if (клетка < 1f) return

    val сторона = клетка * всего
    val сдвигX = (size.width - сторона) / 2
    val сдвигY = (size.height - сторона) / 2

    drawRect(color = бумага, topLeft = Offset(сдвигX, сдвигY), size = Size(сторона, сторона))

    for (y in 0 until матрица.размер) {
        for (x in 0 until матрица.размер) {
            if (!матрица.тёмная(x, y)) continue
            drawRect(
                color = чернила,
                topLeft = Offset(
                    сдвигX + (x + ТИХАЯ_ЗОНА) * клетка,
                    сдвигY + (y + ТИХАЯ_ЗОНА) * клетка,
                ),
                size = Size(клетка, клетка),
            )
        }
    }
}

/** Четыре модуля — минимум по ISO/IEC 18004. Меньше делать нельзя, больше незачем. */
private const val ТИХАЯ_ЗОНА = 4
