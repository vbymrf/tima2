package io.tima.core.call

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
 * Точки лица на ПК (ПЛАН-(ФЗ) ФЗ15): VNN под Windows x64 через JNA, как на телефоне (`FaceWatch`), но модель — `face_pc`
 * и кадр для поиска — с пропорцией камеры ПК (она лежачая). Библиотеки и модель лежат в каталоге ресурсов приложения
 * (`callNatives`, коммит pixpark/gpupixel 1.2.x), рядом с `livekit_ffi.dll`.
 *
 * Ищется раз в [EVERY] кадров на своём потоке; между поисками точки сглаживаются по скорости (`FacePoints.follow`);
 * старше [STALE_MS] — лица нет.
 */
internal class DesktopFaces(private val dir: File?) {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "tima-faces-pc").apply { isDaemon = true } }
    private val busy = AtomicBoolean(false)
    private var pixels: ByteBuffer = ByteBuffer.allocateDirect(4)
    private var width = 0
    private var height = 0

    @Volatile
    private var upright: FloatArray? = null

    @Volatile
    private var seenAt = 0L
    private var found: Boolean? = null
    private var offered = 0L
    private var vnn: VnnPc? = null
    private var failed = false

    @Volatile
    private var detections = 0

    @Volatile
    private var detectNs = 0L

    /** Размер кадра поиска при кадре [w]×[h]: ширина [SMALL], высота — по пропорции, чётная. */
    fun size(w: Int, h: Int): Pair<Int, Int> = SMALL to ((SMALL.toLong() * h / w).toInt() and 1.inv()).coerceAtLeast(2)

    /** Свободен ли поиск — тогда [read] кладёт маленький кадр RGBA в буфер (на потоке OpenGL). */
    fun offer(frameW: Int, frameH: Int, read: (width: Int, height: Int, sink: ByteBuffer) -> Unit) {
        if (++offered % EVERY != 0L) return
        if (failed) return
        if (!busy.compareAndSet(false, true)) return
        val (w, h) = size(frameW, frameH)
        try {
            if (pixels.capacity() != w * h * 4) pixels = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            width = w
            height = h
            read(w, h, pixels)
        } catch (e: Throwable) {
            busy.set(false)
            throw e
        }
        worker.execute {
            try {
                detect()
            } catch (e: Throwable) {
                Journal.trouble(LogCode.CALL, "фильтры ПК: поиск лица упал", "причина" to (e.message ?: e::class.simpleName))
            } finally {
                busy.set(false)
            }
        }
    }

    /** Точки для шейдеров (доли кадра, верх — ноль: на ПК кадр хранится сверху вниз), 111 × 2. `null` — лица нет. */
    fun points(): FloatArray? {
        val p = upright ?: return null
        if (System.currentTimeMillis() - seenAt > STALE_MS) return null
        return p
    }

    fun stats(): Array<Pair<String, Any?>> {
        val n = detections
        val result = arrayOf<Pair<String, Any?>>(
            "поисков лица" to n,
            "мс на поиск" to if (n > 0) (detectNs / n / 10_000) / 100.0 else 0.0,
            "лицо" to (points() != null),
        )
        detections = 0
        detectNs = 0
        return result
    }

    private fun detect() {
        val started = System.nanoTime()
        pixels.rewind()
        val engine = vnn ?: VnnPc.open(dir)?.also {
            vnn = it
            Journal.note(LogCode.CALL, "фильтры ПК: VNN запущен")
        } ?: run {
            failed = true
            Journal.trouble(LogCode.CALL, "фильтры ПК: VNN недоступен — фильтры лица не рисуются")
            return
        }
        val raw = engine.detect(pixels, width, height)?.let { FacePoints.fromVnn(it) }
        detections++
        detectNs += System.nanoTime() - started
        val now = raw != null
        if (now != found) {
            found = now
            Journal.note(LogCode.CALL, if (now) "фильтры ПК: лицо найдено" else "фильтры ПК: лицо потеряно")
        }
        if (raw == null) return
        val was = upright
        upright = if (was == null || jumped(was, raw)) raw else FacePoints.follow(was, raw)
        seenAt = System.currentTimeMillis()
    }

    private fun jumped(a: FloatArray, b: FloatArray): Boolean {
        val dx = a[16 * 2] - b[16 * 2]
        val dy = a[16 * 2 + 1] - b[16 * 2 + 1]
        return dx * dx + dy * dy > 0.01f
    }

    fun close() {
        worker.execute {
            vnn?.close()
            vnn = null
            upright = null
            found = null
        }
    }

    companion object {
        /** Ширина кадра поиска. */
        const val SMALL = 320

        private const val EVERY = 3L
        private const val STALE_MS = 600L
    }
}

