package io.tima.core.media

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Подготовка вложений до отправки: фото, место из вставленного, правила файлов (Решения §3, §5, §6). */
class AttachmentPrepTest {

    /** Шумная картинка: плохо жмётся, превью обязано спуститься по лестнице до 20 КБ. */
    private fun noisy(w: Int, h: Int): ByteArray {
        val image = ImageBitmap(w, h)
        val rnd = Random(1)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(w.toFloat(), h.toFloat())) {
            for (y in 0 until h step 8) for (x in 0 until w step 8) {
                drawRect(Color(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)), Offset(x.toFloat(), y.toFloat()), Size(8f, 8f))
            }
        }
        return encodeJpeg(image, 95)
    }

    @Test
    fun фото_ужимается_до_1280_и_превью_до_20_кб() {
        val prepared = assertNotNull(PhotoPrep.prepare(noisy(2000, 1500)))
        assertEquals(1280, prepared.width)
        assertEquals(960, prepared.height)
        assertTrue(prepared.preview.size <= PhotoPrep.PREVIEW_LIMIT, "превью ${prepared.preview.size} байт")
        assertNotNull(decodeImage(prepared.full))
        assertNull(PhotoPrep.prepare("не картинка".encodeToByteArray()))
    }

    @Test
    fun место_из_вставленного() {
        assertEquals(55.75 to 37.61, PlaceText.parse("55.75, 37.61"))
        assertEquals(55.75 to 37.61, PlaceText.parse("https://yandex.ru/maps/?pt=37.61,55.75&z=16"))
        assertEquals(55.75 to 37.61, PlaceText.parse("https://www.google.com/maps/@55.75,37.61,15z"))
        assertNull(PlaceText.parse("у метро"))
        assertNull(PlaceText.parse("95.1, 37.6"), "широта больше 90 — не место")
    }

    @Test
    fun запреты_и_предупреждения_по_имени() {
        assertTrue(FileRules.forbidden("setup.EXE"))
        assertTrue(FileRules.forbidden("page.html"))
        assertFalse(FileRules.forbidden("отчёт.pdf"))
        assertTrue(FileRules.warned("архив.zip"))
        assertFalse(FileRules.warned("отчёт.pdf"))
        assertEquals("application/pdf", FileRules.mimeOf("A.PDF"))
    }

    @Test
    fun картинка_внутри_сообщения_под_ширину_места() {
        val wide = assertNotNull(PhotoPrep.inline(noisy(1600, 400)))
        assertEquals(720, wide.width)
        assertEquals(180, wide.height)
        assertEquals(0, wide.preview.size, "превью у картинки внутри сообщения нет")
        val tall = assertNotNull(PhotoPrep.inline(noisy(600, 2000), width = 238))
        assertEquals(238, tall.width, "ячейка 33 % — 238")
        val small = assertNotNull(PhotoPrep.inline(noisy(200, 100)))
        assertEquals(200, small.width, "меньшее не растягивается")
    }
}
