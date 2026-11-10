package io.tima.core.call

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Математика поиска лица по моделям MediaPipe Face Landmarker без MediaPipe Tasks (ПЛАН-(ФЗ) §6.3, вариант «в»,
 * заказчик 2026-11-10): MediaPipe Tasks шлёт в Google статистику, а сами модели — обычные TFLite (Apache 2.0).
 * Здесь — то, что у MediaPipe делают её калькуляторы: якоря и разбор ответа BlazeFace (short range), область лица с
 * поворотом по линии глаз, перевод точек из выреза обратно в кадр. Запуск моделей — `TfliteFaces` (Android).
 *
 * Координаты кадра — пиксели прямого кадра (x вправо, y вниз).
 */
internal object FaceModel {
    const val DETECTOR_SIZE = 128
    const val LANDMARKS_SIZE = 256
    const val LANDMARKS = 478
    private const val COORDS = 16
    private const val KEYPOINTS = 6
    private const val MIN_SCORE = 0.5f

    /** Область лица: середина, сторона квадрата (пиксели кадра) и угол оси x выреза в кадре. */
    data class Roi(val cx: Float, val cy: Float, val size: Float, val angle: Float) {
        /** Точка выреза (доли 0…1) → пиксели кадра. */
        fun toFrame(u: Float, v: Float): Pair<Float, Float> {
            val dx = (u - 0.5f) * size
            val dy = (v - 0.5f) * size
            val c = cos(angle)
            val s = sin(angle)
            return (cx + dx * c - dy * s) to (cy + dx * s + dy * c)
        }
    }

    /** Найденное лицо в долях холста детектора: середина, размер, шесть точек (глаза, нос, рот, уши). */
    data class Detection(val score: Float, val cx: Float, val cy: Float, val w: Float, val h: Float, val keypoints: FloatArray)

    /**
     * Якоря BlazeFace short range (`SsdAnchorsCalculator`): шаги 8, 16, 16, 16 на холсте 128, по два якоря в клетке
     * сетки 16×16 и по шесть — в 8×8; размер якоря — 1. Всего 896, порядок — слой, строка, столбец, якорь.
     */
    val anchors: FloatArray by lazy {
        val out = ArrayList<Float>(896 * 2)
        for ((grid, perCell) in listOf(16 to 2, 8 to 6)) {
            for (y in 0 until grid) for (x in 0 until grid) repeat(perCell) {
                out += (x + 0.5f) / grid
                out += (y + 0.5f) / grid
            }
        }
        out.toFloatArray()
    }

    /** Лучшее лицо из ответа детектора: [boxes] — 896 × 16, [scores] — 896 логитов. `null` — нет лица. */
    fun decode(boxes: FloatArray, scores: FloatArray): Detection? {
        var best = -1
        var bestScore = MIN_SCORE
        for (i in 0 until min(scores.size, anchors.size / 2)) {
            val score = sigmoid(scores[i].coerceIn(-100f, 100f))
            if (score > bestScore) {
                bestScore = score
                best = i
            }
        }
        if (best < 0) return null
        val ax = anchors[best * 2]
        val ay = anchors[best * 2 + 1]
        val o = best * COORDS
        val s = DETECTOR_SIZE.toFloat()
        val keypoints = FloatArray(KEYPOINTS * 2) { k -> boxes[o + 4 + k] / s + if (k % 2 == 0) ax else ay }
        return Detection(bestScore, boxes[o] / s + ax, boxes[o + 1] / s + ay, boxes[o + 2] / s, boxes[o + 3] / s, keypoints)
    }

    /** Кадр [width]×[height] вписан в холст детектора полями: масштаб и сдвиг. */
    data class Letterbox(val scale: Float, val offX: Float, val offY: Float) {
        /** Доля холста → пиксель кадра. */
        fun toFrame(x: Float, y: Float): Pair<Float, Float> = (x * DETECTOR_SIZE - offX) / scale to (y * DETECTOR_SIZE - offY) / scale
    }

    fun letterbox(width: Int, height: Int): Letterbox {
        val s = min(DETECTOR_SIZE.toFloat() / width, DETECTOR_SIZE.toFloat() / height)
        return Letterbox(s, (DETECTOR_SIZE - width * s) / 2, (DETECTOR_SIZE - height * s) / 2)
    }

    /** Область лица по найденному: квадрат ×1,5 от большей стороны, поворот — по линии глаз (точки 0 и 1). */
    fun roiFrom(d: Detection, box: Letterbox): Roi {
        val (cx, cy) = box.toFrame(d.cx, d.cy)
        val side = max(d.w, d.h) * DETECTOR_SIZE / box.scale
        val (rx, ry) = box.toFrame(d.keypoints[0], d.keypoints[1])
        val (lx, ly) = box.toFrame(d.keypoints[2], d.keypoints[3])
        return Roi(cx, cy, side * 1.5f, atan2(ly - ry, lx - rx))
    }

    /** Область лица по точкам прошлого кадра (как MediaPipe ведёт лицо): рамка всех точек ×1,5, поворот — 33 и 263. */
    fun roiFrom(xs: FloatArray, ys: FloatArray): Roi {
        var x0 = Float.MAX_VALUE
        var y0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE
        var y1 = -Float.MAX_VALUE
        for (i in xs.indices) {
            x0 = min(x0, xs[i]); x1 = max(x1, xs[i])
            y0 = min(y0, ys[i]); y1 = max(y1, ys[i])
        }
        val angle = atan2(ys[263] - ys[33], xs[263] - xs[33])
        return Roi((x0 + x1) / 2, (y0 + y1) / 2, max(x1 - x0, y1 - y0) * 1.5f, angle)
    }

    /** Точки модели (пиксели выреза 256) → пиксели кадра. */
    fun landmarksToFrame(raw: FloatArray, roi: Roi): Pair<FloatArray, FloatArray> {
        val xs = FloatArray(LANDMARKS)
        val ys = FloatArray(LANDMARKS)
        for (i in 0 until LANDMARKS) {
            val (x, y) = roi.toFrame(raw[i * 3] / LANDMARKS_SIZE, raw[i * 3 + 1] / LANDMARKS_SIZE)
            xs[i] = x
            ys[i] = y
        }
        return xs to ys
    }

    fun sigmoid(x: Float): Float = 1f / (1f + exp(-x))
}
