package io.tima.core.call

import android.content.Context
import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Модели MediaPipe Face Landmarker — через TFLite, без MediaPipe Tasks (ПЛАН-(ФЗ) §6.3, вариант «в»): поиск лица
 * BlazeFace (128×128) и 478 точек (вырез 256×256 по линии глаз). Пока лицо держится, следующий вырез — по точкам
 * прошлого (как ведёт MediaPipe), поиск — только когда лицо потеряно. Модели — `assets/filters/mp_*.tflite`.
 */
internal class TfliteFaces private constructor(private val detector: Interpreter, private val landmarker: Interpreter) {
    private val detectorIn: ByteBuffer = direct(FaceModel.DETECTOR_SIZE * FaceModel.DETECTOR_SIZE * 3 * 4)
    private val landmarksIn: ByteBuffer = direct(FaceModel.LANDMARKS_SIZE * FaceModel.LANDMARKS_SIZE * 3 * 4)

    private val boxesOut = direct(896 * 16 * 4)
    private val scoresOut = direct(896 * 4)
    private val pointsOut = direct(FaceModel.LANDMARKS * 3 * 4)
    private val presenceOut = direct(4)

    private val detectorBoxes: Int
    private val detectorScores: Int
    private val landmarksPoints: Int
    private val landmarksPresence: Int

    /** Лицо прошлого кадра: по нему — следующий вырез без поиска. */
    private var tracked: FaceModel.Roi? = null

    init {
        fun outputs(i: Interpreter) = (0 until i.outputTensorCount).map { it to i.getOutputTensor(it).numElements() }
        val d = outputs(detector)
        detectorBoxes = d.first { it.second == 896 * 16 }.first
        detectorScores = d.first { it.second == 896 }.first
        val l = outputs(landmarker)
        landmarksPoints = l.first { it.second == FaceModel.LANDMARKS * 3 }.first
        landmarksPresence = l.first { it.second == 1 }.first
    }

    /** 478 точек (доли кадра, y вниз) или `null`. [rgba] — прямой кадр [width]×[height]. */
    fun detect(rgba: ByteBuffer, width: Int, height: Int): Pair<FloatArray, FloatArray>? {
        val roi = tracked ?: find(rgba, width, height) ?: return null
        val raw = landmarks(rgba, width, height, roi)
        if (raw == null) {
            tracked = null
            return null
        }
        val (xs, ys) = FaceModel.landmarksToFrame(raw, roi)
        tracked = FaceModel.roiFrom(xs, ys)
        for (i in xs.indices) {
            xs[i] /= width
            ys[i] /= height
        }
        return xs to ys
    }

    private fun find(rgba: ByteBuffer, width: Int, height: Int): FaceModel.Roi? {
        val box = FaceModel.letterbox(width, height)
        // Холст 128: кадр вписан с полями, цвета −1…1 (как ждёт BlazeFace).
        val canvas = FaceModel.DETECTOR_SIZE / box.scale
        fill(
            detectorIn.asFloatBuffer(), rgba, width, height, FaceModel.DETECTOR_SIZE, -1f, 2f,
            canvas, 0f, -box.offX / box.scale, 0f, canvas, -box.offY / box.scale,
        )
        boxesOut.rewind(); scoresOut.rewind()
        detector.runForMultipleInputsOutputs(arrayOf<Any>(detectorIn), mapOf<Int, Any>(detectorBoxes to boxesOut, detectorScores to scoresOut))
        val boxes = FloatArray(896 * 16).also { boxesOut.rewind(); boxesOut.asFloatBuffer().get(it) }
        val scores = FloatArray(896).also { scoresOut.rewind(); scoresOut.asFloatBuffer().get(it) }
        val found = FaceModel.decode(boxes, scores) ?: return null
        return FaceModel.roiFrom(found, box)
    }

    private fun landmarks(rgba: ByteBuffer, width: Int, height: Int, roi: FaceModel.Roi): FloatArray? {
        // Вырез 256 по области лица, цвета 0…1.
        val c = kotlin.math.cos(roi.angle) * roi.size
        val sn = kotlin.math.sin(roi.angle) * roi.size
        // roi.toFrame(u, v) = середина + (u − ½)·(c, sn) + (v − ½)·(−sn, c)
        fill(
            landmarksIn.asFloatBuffer(), rgba, width, height, FaceModel.LANDMARKS_SIZE, 0f, 1f,
            c, -sn, roi.cx - (c - sn) / 2, sn, c, roi.cy - (sn + c) / 2,
        )
        pointsOut.rewind(); presenceOut.rewind()
        landmarker.runForMultipleInputsOutputs(arrayOf<Any>(landmarksIn), mapOf<Int, Any>(landmarksPoints to pointsOut, landmarksPresence to presenceOut))
        presenceOut.rewind()
        if (FaceModel.sigmoid(presenceOut.asFloatBuffer().get(0)) < PRESENCE) return null
        return FloatArray(FaceModel.LANDMARKS * 3).also { pointsOut.rewind(); pointsOut.asFloatBuffer().get(it) }
    }

    /**
     * Вход модели [size]×[size]×3: клетка (u, v — доли входа) берёт цвет кадра в точке `x = ax·u + bx·v + cx`,
     * `y = ay·u + by·v + cy` (пиксели) — ближайший пиксель; вне кадра — чёрное. Цвет — `offset + scale × канал/255`.
     * Без объектов на клетку: 65 тысяч клеток на каждый поиск (замер Samsung A40: с ними поиск шёл 150 мс).
     */
    private fun fill(
        into: FloatBuffer, rgba: ByteBuffer, width: Int, height: Int, size: Int, offset: Float, scale: Float,
        ax: Float, bx: Float, cx: Float, ay: Float, by: Float, cy: Float,
    ) {
        into.rewind()
        val k = scale / 255f
        val row = FloatArray(size * 3)
        for (y in 0 until size) {
            val v = (y + 0.5f) / size
            var i = 0
            for (x in 0 until size) {
                val u = (x + 0.5f) / size
                val px = (ax * u + bx * v + cx).toInt()
                val py = (ay * u + by * v + cy).toInt()
                if (px < 0 || py < 0 || px >= width || py >= height) {
                    row[i] = offset; row[i + 1] = offset; row[i + 2] = offset
                } else {
                    val at = (py * width + px) * 4
                    row[i] = offset + (rgba.get(at).toInt() and 0xFF) * k
                    row[i + 1] = offset + (rgba.get(at + 1).toInt() and 0xFF) * k
                    row[i + 2] = offset + (rgba.get(at + 2).toInt() and 0xFF) * k
                }
                i += 3
            }
            into.put(row)
        }
    }

    fun close() {
        runCatching { detector.close() }
        runCatching { landmarker.close() }
    }

    companion object {
        /** Ниже — модель говорит «лица нет». */
        private const val PRESENCE = 0.5f

        private fun direct(bytes: Int): ByteBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())

        fun open(context: Context): TfliteFaces? = runCatching {
            fun load(name: String): ByteBuffer {
                val bytes = context.assets.open("filters/$name").use { it.readBytes() }
                return direct(bytes.size).put(bytes).also { it.rewind() }
            }
            val options = Interpreter.Options().setNumThreads(4)
            TfliteFaces(Interpreter(load("mp_face_detector.tflite"), options), Interpreter(load("mp_face_landmarks.tflite"), options))
        }.onFailure {
            Journal.trouble(LogCode.CALL, "фильтры: модели MediaPipe через TFLite не загрузились", "причина" to (it.message ?: it::class.simpleName))
        }.getOrNull()
    }
}
