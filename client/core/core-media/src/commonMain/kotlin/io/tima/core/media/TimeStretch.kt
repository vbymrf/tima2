package io.tima.core.media

import kotlin.math.PI
import kotlin.math.cos

/**
 * Ускорение без смены тона (ПЛАН-(ВМ) Р39): 1,5× и 2× у голосовых и аудио звучат тем же голосом,
 * а не «мультяшно». Способ — WSOLA: из входа берутся окна с перекрытием, шаг по входу в [speed]
 * раз длиннее шага по выходу, а точное место каждого окна подбирается по сходству с
 * продолжением предыдущего — так стыки не щёлкают.
 *
 * Свой, а не системный: на Android ускорение умеет `AudioTrack`, а на ПК — никто; один способ на
 * обеих платформах звучит одинаково и проверяется тестом.
 */
class TimeStretch(rate: Int, val speed: Float) {
    private val window = rate * 40 / 1000          // 40 мс
    private val hop = window / 2                   // шаг по выходу
    private val seek = rate * 12 / 1000            // поиск места ±12 мс
    private val inHop = (hop * speed).toInt()
    private val hann = FloatArray(window) { (0.5 - 0.5 * cos(2 * PI * it / window)).toFloat() }

    private var input = FloatArray(window * 8)
    private var inLen = 0
    /** Где во входе начинается следующее окно (без подбора). */
    private var nominal = 0
    /** Хвост прошлого окна — к нему примеряется следующее. */
    private var tail = FloatArray(window)
    private var tailStart = -1
    private val overlap = FloatArray(hop)
    private var started = false

    /** Отдать кусок входа, получить готовый выход (может быть пустым). */
    fun put(pcm: ShortArray, n: Int): ShortArray {
        if (speed == 1f) return pcm.copyOf(n)
        if (inLen + n > input.size) input = input.copyOf(maxOf(input.size * 2, inLen + n))
        for (i in 0 until n) input[inLen + i] = pcm[i].toFloat()
        inLen += n
        val out = ArrayList<Short>()
        while (nominal + seek + window + seek <= inLen) {
            val start = if (!started) nominal else best(nominal)
            // Выход — половина окна: вторая половина прошлого окна плюс первая этого.
            for (i in 0 until hop) {
                val v = overlap[i] + input[start + i] * hann[i]
                out += v.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            for (i in 0 until hop) overlap[i] = input[start + hop + i] * hann[hop + i]
            // Естественное продолжение этого окна — образец для следующего.
            tailStart = start + hop
            started = true
            nominal += inHop
            // Прочитанное дальше не нужно: сдвигаем вход, чтобы он не рос.
            val drop = minOf(nominal - seek, tailStart).coerceAtLeast(0)
            if (drop > window * 4) {
                input.copyInto(input, 0, drop, inLen)
                inLen -= drop
                nominal -= drop
                tailStart -= drop
            }
        }
        return ShortArray(out.size) { out[it] }
    }

    /** Место окна около [around], у которого начало лучше всего совпадает с продолжением прошлого. */
    private fun best(around: Int): Int {
        val from = maxOf(0, around - seek)
        val to = around + seek
        var bestAt = around
        var bestScore = Float.NEGATIVE_INFINITY
        var at = from
        while (at <= to) {
            var s = 0f
            var i = 0
            while (i < hop) {
                s += input[at + i] * input[tailStart + i]
                i += 4
            }
            if (s > bestScore) {
                bestScore = s
                bestAt = at
            }
            at += 2
        }
        return bestAt
    }
}
