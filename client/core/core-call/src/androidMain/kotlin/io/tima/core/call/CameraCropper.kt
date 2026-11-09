package io.tima.core.call

import android.content.Intent
import io.livekit.android.room.track.LocalVideoTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import livekit.org.webrtc.JavaI420Buffer
import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.VideoProcessor
import livekit.org.webrtc.VideoSink
import livekit.org.webrtc.VideoSource
import kotlin.coroutines.resume

/**
 * Обрезка своего кадра перед отправкой — режим адаптации изображения (ПЛАН-(ЗН) ЗН5).
 *
 * ── ПОЧЕМУ ОБРАБОТЧИКОМ ИСТОЧНИКА, А НЕ `restartTrack` ─────────────────────────
 *
 * LiveKit даёт поставить обработчик кадров только при создании дорожки или через
 * `restartTrack`, а `restartTrack` в 2.28.2 роняет приложение (отчёт 637S,
 * `БЕДЫ/2026-10-16-камера-открыта-заново-в-начале-звонка.md`). У источника WebRTC свой
 * `setVideoProcessor` — он ставится на живую дорожку без перезапуска съёмки. Источник лежит в
 * закрытом поле дорожки, отсюда отражение — тем же приёмом, что `VideoSender`.
 *
 * Кадр режется и растягивается до прежнего размера: у собеседника не меняется размер картинки,
 * а кодер не перестраивается на каждом нажатии «+».
 */
internal class CameraCropper : VideoProcessor {
    @Volatile
    var crop: CameraCrop = CameraCrop.FULL

    @Volatile
    private var sink: VideoSink? = null

    /** Замер (ЗН5): сколько кадров прошло и сколько времени ушло на обрезку — с прошлого вопроса. */
    private var frames = 0L
    private var cropNs = 0L
    private var since = System.nanoTime()

    override fun setSink(sink: VideoSink?) {
        this.sink = sink
    }

    override fun onCapturerStarted(success: Boolean) = Unit

    override fun onCapturerStopped() = Unit

    override fun onFrameCaptured(frame: VideoFrame) {
        val out = sink ?: return
        frames++
        val c = crop
        if (c.full) {
            out.onFrame(frame)
            return
        }
        val started = System.nanoTime()
        val buffer = frame.buffer
        val cut = if (c.beyond) {
            framed(buffer, c.cut(buffer.width, buffer.height, frame.rotation))
        } else {
            val r = c.region(buffer.width, buffer.height, frame.rotation)
            buffer.cropAndScale(r.left, r.top, r.width, r.height, buffer.width, buffer.height)
        }
        val next = VideoFrame(cut, frame.rotation, frame.timestampNs)
        cropNs += System.nanoTime() - started
        try {
            out.onFrame(next)
        } finally {
            next.release()
        }
    }

    /**
     * Видимая часть ушла за край кадра (заказчик 2026-11-10, вариант «а»): кадр того же размера, чёрный, и в нём на
     * своём месте — то, что от кадра камеры ещё видно. Обрезку и растяжение делает WebRTC, сюда приходит готовая часть.
     */
    private fun framed(buffer: VideoFrame.Buffer, cut: CameraCrop.Cut): VideoFrame.Buffer {
        val w = buffer.width
        val h = buffer.height
        val out = JavaI420Buffer.allocate(w, h)
        blank(out)
        val s = cut.src
        val d = cut.dst
        val part = buffer.cropAndScale(s.left, s.top, s.width, s.height, d.width, d.height)
        val yuv = part.toI420()
        part.release()
        if (yuv != null) {
            place(yuv.dataY, yuv.strideY, out.dataY, out.strideY, d.left, d.top, d.width, d.height)
            place(yuv.dataU, yuv.strideU, out.dataU, out.strideU, d.left / 2, d.top / 2, d.width / 2, d.height / 2)
            place(yuv.dataV, yuv.strideV, out.dataV, out.strideV, d.left / 2, d.top / 2, d.width / 2, d.height / 2)
            yuv.release()
        }
        return out
    }

    /** Чёрное: яркость 16, цвет 128 — чёрный в YUV узкого размаха, как пишет камера. */
    private var blackY = ByteArray(0)
    private var blackUv = ByteArray(0)

    private fun blank(out: JavaI420Buffer) {
        val y = out.dataY
        val u = out.dataU
        val v = out.dataV
        if (blackY.size != y.capacity()) blackY = ByteArray(y.capacity()) { 16 }
        if (blackUv.size != u.capacity()) blackUv = ByteArray(u.capacity()) { -128 }
        y.duplicate().apply { clear() }.put(blackY)
        u.duplicate().apply { clear() }.put(blackUv, 0, minOf(blackUv.size, u.capacity()))
        v.duplicate().apply { clear() }.put(blackUv, 0, minOf(blackUv.size, v.capacity()))
    }

    private fun place(src: java.nio.ByteBuffer, srcStride: Int, dst: java.nio.ByteBuffer, dstStride: Int, x: Int, y: Int, w: Int, h: Int) {
        val from = src.duplicate()
        val to = dst.duplicate()
        for (row in 0 until h) {
            val start = row * srcStride
            if (start + w > from.capacity()) break
            from.clear()
            from.limit(start + w)
            from.position(start)
            val at = (y + row) * dstStride + x
            if (at + w > to.capacity()) break
            to.clear()
            to.position(at)
            to.put(from)
        }
    }

    /** Кадров в секунду и мс обрезки на кадр с прошлого вызова — для журнала. */
    fun measure(): Array<Pair<String, Any?>> {
        val now = System.nanoTime()
        val seconds = (now - since) / 1e9
        val fps = if (seconds > 0) (frames / seconds * 10).toLong() / 10.0 else 0.0
        val perFrame = if (frames > 0) (cropNs / frames / 10_000) / 100.0 else 0.0
        frames = 0
        cropNs = 0
        since = now
        return arrayOf("к/с" to fps, "мс обрезки на кадр" to perFrame)
    }

    companion object {
        private val sourceField by lazy {
            runCatching { LocalVideoTrack::class.java.getDeclaredField("source").apply { isAccessible = true } }.getOrNull()
        }

        fun sourceOf(track: LocalVideoTrack): VideoSource? = runCatching { sourceField?.get(track) as? VideoSource }.getOrNull()
    }
}

/**
 * Разрешение Android на запись экрана (ПЛАН-(ЗН) ЗН6). Спросить его может только окно, а
 * просит движок, — мост: окно при создании ставит [ask], движок ждёт ответ в [permission].
 */
object ScreenAsk {
    /** Показать системный вопрос и отдать ответ: данные разрешения или `null` — отказали. */
    @Volatile
    var ask: ((onResult: (Intent?) -> Unit) -> Unit)? = null

    suspend fun permission(): Intent? = withContext(Dispatchers.Main) {
        val show = ask ?: return@withContext null
        suspendCancellableCoroutine { wait ->
            var answered = false
            show { data ->
                if (!answered) {
                    answered = true
                    if (wait.isActive) wait.resume(data)
                }
            }
        }
    }
}
