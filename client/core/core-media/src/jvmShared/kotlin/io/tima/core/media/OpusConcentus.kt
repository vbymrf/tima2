package io.tima.core.media

import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusEncoder
import io.github.jaredmdobson.concentus.OpusSignal

/**
 * Opus для голосовых на Android и ПК — Concentus, перенос libopus на чистую Java (лицензия BSD,
 * как у самого Opus). Встроенный кодер Opus у Android — только с 10-й версии, а у нас 8.0
 * (ПЛАН-(ВМ) §2.1); на ПК его нет вовсе. Один кодек на обеих платформах — один и тот же звук.
 *
 * Файл общий для `androidMain` и `jvmMain` (каталог `jvmShared`, `core-media/build.gradle.kts`).
 */
internal object OpusConcentus {
    fun encoder(rate: Int): VoiceEncoder? = runCatching {
        val enc = OpusEncoder(rate, 1, OpusApplication.OPUS_APPLICATION_VOIP).apply {
            setBitrate(VOICE_BITRATE)
            setSignalType(OpusSignal.OPUS_SIGNAL_VOICE)
            setUseVBR(true)
            setComplexity(8)
        }
        val frame = rate / 50 // 20 мс
        val lookahead48 = enc.lookahead * 48_000 / rate
        object : VoiceEncoder {
            override val rate = rate
            override val frame = frame
            override val preSkip = lookahead48
            private val out = ByteArray(1500)
            override fun encode(pcm: ShortArray): ByteArray {
                val n = enc.encode(pcm, 0, frame, out, 0, out.size)
                return out.copyOf(n)
            }
        }
    }.getOrNull()

    fun decoder(): VoiceDecoder? = runCatching {
        val dec = OpusDecoder(48_000, 1)
        object : VoiceDecoder {
            override fun decode(packet: ByteArray, out: ShortArray): Int =
                runCatching { dec.decode(packet, 0, packet.size, out, 0, out.size, false) }.getOrDefault(0)
        }
    }.getOrNull()
}
