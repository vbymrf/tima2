package io.tima.app.platform

import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

// Голосовые: запись AAC/MPEG-4 (m4a) — поддержана на всех API 26+ (Opus в MediaRecorder только с 29).
// Файл шифруется MediaCipher выше по стеку; сюда/на сервер попадает только ciphertext.
private var recorder: MediaRecorder? = null
private var recordFile: File? = null
private var recordStartedAt = 0L
private var player: MediaPlayer? = null

actual fun voiceRecordingSupported(): Boolean = true

actual suspend fun startVoiceRecording(): Boolean {
    if (recorder != null) return false
    if (!ensureCallPermissions(video = false)) return false // разрешение микрофона
    val ctx = AndroidAppContext.app
    val file = File(ctx.cacheDir, "voice-rec-${SystemClock.elapsedRealtimeNanos()}.m4a")
    @Suppress("DEPRECATION")
    val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(ctx) else MediaRecorder()
    return try {
        rec.setAudioSource(MediaRecorder.AudioSource.MIC)
        rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        rec.setAudioEncodingBitRate(64_000)
        rec.setAudioSamplingRate(44_100)
        rec.setOutputFile(file.absolutePath)
        rec.prepare()
        rec.start()
        recorder = rec
        recordFile = file
        recordStartedAt = SystemClock.elapsedRealtime()
        true
    } catch (e: Throwable) {
        runCatching { rec.release() }
        file.delete()
        false
    }
}

actual suspend fun stopVoiceRecording(): RecordedAudio? {
    val rec = recorder ?: return null
    val file = recordFile
    val durationMs = (SystemClock.elapsedRealtime() - recordStartedAt).toInt()
    recorder = null
    recordFile = null
    return try {
        rec.stop()
        rec.release()
        if (file == null || durationMs < 500) { // слишком коротко — не отправляем
            file?.delete()
            null
        } else {
            val bytes = file.readBytes()
            file.delete()
            RecordedAudio(bytes, "audio/mp4", durationMs)
        }
    } catch (e: Throwable) {
        runCatching { rec.release() }
        file?.delete()
        null
    }
}

actual fun cancelVoiceRecording() {
    val rec = recorder ?: return
    recorder = null
    runCatching { rec.stop() }
    runCatching { rec.release() }
    recordFile?.delete()
    recordFile = null
}

actual suspend fun playVoice(bytes: ByteArray, mime: String) {
    stopVoice()
    val ctx = AndroidAppContext.app
    val file = File(ctx.cacheDir, "voice-play.m4a")
    file.writeBytes(bytes)
    val mp = MediaPlayer()
    player = mp
    try {
        suspendCancellableCoroutine { cont ->
            mp.setOnCompletionListener { if (cont.isActive) cont.resume(Unit) }
            mp.setOnErrorListener { _, _, _ -> if (cont.isActive) cont.resume(Unit); true }
            cont.invokeOnCancellation { runCatching { mp.stop() } }
            mp.setDataSource(file.absolutePath)
            mp.prepare()
            mp.start()
        }
    } finally {
        runCatching { mp.release() }
        if (player === mp) player = null
        file.delete()
    }
}

actual fun stopVoice() {
    player?.let { mp -> runCatching { mp.stop() }; runCatching { mp.release() } }
    player = null
}
