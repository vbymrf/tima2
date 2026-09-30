package io.tima.core.call

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import livekit.org.webrtc.HardwareVideoEncoderFactory
import livekit.org.webrtc.VideoCodecInfo
import livekit.org.webrtc.VideoCodecStatus
import livekit.org.webrtc.VideoEncoder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * Проба кодеров — см. [probeCodecs] в commonMain.
 *
 * ── КАК УСТРОЕНО ────────────────────────────────────────────────────────────
 *
 * Кодеры берутся из системного перечня (`MediaCodecList`) и зовутся **напрямую**, мимо
 * WebRTC: так видно, что умеет сам кодер производителя, включая нечётные размеры, до
 * которых WebRTC его не допускает. Отдельной частью — путь WebRTC: его фабрика аппаратных
 * кодеров и `initEncode` на каждом размере, как в звонке.
 *
 * Кадры — тестовая таблица (шахматка 32×32 с градиентом, сдвигается на 4 точки за кадр),
 * цвет серый: сравнивается яркость. Кодер получает кадры через `Image` (формат YUV «гибкий»)
 * — так их понимает любой кодер, какой бы раскладкой памяти он ни пользовался.
 *
 * Сравнение — по каждой строке раскодированного кадра против исходной: средняя разница
 * яркости больше [BAD_ROW] — строка испорчена. Испорченные строки подряд у верхнего или
 * нижнего края — «полоса». Раскодированный кадр берётся **в пределах видимой рамки**,
 * которую сообщает раскодировщик (`Image.cropRect`), — ровно то, что попало бы на экран.
 *
 * Каждый случай ограничен по времени ([CASE_MS]): зависший кодер не должен вешать пробу.
 */
actual suspend fun probeCodecs(progress: (String) -> Unit): String? = withContext(Dispatchers.Default) {
    CodecProber(progress).run()
}

private class ProbeSize(val w: Int, val h: Int) {
    override fun toString() = "${w}×$h"
}

private class Packet(val data: ByteArray, val ptsUs: Long, val flags: Int)

private class Encoded(val packets: List<Packet>?, val error: String?)

private class Checked(val verdict: String, val good: Boolean)

private class CodecProber(private val progress: (String) -> Unit) {

    private val rows = StringBuilder()
    private var total = 0
    private var bad = 0

    fun run(): String {
        val started = System.currentTimeMillis()
        Journal.note(LogCode.CALL, "проба кодеров начата", "телефон" to Build.MODEL)
        val out = StringBuilder()
        out.appendLine("# Проба кодеров — ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        out.appendLine()
        out.appendLine("Кадр: шахматка 32×32 с градиентом, $FRAMES кадров, сравнение яркости по строкам. «Верно» — испорченных строк нет; «полосы» — сколько строк испорчено и где.")
        out.appendLine()
        out.appendLine(codecList())
        out.appendLine(webrtcPath())
        out.appendLine("## Кодеры и раскодировщики по размерам")
        out.appendLine()
        out.appendLine("| кодек | размер | что проверяли | компонент | объявлен | итог |")
        out.appendLine("|---|---|---|---|---|---|")
        for ((label, mime) in MIMES) {
            val encoders = components(mime, encoder = true)
            val decoders = components(mime, encoder = false)
            val refEnc = encoders.firstOrNull { !isHardware(it) }
            val refDec = decoders.firstOrNull { !isHardware(it) }
            for (size in SIZES) {
                progress("$label $size")
                // программный кодер → программный раскодировщик: эталон
                val reference = refEnc?.let { encode(it, mime, size) }
                if (refEnc != null && refDec != null) {
                    row(label, size, "программное сжатие", refEnc, verdictOf(reference!!, refDec, mime, size))
                }
                // аппаратный кодер → программный раскодировщик
                for (enc in encoders.filter { isHardware(it) }) {
                    val made = encode(enc, mime, size)
                    val result = if (refDec == null) Checked("эталонного раскодировщика нет", false)
                    else verdictOf(made, refDec, mime, size)
                    row(label, size, "аппаратное сжатие", enc, result)
                }
                // программный кодер → аппаратный раскодировщик
                if (reference?.packets != null) {
                    for (dec in decoders.filter { isHardware(it) }) {
                        row(label, size, "аппаратное раскодирование", dec, check(dec, mime, size, reference.packets))
                    }
                }
            }
        }
        out.append(rows)
        val seconds = (System.currentTimeMillis() - started) / 1000
        out.appendLine()
        out.appendLine("Случаев: $total, не «верно»: $bad, секунд: $seconds.")
        Journal.note(LogCode.CALL, "проба кодеров окончена", "случаев" to total, "не верно" to bad, "секунд" to seconds)
        return out.toString()
    }

    private fun row(codec: String, size: ProbeSize, what: String, info: MediaCodecInfo, result: Checked) {
        total++
        if (!result.good) bad++
        rows.appendLine("| $codec | $size | $what | `${info.name}` | ${declared(info, codec, size)} | ${result.verdict} |")
    }

    // ── ПЕРЕЧЕНЬ ────────────────────────────────────────────────────────────

    private fun components(mime: String, encoder: Boolean): List<MediaCodecInfo> =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder == encoder && it.supportedTypes.any { t -> t.equals(mime, ignoreCase = true) } }
            .sortedBy { if (isHardware(it)) 0 else 1 }

