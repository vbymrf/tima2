package io.tima.core.call

import java.awt.image.BufferedImage
import java.io.File
import java.nio.ByteBuffer
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Фильтры ПК на видеокарте (ПЛАН-(ФЗ) ФЗ15): кадр проходит те же шейдеры, что на телефоне, и выходит другим.
 *
 * Нужен OpenGL 3.0: на машине без видеокарты (сервер сборки, виртуальная машина) проверка **пишет «пропуск»** и
 * ничего не утверждает — это не зелёный результат, а его отсутствие. Лицо — `TIMA_FACE` (картинка с лицом) и
 * `TIMA_NATIVES` (каталог с VNN и `face_pc`, по умолчанию — раскладка `callNatives`); итог — `build/снимки/фильтры-пк.png`.
 */
class DesktopFiltersTest {

    @Test
    fun фильтры_пк_собирают_кадр_на_видеокарте() {
        val natives = System.getenv("TIMA_NATIVES")?.let(::File)
            ?: File("../core-call-desktop/build/livekit-ffi/windows").takeIf { it.isDirectory }
        val studio = DesktopFilters(natives)
        if (!studio.available) {
            println("пропуск: на этой машине нет OpenGL 3.0 — фильтры ПК не проверены")
            return
        }
        val face = System.getenv("TIMA_FACE")?.let { ImageIO.read(File(it)) }
        val picture = face?.let { scaled(it, 640, 480) } ?: gradient(640, 480)
        val w = picture.width
        val h = picture.height
        val rgb = ByteBuffer.allocateDirect(w * h * 3)
        for (y in 0 until h) for (x in 0 until w) {
            val px = picture.getRGB(x, y)
            rgb.put((px shr 16).toByte()).put((px shr 8).toByte()).put(px.toByte())
        }
        rgb.flip()
        val f = CallFilters(
            on = true, smooth = 80, whiten = 30, brightness = 20, warmth = 30, look = ColorLook.Light, lookStrength = 60,
            thin = 60, eyes = 50, lipstick = 90, lipColor = LipColor.Berry, blush = 60, forehead = 80, eyeCorners = 80,
        )
        var out: ByteBuffer? = null
        // Поиск лица — раз в три кадра на своём потоке: дать ему найти и прийти в шейдеры.
        repeat(30) {
            rgb.rewind()
            out = studio.process(rgb, w, h, w, f)
            Thread.sleep(40)
        }
        if (face != null) assertTrue(studio.faceFound(), "лицо на картинке не нашлось: ${natives?.absolutePath}")
        val made = assertNotNull(out, "кадр не собрался: ${if (studio.broken) "сборка упала" else "нет ответа"}")
        rgb.rewind()
        var differ = 0L
        for (i in 0 until w * h * 3) if (made.get(i) != rgb.get(i)) differ++
        assertTrue(differ > w * h, "фильтры кадр не изменили")
        val shot = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) for (x in 0 until w) {
            val i = (y * w + x) * 3
            shot.setRGB(x, y, (made.get(i).toInt() and 255 shl 16) or (made.get(i + 1).toInt() and 255 shl 8) or (made.get(i + 2).toInt() and 255))
        }
        File("build/снимки").mkdirs()
        ImageIO.write(shot, "png", File("build/снимки/фильтры-пк.png"))
        // Только помада — меняется только рот: проверка, что сетка лица и картинка помады на ПК встают на губы.
        if (face != null) {
            val lips = CallFilters(on = true, lipstick = 100, lipColor = LipColor.Cherry)
            var only: ByteBuffer? = null
            repeat(12) {
                rgb.rewind()
                only = studio.process(rgb, w, h, w, lips)
                Thread.sleep(40)
            }
            val lipsShot = assertNotNull(only)
            var changed = 0
            var top = h
            var bottom = 0
            rgb.rewind()
            for (y in 0 until h) for (x in 0 until w) {
                val i = (y * w + x) * 3
                if (kotlin.math.abs((lipsShot.get(i + 1).toInt() and 255) - (rgb.get(i + 1).toInt() and 255)) > 20) {
                    changed++
                    top = minOf(top, y)
                    bottom = maxOf(bottom, y)
                }
            }
            println("помада: изменено точек $changed, строки $top…$bottom из $h")
            assertTrue(changed > 50, "помада не легла")
            assertTrue(top > h / 2, "помада легла выше рта: строка $top")
            val lipImg = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until h) for (x in 0 until w) {
                val i = (y * w + x) * 3
                lipImg.setRGB(x, y, (lipsShot.get(i).toInt() and 255 shl 16) or (lipsShot.get(i + 1).toInt() and 255 shl 8) or (lipsShot.get(i + 2).toInt() and 255))
            }
            ImageIO.write(lipImg, "png", File("build/снимки/фильтры-пк-помада.png"))
        }
        studio.close()
    }

    private fun scaled(src: BufferedImage, w: Int, h: Int): BufferedImage {
        // По высоте, с полями по бокам: лицо не сплющивается.
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        val k = h.toDouble() / src.height
        val sw = (src.width * k).toInt()
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(src, (w - sw) / 2, 0, sw, h, null)
        g.dispose()
        return out
    }

    private fun gradient(w: Int, h: Int) = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).also { img ->
        for (y in 0 until h) for (x in 0 until w) img.setRGB(x, y, (x * 255 / w shl 16) or (y * 255 / h shl 8) or 128)
    }
}
