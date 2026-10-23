package io.tima.core.media

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Line
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

/**
 * Микрофон и колонки голосовых на ПК (ПЛАН-(ВМ) ВМ2, ВМ4): Java Sound. Микрофон — тот, что выбран
 * для звонков (Р42): звонок знает его по идентификатору Windows, Java Sound — по имени, поэтому
 * имя ищется так же, как в проверке микрофона звонка (`MicCheck`).
 */
actual object VoiceDevices {
    actual fun microphone(name: String?): PcmSource? {
        for (rate in floatArrayOf(48_000f, 16_000f)) {
            val format = AudioFormat(rate, 16, 1, true, false)
            val info = DataLine.Info(TargetDataLine::class.java, format)
            val line = runCatching { (find(name, info) ?: AudioSystem.getLine(info)) as TargetDataLine }.getOrNull() ?: continue
            if (runCatching { line.open(format, (rate / 5).toInt() * 2); line.start() }.isFailure) {
                runCatching { line.close() }
                continue
            }
            return object : PcmSource {
                override val rate = rate.toInt()
                private var bytes = ByteArray(0)
                override fun read(buf: ShortArray, offset: Int, n: Int): Int {
                    if (bytes.size < n * 2) bytes = ByteArray(n * 2)
                    val got = line.read(bytes, 0, n * 2)
                    if (got < 0) return -1
                    for (i in 0 until got / 2) {
                        buf[offset + i] = ((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)).toShort()
                    }
                    return got / 2
                }
                override fun close() {
                    runCatching { line.stop() }
                    line.close()
                }
            }
        }
        return null
    }

    actual fun speaker(): PcmSink? = runCatching {
        val format = AudioFormat(48_000f, 16, 1, true, false)
        val info = DataLine.Info(SourceDataLine::class.java, format)
        val line = AudioSystem.getLine(info) as SourceDataLine
        line.open(format, 48_000 / 5 * 2)
        line.start()
        object : PcmSink {
            override val rate = 48_000
            private var bytes = ByteArray(0)
            override fun write(buf: ShortArray, n: Int) {
                if (bytes.size < n * 2) bytes = ByteArray(n * 2)
                for (i in 0 until n) {
                    val v = buf[i].toInt()
                    bytes[2 * i] = v.toByte()
                    bytes[2 * i + 1] = (v shr 8).toByte()
                }
                line.write(bytes, 0, n * 2)
            }
            override fun flush() {
                line.flush()
            }
            override fun close() {
                runCatching { line.stop() }
                line.close()
            }
        }
    }.getOrNull()

    actual fun encoder(rate: Int): VoiceEncoder? = OpusConcentus.encoder(rate)

    actual fun decoder(): VoiceDecoder? = OpusConcentus.decoder()

    /** Устройство по имени: Java Sound обрезает имена до 31 знака, поэтому и начало имени. */
    private fun find(name: String?, info: Line.Info): Line? {
        if (name.isNullOrBlank()) return null
        val mixer = AudioSystem.getMixerInfo()
            .firstOrNull { it.name == name || name.startsWith(it.name) || it.name.startsWith(name) }
            ?.let { AudioSystem.getMixer(it) }
        return runCatching { mixer?.getLine(info) }.getOrNull()
    }
}
