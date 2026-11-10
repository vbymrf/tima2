package io.tima.core.call

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Точки лица для фильтров (ПЛАН-(ФЗ) ФЗ4): VNN. MediaPipe убран (заказчик 2026-11-10: медленнее вчетверо, +11 МБ,
 * вид не устроил).
 *
 * Кадр для поиска маленький и прямой ([WIDTH]×[HEIGHT], как видит человек) — его отдаёт `FrameStudio`, когда
 * [offer] свободен; поиск идёт на своём потоке, через кадр-два, и не держит камеру. Точки между поисками
 * сглаживаются по скорости (`FacePoints.follow`): стоят — не дрожат, движутся — не отстают; старше [STALE_MS] — лица нет.
 */
internal class FaceWatch(private val context: Context) {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "tima-faces").apply { isDaemon = true } }
    private val busy = AtomicBoolean(false)
    private val pixels: ByteBuffer = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4).order(ByteOrder.nativeOrder())

    /** Последние точки — в прямом кадре (доли, y вниз), сглаженные. */
    @Volatile
    private var upright: FloatArray? = null

    @Volatile
    private var seenAt = 0L

    /** Помада гаснет, пока рот движется (`LipFade`): точки прошлого поиска — сравнивать с новыми. */
    private val fade = LipFade()
    private var lastRaw: FloatArray? = null
    private var found: Boolean? = null

    // ── Замер ──
    @Volatile
    private var detections = 0
    @Volatile
    private var detectNs = 0L

    private var offered = 0L

    /** Отладка поиска лица: метка `files/filters/debug` — первый прямой кадр сохраняется картинкой рядом с ней. */
    private var dumped = false
    private var vnn: Vnn? = null
    private var vnnFailed = false

    /**
     * Свободен ли поиск: свободен — [read] кладёт прямой кадр в буфер (на потоке камеры, с контекстом OpenGL), и
     * поиск уходит на свой поток.
     */
    fun offer(read: (width: Int, height: Int, sink: ByteBuffer) -> Unit) {
        // Не чаще раза в [EVERY] кадров: чтение кадра с видеокарты останавливает её конвейер (замер Samsung A40:
        // поиск почти на каждом кадре — 17 к/с и 28 мс на кадр 1280×720).
        if (++offered % EVERY != 0L) return
        if (!busy.compareAndSet(false, true)) return
        try {
            read(WIDTH, HEIGHT, pixels)
        } catch (e: Throwable) {
            busy.set(false)
            throw e
        }
        if (!dumped) {
            dumped = true
            val mark = File(context.filesDir, "filters/debug")
            if (mark.exists()) runCatching { dump(File(mark.parentFile, "upright.png")) }
        }
        worker.execute {
            try {
                detect()
            } catch (e: Throwable) {
                Journal.trouble(LogCode.CALL, "фильтры: поиск лица упал", "причина" to (e.message ?: e::class.simpleName))
            } finally {
                busy.set(false)
            }
        }
    }

    private fun dump(file: File) {
        val bitmap = android.graphics.Bitmap.createBitmap(WIDTH, HEIGHT, android.graphics.Bitmap.Config.ARGB_8888)
        pixels.rewind()
        bitmap.copyPixelsFromBuffer(pixels)
        pixels.rewind()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        Journal.note(LogCode.CALL, "фильтры: отладка — прямой кадр сохранён", "файл" to file.name)
    }

    /** Точки для шейдеров: текстурные координаты буфера (низ — ноль), 111 × 2. `null` — лица нет. */
    fun points(rotation: Int): FloatArray? {
        val p = upright ?: return null
        if (SystemClock.uptimeMillis() - seenAt > STALE_MS) return null
        val out = FloatArray(p.size)
        for (i in 0 until p.size / 2) {
            val (bx, by) = FacePoints.uprightToBuffer(p[i * 2], p[i * 2 + 1], rotation)
            out[i * 2] = bx
            out[i * 2 + 1] = 1f - by
        }
        return out
    }

    /** Доля силы помады на этот кадр: 1 — как на ползунке, меньше — рот движется. */
    fun lipFade(): Float = fade.value(SystemClock.uptimeMillis())

    /** Для замера в журнале: сколько раз искали и сколько это стоило. */
    fun stats(): Array<Pair<String, Any?>> {
        val n = detections
        val result = arrayOf<Pair<String, Any?>>(
            "поисков лица" to n,
            "мс на поиск" to if (n > 0) (detectNs / n / 10_000) / 100.0 else 0.0,
            "лицо" to (upright != null && SystemClock.uptimeMillis() - seenAt <= STALE_MS),
        )
        detections = 0
        detectNs = 0
        return result
    }

    /**
     * Отладка поиска лица: `files/filters/face.png` — искать на этой картинке вместо кадра камеры (проверить VNN на
     * телефоне, когда перед камерой нет человека). Нет файла — `null`.
     */
    private val debugFace: ByteArray? by lazy {
        runCatching {
            val file = File(context.filesDir, "filters/face.png")
            if (!file.exists()) return@runCatching null
            val decoded = android.graphics.BitmapFactory.decodeFile(file.absolutePath) ?: return@runCatching null
            val scaled = android.graphics.Bitmap.createScaledBitmap(decoded, WIDTH, HEIGHT, true)
            val out = ByteBuffer.allocate(WIDTH * HEIGHT * 4)
            scaled.copyPixelsToBuffer(out)
            Journal.note(LogCode.CALL, "фильтры: отладка — лицо ищется на картинке face.png")
            out.array()
        }.getOrNull()
    }

    private fun detect() {
        val started = System.nanoTime()
        debugFace?.let { pixels.rewind(); pixels.put(it) }
        pixels.rewind()
        val raw = vnn()
        detections++
        detectNs += System.nanoTime() - started
        val now = raw != null
        if (now != found) {
            found = now
            Journal.note(LogCode.CALL, if (now) "фильтры: лицо найдено" else "фильтры: лицо потеряно")
        }
        if (raw == null) {
            lastRaw = null
            return
        }
        lastRaw?.let { fade.onDetection(LipFade.motion(it, raw, WIDTH, HEIGHT), SystemClock.uptimeMillis()) }
        lastRaw = raw
        val was = upright
        upright = if (was == null || jumped(was, raw)) raw else FacePoints.follow(was, raw)
        seenAt = SystemClock.uptimeMillis()
    }

    /** Лицо прыгнуло (повернули камеру, сменился человек) — сглаживать не с чем. */
    private fun jumped(a: FloatArray, b: FloatArray): Boolean {
        val dx = a[16 * 2] - b[16 * 2]
        val dy = a[16 * 2 + 1] - b[16 * 2 + 1]
        return dx * dx + dy * dy > 0.01f
    }

    private fun vnn(): FloatArray? {
        if (vnnFailed) return null
        val engine = vnn ?: Vnn.open(context)?.also {
            vnn = it
            Journal.note(LogCode.CALL, "фильтры: VNN запущен")
        } ?: run {
            vnnFailed = true
            Journal.trouble(LogCode.CALL, "фильтры: VNN недоступен — фильтры лица не рисуются", "архитектура" to Build.SUPPORTED_ABIS.joinToString())
            return null
        }
        return engine.detect(pixels, WIDTH, HEIGHT)?.let { FacePoints.fromVnn(it) }
    }

    fun close() {
        worker.execute {
            vnn?.close()
            vnn = null
            upright = null
            found = null
            lastRaw = null
            fade.reset()
        }
    }

    companion object {
        /** Прямой кадр для поиска: портрет, как держат телефон. */
        const val WIDTH = 192
        const val HEIGHT = 256

        /** Искать лицо раз в столько кадров. */
        private const val EVERY = 3L

        /** Точки старше — лица нет: фильтры лица не рисуются на пустом месте. */
        private const val STALE_MS = 600L
    }
}

