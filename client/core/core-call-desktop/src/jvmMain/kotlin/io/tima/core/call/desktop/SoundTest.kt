package io.tima.core.call.desktop

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlin.math.PI
import kotlin.math.sin

/**
 * Пробный звук в колонки — два коротких тона.
 *
 * Играет Java Sound, а не ADM: у ADM нет ручки «сыграй это», звук он играет только
 * собеседника. Колонки находятся **по имени**: Windows называет их одинаково и для ADM,
 * и для Java Sound («Динамики (High Definition Audio Device)»). Не нашлись — играем в
 * колонки по умолчанию: пробный звук, сыгранный не туда, лучше молчания, которое читается
 * как «колонки не работают».
 */
internal object SoundTest {

    fun play(speakerName: String?) {
        Thread({ runCatching { beep(speakerName) } }, "tima-sound-test").apply { isDaemon = true }.start()
    }

    private fun beep(speakerName: String?) {
        val format = AudioFormat(RATE.toFloat(), 16, 1, true, false)
        val info = DataLine.Info(SourceDataLine::class.java, format)
        val mixer = speakerName?.let { name ->
            AudioSystem.getMixerInfo().firstOrNull { it.name.contains(name) }
                ?.let { AudioSystem.getMixer(it) }
                ?.takeIf { it.isLineSupported(info) }
        }
        val line = (mixer?.getLine(info) ?: AudioSystem.getLine(info)) as SourceDataLine
        line.open(format)
        line.start()
        for (tone in listOf(660.0, 880.0)) {
            val samples = RATE * TONE_MS / 1000
            val bytes = ByteArray(samples * 2)
            for (i in 0 until samples) {
                // Края сглажены: без этого тон начинается и кончается щелчком.
                val edge = minOf(i, samples - i).coerceAtMost(FADE) / FADE.toDouble()
                val v = (sin(2 * PI * tone * i / RATE) * VOLUME * edge).toInt()
                bytes[i * 2] = v.toByte()
                bytes[i * 2 + 1] = (v shr 8).toByte()
            }
            line.write(bytes, 0, bytes.size)
        }
        line.drain()
        line.close()
    }

    private const val RATE = 48_000
    private const val TONE_MS = 350
    private const val FADE = 1_200
    private const val VOLUME = 9_000.0
}
