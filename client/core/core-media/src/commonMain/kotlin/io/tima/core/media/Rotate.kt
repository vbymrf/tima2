package io.tima.core.media

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint

/**
 * Повернуть картинку на четверть по часовой.
 *
 * Общим кодом, а не платформенным: поворот — арифметика, а не свойство телефона.
 * `Canvas` поверх `ImageBitmap` есть и на Android, и на ПК, и рисует он одинаково.
 *
 * Стороны меняются местами — это и есть поворот, а не наклон.
 */
fun rotateQuarter(image: ImageBitmap): ImageBitmap {
    val turned = ImageBitmap(width = image.height, height = image.width)
    val canvas = Canvas(turned)
    // Сдвиг на ширину нового холста, потом поворот: иначе картинка уедет за край и
    // получится пустой квадрат — ровно то, что выходит, если забыть про начало координат.
    canvas.translate(image.height.toFloat(), 0f)
    canvas.rotate(90f)
    canvas.drawImage(image, Offset.Zero, Paint())
    return turned
}

/**
 * Повернуть на столько четвертей, сколько сказано. Отрицательные и большие значения
 * приводятся к четырём: поворот на пять четвертей — это поворот на одну.
 */
fun rotateQuarters(image: ImageBitmap, quarters: Int): ImageBitmap {
    var result = image
    repeat(((quarters % 4) + 4) % 4) { result = rotateQuarter(result) }
    return result
}
