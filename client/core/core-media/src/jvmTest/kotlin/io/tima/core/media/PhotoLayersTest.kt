package io.tima.core.media

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Правка фото слоями (ADR-0052): поворот, рамка, отметки — по пикселям; одна правка на любом размере фото. */
class PhotoLayersTest {

    /** [w]×[h]: левая половина красная, правая синяя. */
    private fun halves(w: Int = 40, h: Int = 20): ImageBitmap {
        val img = ImageBitmap(w, h)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(img), Size(w.toFloat(), h.toFloat())) {
            drawRect(Color.Red, size = Size(w / 2f, h.toFloat()))
            drawRect(Color.Blue, topLeft = Offset(w / 2f, 0f), size = Size(w / 2f, h.toFloat()))
        }
        return img
    }

    private fun ImageBitmap.at(x: Int, y: Int): Int {
        val px = IntArray(1)
        readPixels(px, x, y, 1, 1)
        return px[0]
    }

    private val red = Color.Red.toArgb()
    private val blue = Color.Blue.toArgb()
    private val green = Color.Green.toArgb()

    @Test
    fun поворот_по_часовой_левое_уходит_наверх() {
        val turned = PhotoLayers.turn(halves())
        assertEquals(IntSize(20, 40), IntSize(turned.width, turned.height))
        assertEquals(red, turned.at(10, 5), "левая половина — сверху")
        assertEquals(blue, turned.at(10, 35), "правая — снизу")
        val full = PhotoLayers.render(halves(), PhotoMarks(turns = 4))
        assertEquals(red, full.at(5, 10), "четыре поворота — как было")
    }

    @Test
    fun рамка_в_долях_фото_и_поворот_поверх_неё() {
        val right = PhotoLayers.render(halves(), PhotoMarks(crop = Rect(0.5f, 0f, 1f, 1f)))
        assertEquals(IntSize(20, 20), IntSize(right.width, right.height))
        assertEquals(blue, right.at(2, 2))
        // Рамка — в долях исходного, до поворота: левая половина остаётся красной и после поворота.
        val turned = PhotoLayers.render(halves(), PhotoMarks(turns = 1, crop = Rect(0f, 0f, 0.5f, 1f)))
        assertEquals(IntSize(20, 20), IntSize(turned.width, turned.height))
        assertEquals(red, turned.at(10, 10))
    }

    @Test
    fun отметка_в_долях_фото_поворачивается_вместе_с_ним() {
        val line = PhotoMark.Brush(listOf(Offset(0.1f, 0.5f), Offset(0.9f, 0.5f)), Color.Green, 0.1f)
        val flat = PhotoLayers.render(halves(), PhotoMarks(marks = listOf(line)))
        assertEquals(green, flat.at(20, 10), "по середине — след")
        val turned = PhotoLayers.render(halves(), PhotoMarks(turns = 1, marks = listOf(line)))
        assertEquals(green, turned.at(10, 20), "после поворота след вертикален — по середине")
        assertEquals(red, turned.at(2, 2), "у края — фото")
        val arrow = PhotoLayers.render(halves(), PhotoMarks(marks = listOf(PhotoMark.Arrow(Offset(0.1f, 0.1f), Offset(0.9f, 0.9f), Color.Yellow, 0.05f))))
        assertNotEquals(blue, arrow.at(36, 18), "у острия — стрелка, а не синее фото")
    }

    @Test
    fun одна_правка_на_исходнике_вдвое_больше_даёт_вдвое_больший_итог() {
        val marks = PhotoMarks(turns = 1, crop = Rect(0f, 0f, 0.5f, 1f), marks = listOf(PhotoMark.Brush(listOf(Offset(0.1f, 0.5f), Offset(0.45f, 0.5f)), Color.Green, 0.05f)))
        val small = PhotoLayers.render(halves(40, 20), marks)
        val big = PhotoLayers.render(halves(80, 40), marks)
        assertEquals(IntSize(small.width * 2, small.height * 2), IntSize(big.width, big.height))
        assertEquals(small.at(10, 10) == green, big.at(20, 20) == green, "след на том же месте")
    }

    @Test
    fun точка_видимого_переводится_в_точку_фото_и_обратно() {
        for (q in 0..3) {
            val p = Offset(0.2f, 0.7f)
            val back = PhotoLayers.unrotate(PhotoLayers.rotate(p, q), q)
            assertTrue((back - p).getDistance() < 1e-5f, "поворот $q туда и обратно")
        }
        // Повёрнуто на четверть: верх видимого — левая сторона фото.
        val top = PhotoLayers.toPhoto(Offset(0.5f, 0.1f), 1, null)
        assertTrue(top.x < 0.5f, "верх видимого после поворота — левая половина фото, а не $top")
        // С рамкой: видимое — правая половина; его левый край — середина фото.
        val edge = PhotoLayers.toPhoto(Offset(0f, 0.5f), 0, Rect(0.5f, 0f, 1f, 1f))
        assertEquals(0.5f, edge.x, 1e-5f)
        // Вне видимого — вне фото: след с поля у края.
        assertTrue(PhotoLayers.toPhoto(Offset(-0.1f, 0.5f), 0, null).x < 0f)
    }
}
