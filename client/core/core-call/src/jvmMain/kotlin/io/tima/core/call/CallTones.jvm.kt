package io.tima.core.call

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.math.PI
import kotlin.math.sin

/**
 * ПК: тон 425 Гц складывается программой и играет в колонки по умолчанию — системных тонов
 * вызова, как на Android, у Windows нет. Своим потоком: запись в звуковую линию блокирует.
 * Нет звукового устройства — молчим: гудок не повод ронять звонок.
 */
actual object CallTones {
    @Volatile
    private var playing: Thread? = null

    @Volatile
    private var ringing = false

    actual fun ringback(on: Boolean) {
        if (on) {
            if (ringing) return
            ringing = true
            play(onMs = RING_ON_MS, offMs = RING_OFF_MS, totalMs = Long.MAX_VALUE)
        } else if (ringing) {
            ringing = false
            playing = null
        }
    }

    actual fun busy() {
        ringing = false
        play(onMs = BUSY_ON_MS, offMs = BUSY_ON_MS, totalMs = BUSY_TONE_MS.toLong())
    }

    private fun play(onMs: Int, offMs: Int, totalMs: Long) {
        val thread = Thread {
            runCatching {
                val format = AudioFormat(RATE.toFloat(), 16, 1, true, false)
                val line = AudioSystem.getSourceDataLine(format)
                line.open(format)
                line.start()
                val me = Thread.currentThread()
                val tone = samples(CHUNK_MS, sound = true)
                val silence = samples(CHUNK_MS, sound = false)
                var played = 0L
                while (playing === me && played < totalMs) {
                    val inCycle = played % (onMs + offMs)
                    val chunk = if (inCycle < onMs) tone else silence
                    line.write(chunk, 0, chunk.size)
                    played += CHUNK_MS
                }
                line.drain()
                line.close()
            }
        }.apply {
            isDaemon = true
            name = "tima-call-tone"
        }
        playing = thread
        thread.start()
    }

    /** Кусок тона или тишины: 16 бит, моно. Длина кратна периоду — стыки не щёлкают. */
    private fun samples(ms: Int, sound: Boolean): ByteArray {
        val count = RATE * ms / 1000
        val out = ByteArray(count * 2)
        if (!sound) return out
        for (i in 0 until count) {
            val v = (sin(2 * PI * FREQUENCY * i / RATE) * AMPLITUDE).toInt()
            out[2 * i] = (v and 0xFF).toByte()
            out[2 * i + 1] = (v shr 8 and 0xFF).toByte()
        }
        return out
    }

    private const val RATE = 8_000
    private const val FREQUENCY = 425.0
    private const val AMPLITUDE = 6_000.0
    /** 40 мс — 17 периодов тона ровно: кусок кончается там же, где начинается. */
    private const val CHUNK_MS = 40
    private const val RING_ON_MS = 1_000
    private const val RING_OFF_MS = 4_000
    private const val BUSY_ON_MS = 360
}
