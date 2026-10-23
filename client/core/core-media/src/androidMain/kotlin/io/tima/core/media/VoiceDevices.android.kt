package io.tima.core.media

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder

/**
 * Микрофон и колонки голосовых на Android (ПЛАН-(ВМ) ВМ2, ВМ4): `AudioRecord` и `AudioTrack` в
 * потоковом режиме. Разрешение на микрофон спрашивает экран до записи: без него `AudioRecord` не
 * встаёт, и здесь отвечаем `null`.
 */
actual object VoiceDevices {
    @SuppressLint("MissingPermission")
    actual fun microphone(name: String?): PcmSource? {
        for (rate in intArrayOf(48_000, 16_000)) {
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) continue
            val rec = runCatching {
                AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate / 5 * 2))
            }.getOrNull() ?: continue
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                continue
            }
            if (runCatching { rec.startRecording() }.isFailure || rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                rec.release()
                continue
            }
            return object : PcmSource {
                override val rate = rate
                override fun read(buf: ShortArray, offset: Int, n: Int): Int {
                    val got = rec.read(buf, offset, n)
                    return if (got < 0) -1 else got
                }
                override fun close() {
                    runCatching { rec.stop() }
                    rec.release()
                }
            }
        }
        return null
    }

    actual fun speaker(): PcmSink? = runCatching {
        val rate = 48_000
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(min, rate / 5 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()
        object : PcmSink {
            override val rate = rate
            override fun write(buf: ShortArray, n: Int) {
                var at = 0
                while (at < n) {
                    val w = track.write(buf, at, n - at)
                    if (w <= 0) return
                    at += w
                }
            }
            override fun flush() {
                runCatching {
                    track.pause()
                    track.flush()
                    track.play()
                }
            }
            override fun close() {
                runCatching { track.stop() }
                track.release()
            }
        }
    }.getOrNull()

    actual fun encoder(rate: Int): VoiceEncoder? = OpusConcentus.encoder(rate)

    actual fun decoder(): VoiceDecoder? = OpusConcentus.decoder()
}