/**
 * VNN (JOYY, MIT по заголовкам `vnn_*.h`) — через JNA, без своей NDK-сборки. Библиотеки — `jniLibs/arm64-v8a`,
 * модель — `assets/filters/face_mobile.vnnmodel` (копируется в файлы приложения: VNN читает путь, а не поток).
 *
 * Структуры — руками, по `vnn_common.h`: `VNN_Image` на 64-битной Android — 56 байт (шесть int, прямоугольник из
 * четырёх float, два указателя), `VNN_FaceFrameDataArr` — 4 + 5 × 3408 байт.
 */
internal class Vnn private constructor(private val lib: Api, private val handle: Int) {
    @Suppress("FunctionName")
    interface Api : Library {
        fun VNN_Create_Face(handle: IntByReference, argc: Int, argv: Pointer): Int
        fun VNN_Destroy_Face(handle: IntByReference): Int
        fun VNN_Apply_Face_CPU(handle: Int, input: Pointer, output: Pointer): Int
        fun VNN_Set_Face_Attr(handle: Int, name: String, value: Pointer): Int
    }

    private val image = Memory(56)
    private val output = Memory(4L + 5 * FACE_BYTES)

    /** 106 точек лица (доли, y вниз) или `null`. */
    fun detect(rgba: ByteBuffer, width: Int, height: Int): FloatArray? {
        image.clear()
        image.setInt(0, 0) // ori_fmt: DEFAULT — кадр уже прямой
        image.setInt(4, 6) // pix_fmt: RGBA8888
        image.setInt(8, 0) // mode_fmt: VIDEO
        image.setInt(12, width)
        image.setInt(16, height)
        image.setInt(20, 4)
        image.setPointer(40, Native.getDirectBufferPointer(rgba))
        image.setPointer(48, Pointer.NULL)
        output.clear()
        if (lib.VNN_Apply_Face_CPU(handle, image, output) != 0) return null
        if (output.getInt(0) <= 0) return null
        val count = output.getInt(4 + 28)
        if (count < 106) return null
        val raw = FloatArray(212) { i -> output.getFloat(4L + 32 + i * 4L) }
        // gpupixel отдаёт их шейдерам как доли кадра; если библиотека вернула пиксели — привести к долям.
        if (raw.any { it > 1.5f }) for (i in 0 until 106) {
            raw[i * 2] /= width
            raw[i * 2 + 1] /= height
        }
        return raw
    }

