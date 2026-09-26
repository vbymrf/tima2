package io.tima.core.call.desktop

import io.tima.core.call.VideoPicture
import livekit.proto.VideoRotation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Кадры ПК: поворот чужого и перекладка своего.
 *
 * Обе беды видны только глазами и только с камерой — лицо боком у собеседника с телефона,
 * синее лицо у своего окошка. На стенде без камеры их не увидит никто, поэтому ловим здесь.
 */
class PicturesTest {

    /** Кадр 2×1: слева точка A, справа точка B — по одному байту-метке на точку. */
    private fun twoByOne() = VideoPicture(2, 1, byteArrayOf(1, 1, 1, 1, 2, 2, 2, 2))

    private fun VideoPicture.marks(): List<Byte> = (0 until width * height).map { bgra[it * 4] }

    @Test
    fun без_поворота_кадр_тот_же() {
        val picture = twoByOne()
        assertTrue(turn(picture, VideoRotation.VIDEO_ROTATION_0) === picture)
    }

    @Test
    fun поворот_на_90_кладёт_кадр_на_бок() {
        val turned = turn(twoByOne(), VideoRotation.VIDEO_ROTATION_90)
        assertEquals(1, turned.width)
        assertEquals(2, turned.height)
        // По часовой: левая точка уходит наверх, правая — вниз.
        assertEquals(listOf<Byte>(1, 2), turned.marks())
    }

    @Test
    fun поворот_на_180_меняет_концы() {
        val turned = turn(twoByOne(), VideoRotation.VIDEO_ROTATION_180)
        assertEquals(listOf<Byte>(2, 1), turned.marks())
    }

    @Test
    fun поворот_на_270_обратен_повороту_на_90() {
        val turned = turn(twoByOne(), VideoRotation.VIDEO_ROTATION_270)
        assertEquals(1, turned.width)
        assertEquals(listOf<Byte>(2, 1), turned.marks())
    }

    @Test
    fun своё_окошко_получает_bgra_из_rgb() {
        // Красная точка: R=200, G=10, B=20. В BGRA синий идёт первым.
        val picture = rgbToPicture(1, 1, byteArrayOf(200.toByte(), 10, 20))
        assertEquals(listOf<Byte>(20, 10, 200.toByte(), -1), picture.bgra.toList())
    }

    @Test
    fun библиотека_камеры_грузится() {
        // На этом стенде камеры нет — честный ответ «нет камеры». Будет камера — поток
        // откроется. Не годится одно: библиотека не нашлась или не загрузилась.
        when (val opened = Camera.open(640, 480, 15)) {
            is Camera.Companion.Opened.Ready -> opened.camera.close()
            is Camera.Companion.Opened.Failed -> assertFalse(opened.why.contains(".dll"), opened.why)
        }
    }
}
