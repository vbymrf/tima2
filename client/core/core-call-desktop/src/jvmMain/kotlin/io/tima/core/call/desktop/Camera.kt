package io.tima.core.call.desktop

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import java.io.File

/**
 * C-интерфейс openpnp-capture v0.0.30 (`include/openpnp-capture.h`) — ровно то, что нужно
 * звонку: найти камеру, выбрать формат, открыть, брать кадры.
 *
 * `uint32_t` у JNA — `Int`: значения здесь малые, знаковость не мешает.
 */
@Suppress("FunctionName")
internal interface CaptureNative : Library {
    fun Cap_createContext(): Pointer?
    fun Cap_releaseContext(ctx: Pointer): Int
    fun Cap_getDeviceCount(ctx: Pointer): Int
    fun Cap_getDeviceName(ctx: Pointer, index: Int): String?
    fun Cap_getNumFormats(ctx: Pointer, index: Int): Int
    fun Cap_getFormatInfo(ctx: Pointer, index: Int, id: Int, info: CapFormatInfo): Int
    fun Cap_openStream(ctx: Pointer, index: Int, formatId: Int): Int
    fun Cap_closeStream(ctx: Pointer, stream: Int): Int
    fun Cap_hasNewFrame(ctx: Pointer, stream: Int): Int
    fun Cap_captureFrame(ctx: Pointer, stream: Int, buffer: Pointer, bytes: Int): Int
}

/** `CapFormatInfo` из заголовка: пять `uint32_t` подряд. */
@Structure.FieldOrder("width", "height", "fourcc", "fps", "bpp")
class CapFormatInfo : Structure() {
    @JvmField var width: Int = 0
    @JvmField var height: Int = 0
    @JvmField var fourcc: Int = 0
    @JvmField var fps: Int = 0
    @JvmField var bpp: Int = 0
}

/**
 * Камера ПК — открытый поток кадров RGB24.
 *
 * ── ПОРЯДОК БАЙТОВ СВЕРЕН, А НЕ УГАДАН ──────────────────────────────────────
 *
 * openpnp-capture на Windows получает от DirectShow перевёрнутые кадры BGR и сама
 * переворачивает их в R,G,B сверху вниз (`win/platformstream.cpp`). `livekit-ffi` свой
 * `RGB24` переводит через libyuv `RAW` — это тоже R,G,B (`colorcvt/cvtimpl.rs`). Поэтому
 * кадр уходит в движок как есть. Перепутай здесь — у собеседника лицо стало бы синим, и
 * на ПК без камеры (этот стенд) этого не увидел бы никто.
 *
 * Кадр копируется в [frame] — буфер вне кучи JVM: его адрес уходит в `livekit-ffi`, и
 * сборщик мусора не должен его двигать.
 */
internal class Camera private constructor(
    private val lib: CaptureNative,
    private val ctx: Pointer,
    private val stream: Int,
    val name: String,
    val width: Int,
    val height: Int,
    val fps: Int,
) {
    val frame = Memory(width.toLong() * height * 3)

    /** Взять новый кадр, если он есть. `false` — нового нет. */
    fun grab(): Boolean {
        if (lib.Cap_hasNewFrame(ctx, stream) == 0) return false
        return lib.Cap_captureFrame(ctx, stream, frame, frame.size().toInt()) == CAP_OK
    }

    /** Закрыть поток — **и погасить лампочку камеры**: человек верит ей, а не экрану. */
    fun close() {
        lib.Cap_closeStream(ctx, stream)
        lib.Cap_releaseContext(ctx)
    }

    companion object {
        private const val LIBRARY = "openpnp-capture.dll"
        private const val CAP_OK = 0

        @Volatile
        private var native: CaptureNative? = null

        /** Итог открытия: камера или причина словами — для журнала и для надписи. */
        sealed interface Opened {
            class Ready(val camera: Camera) : Opened
            class Failed(val why: String) : Opened
        }

        /**
         * Открыть первую камеру в формате, ближайшем к просимому.
         *
         * Первую — потому что их почти всегда одна; выбор камеры из нескольких — в
         * «Разрешениях» ПК (ПК3), когда появится.
         */
        fun open(wantWidth: Int, wantHeight: Int, wantFps: Int): Opened {
            val lib = load() ?: return Opened.Failed("нет $LIBRARY в каталоге ресурсов приложения")
            val ctx = lib.Cap_createContext() ?: return Opened.Failed("камера: контекст не создался")
            val count = lib.Cap_getDeviceCount(ctx)
            if (count <= 0) {
                lib.Cap_releaseContext(ctx)
                return Opened.Failed("на ПК нет камеры")
            }
            val name = lib.Cap_getDeviceName(ctx, 0).orEmpty()
            val formats = (0 until lib.Cap_getNumFormats(ctx, 0)).mapNotNull { id ->
                val info = CapFormatInfo()
                if (lib.Cap_getFormatInfo(ctx, 0, id, info) == CAP_OK) id to info else null
            }
            // Ближайший по площади к просимому, при равенстве — с кадрами почаще. Больше
            // просимого не берём без нужды: кадр крупнее съест процессор на кодировании,
            // а собеседнику уедет всё равно ужатым.
            val want = wantWidth * wantHeight
            val chosen = formats
                .sortedWith(compareBy({ kotlin.math.abs(it.second.width * it.second.height - want) }, { -it.second.fps }))
                .firstOrNull()
            if (chosen == null) {
                lib.Cap_releaseContext(ctx)
                return Opened.Failed("камера «$name» не назвала ни одного формата")
            }
            val stream = lib.Cap_openStream(ctx, 0, chosen.first)
            if (stream < 0) {
                lib.Cap_releaseContext(ctx)
                // Чаще всего — камеру держит другая программа или Windows не дала доступа
                // («Параметры → Конфиденциальность → Камера»).
                return Opened.Failed("камера «$name» не открылась: занята или нет доступа")
            }
            val info = chosen.second
            return Opened.Ready(Camera(lib, ctx, stream, name, info.width, info.height, info.fps.coerceAtLeast(1)))
        }

        @Synchronized
        private fun load(): CaptureNative? {
            native?.let { return it }
            val dir = System.getProperty("compose.application.resources.dir") ?: return null
            val file = File(dir, LIBRARY).takeIf { it.isFile } ?: return null
            return runCatching { Native.load(file.absolutePath, CaptureNative::class.java) }
                .getOrNull()
                ?.also { native = it }
        }
    }
}