    fun close() {
        runCatching { lib.VNN_Destroy_Face(IntByReference(handle)) }
    }

    companion object {
        private const val FACE_BYTES = 3408L

        fun open(context: Context): Vnn? = runCatching {
            if ("arm64-v8a" !in Build.SUPPORTED_ABIS) return null
            System.loadLibrary("vnn_core")
            System.loadLibrary("vnn_kit")
            val lib = Native.load("vnn_face", Api::class.java)
            val model = File(context.filesDir, "filters/face_mobile.vnnmodel")
            if (!model.exists()) {
                model.parentFile?.mkdirs()
                context.assets.open("filters/face_mobile.vnnmodel").use { input -> model.outputStream().use { input.copyTo(it) } }
            }
            val path = Memory(model.absolutePath.toByteArray().size + 1L).apply { setString(0, model.absolutePath) }
            val argv = Memory(Native.POINTER_SIZE.toLong()).apply { setPointer(0, path) }
            val handle = IntByReference()
            val result = lib.VNN_Create_Face(handle, 1, argv)
            if (result != 0 || handle.value == 0) {
                Journal.trouble(LogCode.CALL, "фильтры: VNN не создал распознавание лица", "код" to result)
                return null
            }
            // 106 точек, а не 278: шейдеры gpupixel ждут их.
            val no = Memory(4).apply { setInt(0, 0) }
            lib.VNN_Set_Face_Attr(handle.value, "_use_278pts", no)
            Vnn(lib, handle.value)
        }.onFailure {
            Journal.trouble(LogCode.CALL, "фильтры: VNN не загрузился", "причина" to (it.message ?: it::class.simpleName))
        }.getOrNull()
    }
}
