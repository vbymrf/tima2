package io.tima.app.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Рисуем модули QR как залитые прямоугольники на Canvas — без промежуточного
 * растрового изображения (Bitmap на Android и BufferedImage/Skia на десктопе —
 * разные API, а закрашенный Canvas один и тот же на обеих JVM-платформах).
 */
@Composable
actual fun QrCodeImage(text: String, modifier: Modifier, sizeDp: Dp) {
    val matrix = remember(text) { QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 1, 1) }
    Canvas(modifier.size(sizeDp)) {
        val cell = size.width / matrix.width
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix.get(x, y)) {
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(x * cell, y * cell),
                        size = Size(cell, cell),
                    )
                }
            }
        }
    }
}
