package io.tima.core.media

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.panpf.sketch.AsyncImage
import com.github.panpf.sketch.PlatformContext
import com.github.panpf.sketch.Sketch
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Загрузчик картинок под наш формат (ПЛАН-(ЗК) ЗК5) — на ПК, без окна: та же отрисовка Compose,
 * что в приложении.
 */
class TimaSketchTest {

    @Test
    fun сетевых_загрузчиков_Sketch_в_сборке_нет() {
        // Сетевые модули Sketch (sketch-http*) не подключены — адрес из сообщения загрузчик
        // открыть не может, даже если попросят.
        for (name in listOf(
            "com.github.panpf.sketch.fetch.HttpUriFetcher",
            "com.github.panpf.sketch.http.KtorStack",
            "com.github.panpf.sketch.http.OkHttpStack",
            "com.github.panpf.sketch.http.HurlStack",
        )) {
            assertFailsWith<ClassNotFoundException>(name) { Class.forName(name) }
        }
    }

    @Test
    fun номер_файла_из_адреса() {
        assertEquals("abc-1", mediaIdOf(mediaUri("abc-1")))
        assertNull(mediaIdOf("https://example.com/a.png"), "чужой адрес — не наш")
        assertNull(mediaIdOf("tima-media://"), "пустой номер")
        assertNull(mediaIdOf("tima-media://a/b"), "номер без пути")
    }

    @Test
    fun файл_по_номеру_проходит_цепочку_байты_расшифровка_картинка() {
        val photo = paintedPng(0xFF3D55AA.toInt())
        // «Хранилище» держит файл зашифрованным: в начале не PNG.
        val stored = mapOf("p1" to seal(photo))
        assertFalse(stored.getValue("p1").copyOf(4).contentEquals(photo.copyOf(4)), "файл лежит открытым")

        val sketch = timaSketch(PlatformContext.INSTANCE, source = { id -> stored[id]?.let(::unseal) })
        val frame = render(sketch, mediaUri("p1"))
        assertTrue(centre(frame) == 0xFF3D55AA.toInt(), "картинка не показана: в середине ${"%08X".format(centre(frame))}")
    }

    @Test
    fun неудачи_различаются_по_причине() {
        val failures = mutableMapOf<String, MediaFailure>()
        val stored = mapOf(
            "garbage" to "не картинка".encodeToByteArray(),
            "locked" to byteArrayOf(1, 2, 3),
        )
        val sketch = timaSketch(
            PlatformContext.INSTANCE,
            source = { id ->
                if (id == "locked") throw MediaDecryptException("ключ не подошёл")
                stored[id]
            },
            onFailure = { id, reason, _ -> synchronized(failures) { failures[id] = reason } },
        )
        render(sketch, mediaUri("missing"))
        render(sketch, mediaUri("locked"))
        render(sketch, mediaUri("garbage"))
        assertEquals(MediaFailure.NO_BYTES, failures["missing"])
        assertEquals(MediaFailure.DECRYPT, failures["locked"])
        assertEquals(MediaFailure.DECODE, failures["garbage"])
    }

    @Test
    fun анимированный_WebP_даёт_разные_кадры() {
        val webp = checkNotNull(javaClass.getResourceAsStream("/media/animated.webp")).readBytes()
        val sketch = timaSketch(PlatformContext.INSTANCE, source = { id -> if (id == "e1") webp else null })
        val scene = ImageComposeScene(width = 160, height = 160, density = Density(1f)) {
            Column { AsyncImage(uri = mediaUri("e1"), sketch = sketch, contentDescription = null, modifier = Modifier.size(160.dp)) }
        }
        val frames = LinkedHashSet<Int>()
        val start = System.nanoTime()
        try {
            while (System.nanoTime() - start < 4_000_000_000L) {
                val bitmap = Bitmap.makeFromImage(scene.render(System.nanoTime()))
                if (varied(bitmap)) frames += hash(bitmap)
                Thread.sleep(40)
            }
        } finally {
            scene.close()
        }
        assertTrue(frames.size >= 3, "WebP не двигается: разных кадров ${frames.size}")
    }

    // ── помощники ──────────────────────────────────────────────────────────

    /** Отрисовать картинку по адресу; ждём, пока загрузчик успеет, — до 3 с. */
    private fun render(sketch: Sketch, uri: String): Bitmap {
        val scene = ImageComposeScene(width = 100, height = 100, density = Density(1f)) {
            AsyncImage(uri = uri, sketch = sketch, contentDescription = null, modifier = Modifier.size(50.dp))
        }
        try {
            var last = Bitmap.makeFromImage(scene.render(System.nanoTime()))
            val start = System.nanoTime()
            while (System.nanoTime() - start < 3_000_000_000L) {
                Thread.sleep(50)
                last = Bitmap.makeFromImage(scene.render(System.nanoTime()))
                if (varied(last)) break
            }
            return last
        } finally {
            scene.close()
        }
    }

    private fun paintedPng(argb: Int): ByteArray {
        val surface = Surface.makeRasterN32Premul(64, 64)
        val canvas: Canvas = surface.canvas
        canvas.clear(Color.WHITE)
        canvas.drawRect(Rect.makeWH(64f, 64f), Paint().apply { color = argb })
        return checkNotNull(surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)).bytes
    }

    /** Учебное «шифрование» хранилища: цепочку проверяет, криптографию — нет. */
    private fun seal(bytes: ByteArray) = ByteArray(bytes.size) { (bytes[it].toInt() xor 0x5A).toByte() }
    private fun unseal(bytes: ByteArray) = seal(bytes)

    /** Середина места картинки: она занимает левый верхний квадрат 50×50. */
    private fun centre(b: Bitmap): Int = b.getColor(25, 25)

    private fun varied(b: Bitmap): Boolean {
        val first = b.getColor(0, 0)
        for (y in 0 until b.height step 4) for (x in 0 until b.width step 4) if (b.getColor(x, y) != first) return true
        return false
    }

    private fun hash(b: Bitmap): Int {
        var h = 17
        for (y in 0 until b.height step 3) for (x in 0 until b.width step 3) h = 31 * h + b.getColor(x, y)
        return h
    }
}
