package io.tima.core.media

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Шаги правки фото (ПЛАН-(ИФ) ИФ6): поворот, обрезка, кисть, стрелка — по пикселям. */
class PhotoStepsTest {

    /** 40×20: левая половина красная, правая синяя. */
    private fun halves(): ImageBitmap {
        val img = ImageBitmap(40, 20)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(img), Size(40f, 20f)) {
            drawRect(Color.Red, size = Size(20f, 20f))
            drawRect(Color.Blue, topLeft = Offset(20f, 0f), size = Size(20f, 20f))
        }
        return img
    }

    private fun ImageBitmap.at(x: Int, y: Int): Int {
        val px = IntArray(1)
        readPixels(px, x, y, 1, 1)
        return px[0]
    }

    private val red = Color.Red.toArgbInt()
    private val blue = Color.Blue.toArgbInt()

    private fun Color.toArgbInt(): Int = toArgb()

    @Test
    fun поворот_по_часовой_левое_уходит_наверх() {
        val turned = PhotoSteps.turn(halves())
        assertEquals(20, turned.width)
        assertEquals(40, turned.height)
        assertEquals(red, turned.at(10, 5), "левая половина — сверху")
        assertEquals(blue, turned.at(10, 35), "правая — снизу")
        val full = PhotoSteps.apply(halves(), List(4) { PhotoStep.Turn })
        assertEquals(red, full.at(5, 10), "четыре поворота — как было")
    }

    @Test
    fun обрезка_по_долям_текущей_картинки() {
        val cut = PhotoSteps.apply(halves(), listOf(PhotoStep.Crop(0.5f, 0f, 1f, 1f)))
        assertEquals(20, cut.width)
        assertEquals(20, cut.height)
        assertEquals(blue, cut.at(2, 2))
        val turnedThenCut = PhotoSteps.apply(halves(), listOf(PhotoStep.Turn, PhotoStep.Crop(0f, 0f, 1f, 0.5f)))
        assertEquals(20 to 20, turnedThenCut.width to turnedThenCut.height)
        assertEquals(red, turnedThenCut.at(10, 10), "после поворота верхняя половина — бывшая левая")
    }

    @Test
    fun кисть_и_стрелка_рисуют_цветом_размер_тот_же() {
        val brushed = PhotoSteps.apply(halves(), listOf(PhotoStep.Brush(listOf(Offset(0.1f, 0.5f), Offset(0.9f, 0.5f)), Color.Green, 0.1f)))
        assertEquals(40 to 20, brushed.width to brushed.height)
        assertEquals(Color.Green.toArgbInt(), brushed.at(20, 10))
        val arrowed = PhotoSteps.apply(halves(), listOf(PhotoStep.Arrow(Offset(0.1f, 0.1f), Offset(0.9f, 0.9f), Color.Yellow, 0.05f)))
        assertNotEquals(blue, arrowed.at(36, 18), "у острия — стрелка, а не синее фото")
    }
}
