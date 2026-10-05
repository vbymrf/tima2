package io.tima.core.call

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.VideoSink
import java.io.ByteArrayOutputStream

/**
 * Кадр собеседника в JPEG — для отчёта о проблеме (ПЛАН-(В)-ВИДЕО.md В8).
 *
 * Берётся **следующий** кадр дорожки: WebRTC кадров не хранит, а тот, что на экране, уже
 * отдан отрисовщику. Ждём не дольше [FRAME_WAIT_MS] — видео могло встать, ради того и
 * жалуются. Кадр — в том виде, в каком его раскодировал телефон: полосы раскодировщика на
 * нём видны, а поверхность отрисовки ни при чём.
 */
internal suspend fun grabFrame(track: VideoTrack): ByteArray? {
    val got = CompletableDeferred<ByteArray?>()
    val sink = VideoSink { frame ->
        if (got.isCompleted) return@VideoSink
        got.complete(runCatching { jpegOf(frame) }.getOrNull())
    }
    track.addRenderer(sink)
    return try {
        withTimeoutOrNull(FRAME_WAIT_MS) { got.await() }
    } finally {
        track.removeRenderer(sink)
    }
}

/** Кадр WebRTC → JPEG: через I420 и NV21, который Android умеет сжимать сам. */
private fun jpegOf(frame: VideoFrame): ByteArray {
    val i420 = frame.buffer.toI420() ?: error("кадр не перевёлся в I420")
    try {
        // NV21 требует чётных сторон; нечётный край — одна строка, её не жаль.
        val w = i420.width and 1.inv()
        val h = i420.height and 1.inv()
        val nv21 = ByteArray(w * h * 3 / 2)
        val y = i420.dataY
        val strideY = i420.strideY
        for (row in 0 until h) {
            for (col in 0 until w) nv21[row * w + col] = y.get(row * strideY + col)
        }
        val u = i420.dataU
        val v = i420.dataV
        var at = w * h
        for (row in 0 until h / 2) {
            for (col in 0 until w / 2) {
                nv21[at++] = v.get(row * i420.strideV + col)
                nv21[at++] = u.get(row * i420.strideU + col)
            }
        }
        val out = ByteArrayOutputStream()
        YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), JPEG_QUALITY, out)
        val jpeg = out.toByteArray()
        if (frame.rotation == 0) return jpeg
        // Телефон собеседника снимал боком — повернуть, как это делает экран.
        val picture = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
        val turned = Bitmap.createBitmap(
            picture, 0, 0, picture.width, picture.height,
            Matrix().apply { postRotate(frame.rotation.toFloat()) }, true,
        )
        return ByteArrayOutputStream().also { turned.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray()
    } finally {
        i420.release()
    }
}

/** Сколько ждём кадр. Секунда: у живого видео это десятки кадров. */
private const val FRAME_WAIT_MS = 1_000L

private const val JPEG_QUALITY = 90
