package io.tima.core.media

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Обрезка квадратом: то, что человек видит в окне, и есть то, что уходит на сервер.
 *
 * Картинка 400×200: левая половина красная, правая синяя. Окно 100. Вписанная по
 * меньшей стороне (200 → 100) картинка показывает в окне ровно середину — границу
 * цветов. Сдвиг до левого предела — окно целиком в красном.
 */
class SquareCropTest {

    private fun twoHalves(): ImageBitmap {
        val image = ImageBitmap(400, 200)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(400f, 200f)) {
            drawRect(Color.Red, size = Size(200f, 200f))
            drawRect(Color.Blue, topLeft = Offset(200f, 0f), size = Size(200f, 200f))
        }
        return image
    }

    @Test
    fun без_сдвига_в_окне_середина_картинки() {
        val cut = SquareCrop(twoHalves(), viewport = 100f).cut(Offset.Zero, zoom = 1f, side = 100)
        val px = cut.toPixelMap()
        assertEquals(Color.Red, px[10, 50], "слева от середины должно быть красное")
        assertEquals(Color.Blue, px[90, 50], "справа от середины должно быть синее")
    }

    @Test
    fun сдвиг_до_предела_показывает_край_и_не_дальше() {
        val crop = SquareCrop(twoHalves(), viewport = 100f)
        // Вписано: 400×200 → 200×100. Окно 100, значит вбок можно на (200-100)/2 = 50.
        assertEquals(Offset(50f, 0f), crop.panLimit(zoom = 1f))
        // Сдвиг картинки вправо на +50 открывает её левый край: в окне только красное.
        val cut = crop.cut(Offset(500f, 0f), zoom = 1f, side = 100)
        val px = cut.toPixelMap()
        assertEquals(Color.Red, px[5, 50])
        assertEquals(Color.Red, px[95, 50], "за левый край картинки окно уйти не должно, иначе тут был бы фон")
    }

    @Test
    fun увеличение_расширяет_пределы_сдвига() {
        val crop = SquareCrop(twoHalves(), viewport = 100f)
        val one = crop.panLimit(1f)
        val two = crop.panLimit(2f)
        assertTrue(two.x > one.x && two.y > one.y, "при увеличении вдвое картинка выходит за окно и по вертикали")
    }

    @Test
    fun результат_всегда_квадрат_нужной_стороны() {
        val cut = SquareCrop(twoHalves(), viewport = 280f).cut(Offset.Zero, zoom = 3f)
        assertEquals(SquareCrop.OUTPUT_SIDE, cut.width)
        assertEquals(SquareCrop.OUTPUT_SIDE, cut.height)
    }
}