    private fun isHardware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated
        else !(info.name.startsWith("OMX.google.") || info.name.startsWith("c2.android."))

    private fun declared(info: MediaCodecInfo, codec: String, size: ProbeSize): String {
        val mime = MIMES.first { it.first == codec }.second
        val caps = runCatching { info.getCapabilitiesForType(mime).videoCapabilities }.getOrNull() ?: return "—"
        val ok = runCatching { caps.isSizeSupported(size.w, size.h) }.getOrDefault(false)
        return if (ok) "да" else "нет (кратность ${caps.widthAlignment}×${caps.heightAlignment})"
    }

    private fun codecList(): String {
        val sb = StringBuilder("## Что есть на телефоне\n\n| кодек | кодирует | раскодирует |\n|---|---|---|\n")
        for ((label, mime) in MIMES) {
            fun names(encoder: Boolean) = components(mime, encoder).joinToString(", ") { c ->
                val caps = runCatching { c.getCapabilitiesForType(mime).videoCapabilities }.getOrNull()
                val align = caps?.let { " ${it.widthAlignment}×${it.heightAlignment}" } ?: ""
                "`${c.name}` (${if (isHardware(c)) "апп" else "прог"}$align)"
            }.ifEmpty { "нет" }
            sb.appendLine("| $label | ${names(true)} | ${names(false)} |")
        }
        sb.appendLine()
        sb.appendLine("В скобках — вид и кратность ширины×высоты, которую объявляет кодер.")
        return sb.toString()
    }

    // ── ПУТЬ WEBRTC ─────────────────────────────────────────────────────────

