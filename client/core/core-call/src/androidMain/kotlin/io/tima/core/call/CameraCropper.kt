package io.tima.core.call

import android.content.Intent
import io.livekit.android.room.track.LocalVideoTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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
        val r = c.region(buffer.width, buffer.height, frame.rotation)
        val cut = buffer.cropAndScale(r.left, r.top, r.width, r.height, buffer.width, buffer.height)
        val next = VideoFrame(cut, frame.rotation, frame.timestampNs)
        cropNs += System.nanoTime() - started
        try {
            out.onFrame(next)
        } finally {
            next.release()
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
