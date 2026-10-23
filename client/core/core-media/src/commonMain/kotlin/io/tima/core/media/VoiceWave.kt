package io.tima.core.media

import kotlin.math.sqrt

/**
 * Рисунок волны голосового (ПЛАН-(ВМ) §2.2): громкость кусков записи. Отправитель считает её при
 * записи и кладёт в сообщение — пузырь рисуется до скачивания файла.
 *
 * Громкость куска — среднеквадратичная по 20 мс; в сообщение — [BARS] долей, байт на долю
 * (0…255), относительно самой громкой доли записи и с корнем: тихая речь не сливается в черту.
 */
class VoiceWave {
    private var levels = FloatArray(1024)
    private var count = 0

    /** Громкость очередного куска записи. */
    fun add(pcm: ShortArray, n: Int) {
        if (n <= 0) return
        var sum = 0.0
        for (i in 0 until n) {
            val v = pcm[i].toDouble()
            sum += v * v
        }
        if (count == levels.size) levels = levels.copyOf(levels.size * 2)
        levels[count++] = sqrt(sum / n).toFloat()
    }

    /** Последние [n] кусков как доли 0…1 — живая волна в области записи. */
    fun recent(n: Int): List<Float> {
        val from = maxOf(0, count - n)
        return (from until count).map { (levels[it] / LOUD).coerceIn(0f, 1f) }
    }

    /** Рисунок для сообщения: [bars] долей по байту. */
    fun bars(bars: Int = BARS): ByteArray = bars(levels.copyOf(count), bars)

    companion object {
        /** Сколько долей у рисунка в сообщении. */
        const val BARS = 64

        /** Громкость, которая в живой волне — во всю высоту. */
        private const val LOUD = 6000f

        /** Тише этого вся запись — тишина: рисунок плоский, а не растянутый шум. */
        private const val QUIET = 200f

        fun bars(levels: FloatArray, bars: Int): ByteArray {
            if (levels.isEmpty()) return ByteArray(bars)
            val grouped = FloatArray(bars) { b ->
                val from = b * levels.size / bars
                val to = maxOf(from + 1, (b + 1) * levels.size / bars).coerceAtMost(levels.size)
                var s = 0f
                for (i in from until to) s += levels[i]
                s / (to - from)
            }
            val top = maxOf(grouped.max(), QUIET)
            return ByteArray(bars) { (sqrt((grouped[it] / top).coerceIn(0f, 1f)) * 255).toInt().toByte() }
        }
    }
}