    /**
     * Что возьмёт WebRTC: его фабрика аппаратных кодеров (как в звонке, без контекста EGL —
     * кадры памятью) и `initEncode` на каждом размере. Отказ «ERR_SIZE» здесь — это отказ
     * обёртки WebRTC, до кодера телефона не дошедший.
     */
    private fun webrtcPath(): String {
        val sb = StringBuilder("## Путь WebRTC\n\n")
        val factory = runCatching { HardwareVideoEncoderFactory(null, true, false) }.getOrElse {
            return sb.append("Фабрика аппаратных кодеров WebRTC не создалась: ${it.javaClass.simpleName} ${it.message}\n").toString()
        }
        val supported = runCatching { factory.supportedCodecs.toList() }.getOrElse {
            return sb.append("Перечень WebRTC не получен: ${it.javaClass.simpleName} ${it.message}\n").toString()
        }
        sb.appendLine("Аппаратно WebRTC берёт: " + supported.joinToString(", ") { it.name }.ifEmpty { "ничего" } + ".")
        sb.appendLine()
        sb.appendLine("| кодек | " + SIZES.joinToString(" | ") { it.toString() } + " |")
        sb.appendLine("|---|" + SIZES.joinToString("") { "---|" })
        // у H.264 в перечне несколько записей (профили); берём первую, для которой фабрика
        // даёт кодер, — у записи High при выключенном High кодера нет
        for ((name, infos) in supported.groupBy { it.name }) {
            val info = infos.firstOrNull { i -> runCatching { factory.createEncoder(i)?.also { it.release() } }.getOrNull() != null }
            val cells = SIZES.map { size -> if (info == null) "нет кодера" else webrtcInit(factory, info, size) }
            sb.appendLine("| $name | " + cells.joinToString(" | ") + " |")
        }
        sb.appendLine()
        return sb.toString()
    }

    private fun webrtcInit(factory: HardwareVideoEncoderFactory, info: VideoCodecInfo, size: ProbeSize): String {
        progress("WebRTC ${info.name} $size")
        val encoder = runCatching { factory.createEncoder(info) }.getOrNull() ?: return "нет кодера"
        return try {
            val settings = VideoEncoder.Settings(1, size.w, size.h, 300, 15, 1, false)
            val status = encoder.initEncode(settings) { _, _ -> }
            if (status == VideoCodecStatus.OK) "OK" else status.name
        } catch (e: Throwable) {
            e.javaClass.simpleName
        } finally {
            runCatching { encoder.release() }
        }
    }

    // ── КОДИРОВАНИЕ ─────────────────────────────────────────────────────────