/**
 * VNN под Windows x64 — те же структуры, что на телефоне (`Vnn` в androidMain): `VNN_Image` — 56 байт, выход —
 * 4 + 5 × 3408 байт. Перед VNN загружается C-рантайм Visual C++ из JDK приложения: VNN его требует, а на ПК без
 * распространяемого пакета Microsoft его может не быть в системе.
 */
internal class VnnPc private constructor(private val lib: Api, private val handle: Int) {
    @Suppress("FunctionName")
    interface Api : Library {
        fun VNN_Create_Face(handle: IntByReference, argc: Int, argv: Pointer): Int
        fun VNN_Destroy_Face(handle: IntByReference): Int
        fun VNN_Apply_Face_CPU(handle: Int, input: Pointer, output: Pointer): Int
        fun VNN_Set_Face_Attr(handle: Int, name: String, value: Pointer): Int
    }

    private val image = Memory(56)
    private val output = Memory(4L + 5 * FACE_BYTES)

    fun detect(rgba: ByteBuffer, width: Int, height: Int): FloatArray? {
        image.clear()
        image.setInt(0, 0)
        image.setInt(4, 6)
        image.setInt(8, 0)
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

        fun open(dir: File?): VnnPc? = runCatching {
            val home = dir ?: return null
            val face = File(home, "vnn_face.dll")
            val model = File(home, "face_pc.vnnmodel")
            if (!face.isFile || !model.isFile) {
                Journal.trouble(LogCode.CALL, "фильтры ПК: нет VNN в каталоге приложения", "каталог" to home.absolutePath)
                return null
            }
            // C-рантайм — из JDK приложения, до VNN: тогда Windows берёт уже загруженный.
            val bin = File(System.getProperty("java.home"), "bin")
            for (name in listOf("vcruntime140.dll", "vcruntime140_1.dll", "msvcp140.dll")) {
                File(bin, name).takeIf { it.isFile }?.let { runCatching { System.load(it.absolutePath) } }
            }
            System.load(File(home, "vnn_core.dll").absolutePath)
            System.load(File(home, "vnn_kit.dll").absolutePath)
            val lib = Native.load(face.absolutePath, Api::class.java)
            val path = Memory(model.absolutePath.toByteArray().size + 1L).apply { setString(0, model.absolutePath) }
            val argv = Memory(Native.POINTER_SIZE.toLong()).apply { setPointer(0, path) }
            val handle = IntByReference()
            val result = lib.VNN_Create_Face(handle, 1, argv)
            if (result != 0 || handle.value == 0) {
                Journal.trouble(LogCode.CALL, "фильтры ПК: VNN не создал распознавание лица", "код" to result)
                return null
            }
            val no = Memory(4).apply { setInt(0, 0) }
            lib.VNN_Set_Face_Attr(handle.value, "_use_278pts", no)
            VnnPc(lib, handle.value)
        }.onFailure {
            Journal.trouble(LogCode.CALL, "фильтры ПК: VNN не загрузился", "причина" to (it.message ?: it::class.simpleName))
        }.getOrNull()
    }
}
