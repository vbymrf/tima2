package io.tima.core.call.desktop

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Line
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine
import kotlin.math.abs
import kotlin.math.log10

/**
 * Проверка микрофона без звонка: уровень и «слушать себя» (заказчик 2026-09-26).
 *
 * ── ПОЧЕМУ НЕ ЧЕРЕЗ ADM ─────────────────────────────────────────────────────
 *
 * ADM WebRTC пишет микрофон только тогда, когда дорожка уходит в соединение, — без
 * звонка он молчит, и уровень Windows у такого микрофона стоит на нуле (проверено
 * 2026-09-26). Поэтому проверка пишет микрофон сама, через Java Sound, и уровень считает
 * по самому звуку. Звук здесь **без обработки звонка** (эха, шума, усиления): это проверка
 * устройства, а не разговора.
 *
 * Устройства находятся по имени: Windows называет их одинаково для ADM и для Java Sound.
 */
internal class MicCheck(
    microphoneName: String?,
    private val speakerName: String?,
    /** Обработка звонка — чтобы проверка слышала то, что получит собеседник. `null` — сырой звук. */
    private val apm: CheckApm? = null,
    /** Колонки не открылись — словами для экрана. */
    private val onTrouble: (String) -> Unit = {},
    /**
     * Замер для журнала: пик сырого микрофона и пик после обработки, дБ от полной шкалы,
     * раз в [STATS_MS], пока человек говорит. По двум числам видно, где теряется
     * громкость: тихо уже на входе — микрофон и путь до Windows; громко на входе и тихо
     * после — обработка.
     */
    private val onStats: (rawDb: Int, processedDb: Int) -> Unit = { _, _ -> },
) {

    private val format = AudioFormat(RATE.toFloat(), 16, 1, true, false)
    private val input: TargetDataLine = openInput(microphoneName)

    @Volatile
    private var output: SourceDataLine? = null

    @Volatile
    private var running = true

    /** Слушать себя: микрофон — в выбранные колонки. Лучше в наушниках, иначе свист. */
    @Volatile
    var listen: Boolean = false
        set(value) {
            field = value
            if (!value) closeOutput()
        }

    /**
     * Писать и считать уровень, пока не закрыли. [onLevel] — 0…1 по шкале децибел:
     * тихий голос по размаху — доли процента, а на полосе он должен быть виден.
     */
    fun run(onLevel: (Float) -> Unit) {
        val chunk = ByteArray(RATE / 50 * 2)
        var rawMax = 0
        var outMax = 0
        var statsAt = System.currentTimeMillis()
        input.start()
        while (running) {
            val read = input.read(chunk, 0, chunk.size)
            if (read <= 0) continue
            rawMax = maxOf(rawMax, peakOf(chunk, read))
            // Уровень и «слушать себя» — уже после обработки: это и услышит собеседник.
            apm?.capture(chunk, read)
            var peak = 0
            var i = 0
            while (i + 1 < read) {
                val v = abs(((chunk[i + 1].toInt() shl 8) or (chunk[i].toInt() and 0xff)).toShort().toInt())
                if (v > peak) peak = v
                i += 2
            }
            onLevel(level(peak))
            outMax = maxOf(outMax, peak)
            val now = System.currentTimeMillis()
            if (now - statsAt >= STATS_MS) {
                // Только когда есть что мерить: тишина комнаты в журнале ничего не объясняет.
                if (db(rawMax) > SPEECH_DB) onStats(db(rawMax), db(outMax))
                rawMax = 0
                outMax = 0
                statsAt = now
            }
            if (listen) {
                val out = output ?: runCatching { openOutput() }
                    .onFailure {
                        // Колонки не открылись — проверка микрофона продолжается, а человеку
                        // говорим, почему себя не слышно. Иначе падала вся проверка разом.
                        listen = false
                        onTrouble("колонки не открылись: " + (it.message ?: it::class.simpleName))
                    }
                    .getOrNull()?.also { output = it }
                if (out != null) {
                    apm?.render(chunk, read)
                    out.write(chunk, 0, read)
                }
            }
        }
    }

    fun close() {
        running = false
        runCatching { input.stop(); input.close() }
        closeOutput()
    }

    private fun closeOutput() {
        val out = output ?: return
        output = null
        runCatching { out.stop(); out.close() }
    }

    private fun openInput(name: String?): TargetDataLine {
        val info = DataLine.Info(TargetDataLine::class.java, format)
        val line = (find(name, info) ?: AudioSystem.getLine(info)) as TargetDataLine
        line.open(format, BUFFER)
        return line
    }

    private fun openOutput(): SourceDataLine {
        val info = DataLine.Info(SourceDataLine::class.java, format)
        val line = (find(speakerName, info) ?: AudioSystem.getLine(info)) as SourceDataLine
        line.open(format, BUFFER)
        line.start()
        return line
    }

    companion object {
        private const val RATE = 48_000
        private const val STATS_MS = 2_000L

        /** Ниже этого — тишина комнаты, а не речь. */
        private const val SPEECH_DB = -50

        fun db(peak: Int): Int = if (peak <= 0) -99 else (20 * log10(peak / 32768.0)).toInt()

        private fun peakOf(data: ByteArray, length: Int): Int {
            var peak = 0
            var i = 0
            while (i + 1 < length) {
                val v = abs(((data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xff)).toShort().toInt())
                if (v > peak) peak = v
                i += 2
            }
            return peak
        }
        private const val BUFFER = RATE / 10 * 2

        /** Нижний край шкалы: тише −60 дБ — пустая полоса. */
        private const val FLOOR_DB = 60.0

        fun level(peak: Int): Float {
            if (peak <= 0) return 0f
            val db = 20 * log10(peak / 32768.0)
            return ((db + FLOOR_DB) / FLOOR_DB).toFloat().coerceIn(0f, 1f)
        }

        /**
         * Устройство Java Sound по имени устройства Windows. DirectSound режет имена до 31
         * знака — поэтому сравнение в обе стороны по началу.
         */
        internal fun find(name: String?, info: Line.Info): Line? {
            if (name.isNullOrBlank()) return null
            val mixer = AudioSystem.getMixerInfo().firstOrNull { it.name == name || name.startsWith(it.name) || it.name.startsWith(name) }
                ?.let { AudioSystem.getMixer(it) }
                ?.takeIf { it.isLineSupported(info) }
            return mixer?.getLine(info)
        }
    }
}