    private fun encode(info: MediaCodecInfo, mime: String, size: ProbeSize): Encoded {
        val codec = runCatching { MediaCodec.createByCodecName(info.name) }.getOrElse { return Encoded(null, "не создан: ${it.javaClass.simpleName}") }
        val packets = ArrayList<Packet>()
        try {
            val format = MediaFormat.createVideoFormat(mime, size.w, size.h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, max(300_000, size.w * size.h * 4))
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
            } catch (e: Throwable) {
                return Encoded(null, "не заводится: ${e.javaClass.simpleName}")
            }
            val deadline = System.currentTimeMillis() + CASE_MS
            val bufferInfo = MediaCodec.BufferInfo()
            var fed = 0
            var eosSent = false
            var done = false
            while (!done && System.currentTimeMillis() < deadline) {
                if (!eosSent) {
                    val index = codec.dequeueInputBuffer(WAIT_US)
                    if (index >= 0) {
                        if (fed < FRAMES) {
                            val image = codec.getInputImage(index)
                                ?: return Encoded(null, "кадр не подать: нет Image на входе")
                            fillFrame(image, fed, size)
                            codec.queueInputBuffer(index, 0, size.w * size.h * 3 / 2, ptsOf(fed), 0)
                            fed++
                        } else {
                            codec.queueInputBuffer(index, 0, 0, ptsOf(fed), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eosSent = true
                        }
                    }
                }
                val out = codec.dequeueOutputBuffer(bufferInfo, WAIT_US)
                if (out >= 0) {
                    val buffer = codec.getOutputBuffer(out)
                    if (buffer != null && bufferInfo.size > 0) {
                        val bytes = ByteArray(bufferInfo.size)
                        buffer.position(bufferInfo.offset)
                        buffer.get(bytes)
                        packets += Packet(bytes, bufferInfo.presentationTimeUs, bufferInfo.flags)
                    }
                    codec.releaseOutputBuffer(out, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                }
            }
            val frames = packets.count { it.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 }
            if (frames == 0) return Encoded(null, "завёлся, но не выдал ни кадра" + if (!done) " (время вышло)" else "")
            return Encoded(packets, null)
        } catch (e: Throwable) {
            return Encoded(null, "сбой при сжатии: ${e.javaClass.simpleName}")
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    private fun verdictOf(made: Encoded, decoder: MediaCodecInfo, mime: String, size: ProbeSize): Checked =
        if (made.packets == null) Checked(made.error ?: "—", false) else check(decoder, mime, size, made.packets)

    // ── РАСКОДИРОВАНИЕ И СРАВНЕНИЕ ──────────────────────────────────────────

    private fun check(info: MediaCodecInfo, mime: String, size: ProbeSize, packets: List<Packet>): Checked {
        val codec = runCatching { MediaCodec.createByCodecName(info.name) }.getOrElse { return Checked("раскодировщик не создан", false) }
        try {
            val format = MediaFormat.createVideoFormat(mime, size.w, size.h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            }
            try {
                codec.configure(format, null, null, 0)
                codec.start()
            } catch (e: Throwable) {
                return Checked("раскодировщик не заводится: ${e.javaClass.simpleName}", false)
            }
            val deadline = System.currentTimeMillis() + CASE_MS
            val bufferInfo = MediaCodec.BufferInfo()
            var next = 0
            var eosSent = false
            var done = false
            var frames = 0
            var worstRows = 0
            var worstWhere = ""
            var mse = 0.0
            var pixels = 0L
            var shown: ProbeSize? = null
            while (!done && System.currentTimeMillis() < deadline) {
                if (!eosSent) {
                    val index = codec.dequeueInputBuffer(WAIT_US)
                    if (index >= 0) {
                        if (next < packets.size) {
                            val p = packets[next++]
                            val buffer = codec.getInputBuffer(index)!!
                            buffer.clear()
                            buffer.put(p.data)
                            val flags = p.flags and (MediaCodec.BUFFER_FLAG_CODEC_CONFIG or MediaCodec.BUFFER_FLAG_KEY_FRAME)
                            codec.queueInputBuffer(index, 0, p.data.size, p.ptsUs, flags)
                        } else {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eosSent = true
                        }
                    }
                }
                val out = codec.dequeueOutputBuffer(bufferInfo, WAIT_US)
                if (out >= 0) {
                    if (bufferInfo.size > 0 || bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM == 0) {
                        val image = runCatching { codec.getOutputImage(out) }.getOrNull()
                        if (image != null) {
                            val crop = image.cropRect
                            shown = ProbeSize(crop.width(), crop.height())
                            val index = frameIndex(bufferInfo.presentationTimeUs)
                            val plane = image.planes[0]
                            val buf = plane.buffer
                            val stride = plane.rowStride
                            val step = plane.pixelStride
                            val w = min(crop.width(), size.w)
                            val h = min(crop.height(), size.h)
                            val badRows = ArrayList<Int>()
                            for (y in 0 until h) {
                                var diff = 0L
                                for (x in 0 until w) {
                                    val got = buf.get((crop.top + y) * stride + (crop.left + x) * step).toInt() and 0xFF
                                    val want = lumaAt(x, y, index)
                                    val d = got - want
                                    diff += abs(d)
                                    mse += (d * d).toDouble()
                                }
                                pixels += w
                                if (diff / w > BAD_ROW) badRows += y
                            }
                            if (badRows.size > worstRows) {
                                worstRows = badRows.size
                                worstWhere = where(badRows, h)
                            }
                            frames++
                            image.close()
                        }
                    }
                    codec.releaseOutputBuffer(out, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                }
            }
            if (frames == 0) return Checked("раскодировщик не выдал ни кадра" + if (!done) " (время вышло)" else "", false)
            val psnr = if (mse == 0.0 || pixels == 0L) 99.0 else 10 * log10(255.0 * 255.0 / (mse / pixels))
            val sizeNote = shown?.let { if (it.w != size.w || it.h != size.h) "; на выходе $it" else "" } ?: ""
            return when {
                worstRows > 0 -> Checked("**полосы**: $worstRows стр. $worstWhere, PSNR ${"%.1f".format(psnr)}$sizeNote", false)
                sizeNote.isNotEmpty() -> Checked("**размер другой**$sizeNote, PSNR ${"%.1f".format(psnr)}", false)
                psnr < MIN_PSNR -> Checked("**плохо**: PSNR ${"%.1f".format(psnr)}", false)
                else -> Checked("верно, PSNR ${"%.1f".format(psnr)}, кадров $frames", true)
            }
        } catch (e: Throwable) {
            return Checked("сбой при раскодировании: ${e.javaClass.simpleName}", false)
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    private fun where(rows: List<Int>, height: Int): String {
        val top = rows.takeWhile { it == rows.indexOf(it) }.size
        val bottom = rows.reversed().withIndex().takeWhile { (i, r) -> r == height - 1 - i }.size
        return when {
            top > 0 && top >= rows.size / 2 -> "сверху"
            bottom > 0 && bottom >= rows.size / 2 -> "снизу"
            else -> "в строках ${rows.first()}–${rows.last()}"
        }
    }

    // ── ТЕСТОВЫЙ КАДР ───────────────────────────────────────────────────────

    private fun lumaAt(x: Int, y: Int, frame: Int): Int {
        val shift = frame * 4
        val cell = (((x + shift) / 32) + (y / 32)) and 1
        return (if (cell == 0) 60 else 190) + ((x + y) and 31)
    }

    private fun fillFrame(image: android.media.Image, frame: Int, size: ProbeSize) {
        val planes = image.planes
        val luma = planes[0]
        val lb = luma.buffer
        for (y in 0 until size.h) for (x in 0 until size.w) {
            lb.put(y * luma.rowStride + x * luma.pixelStride, lumaAt(x, y, frame).toByte())
        }
        for (p in 1..2) {
            val plane = planes[p]
            val b = plane.buffer
            for (y in 0 until (size.h + 1) / 2) for (x in 0 until (size.w + 1) / 2) {
                val at = y * plane.rowStride + x * plane.pixelStride
                if (at < b.limit()) b.put(at, 128.toByte())
            }
        }
    }

    private fun ptsOf(frame: Int): Long = frame * 1_000_000L / FPS

    private fun frameIndex(ptsUs: Long): Int = ((ptsUs * FPS + 500_000) / 1_000_000L).toInt()

    private companion object {
        val MIMES = listOf(
            "H264" to MediaFormat.MIMETYPE_VIDEO_AVC,
            "VP8" to MediaFormat.MIMETYPE_VIDEO_VP8,
            "VP9" to MediaFormat.MIMETYPE_VIDEO_VP9,
            "H265" to MediaFormat.MIMETYPE_VIDEO_HEVC,
        )

        /**
         * Лестница WebRTC от камеры 720×960 и 720×1280 (ступени ×3/4 и ×1/2), размеры,
         * которые он выдавал при заявке кратности 16, и кратные — для сравнения.
         */
        val SIZES = listOf(
            ProbeSize(720, 960), ProbeSize(540, 720), ProbeSize(360, 480), ProbeSize(270, 360), ProbeSize(180, 240),
            ProbeSize(720, 1280), ProbeSize(540, 960), ProbeSize(360, 640), ProbeSize(270, 480), ProbeSize(180, 320),
            ProbeSize(352, 469), ProbeSize(176, 313), ProbeSize(240, 427), ProbeSize(352, 626),
            ProbeSize(240, 320), ProbeSize(480, 640), ProbeSize(352, 464), ProbeSize(352, 480),
        )

        const val FRAMES = 10
        const val FPS = 15
        const val WAIT_US = 10_000L
        const val CASE_MS = 6_000L

        /** Средняя разница яркости по строке, выше которой строка испорчена. */
        const val BAD_ROW = 40

        /** Ниже — картинка плохая даже без полос. */
        const val MIN_PSNR = 25.0
    }
}
