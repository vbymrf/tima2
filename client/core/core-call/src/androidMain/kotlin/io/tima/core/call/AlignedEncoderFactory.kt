package io.tima.core.call

import livekit.org.webrtc.EglBase
import livekit.org.webrtc.HardwareVideoEncoderFactory
import livekit.org.webrtc.SimulcastVideoEncoderFactory
import livekit.org.webrtc.SoftwareVideoEncoderFactory
import livekit.org.webrtc.VideoCodecInfo
import livekit.org.webrtc.VideoCodecStatus
import livekit.org.webrtc.VideoEncoder
import livekit.org.webrtc.VideoEncoderFactory
import livekit.org.webrtc.VideoEncoderFallback
import livekit.org.webrtc.VideoFrame
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Фабрика кодеров, у которой аппаратный кодер заявляет кратность 16 (ПЛАН-ВИДЕО.md В2).
 *
 * ── ЗАЧЕМ ───────────────────────────────────────────────────────────────────
 *
 * Аппаратный кодер WebRTC нашей сборки (`HardwareVideoEncoder`, libwebrtc 144) заявляет
 * кратность 2 — «MediaCodec requires 2x2 alignment». WebRTC и ужимает кадр при нехватке
 * канала до 270×480 или 360×480, а кодер H.264 realme (Unisoc) на стороне, не кратной
 * 16, даёт полосы. Заявка кратности — штатный путь WebRTC: получив 16, он сам подбирает
 * размеры, кратные 16, и сам же решает, когда и насколько ужимать. Мы ему только
 * сообщаем ограничение, а не вмешиваемся в его решения.
 *
 * ── КАК УСТРОЕНО ────────────────────────────────────────────────────────────
 *
 * Повторяет связку, которую livekit-android 2.28.2 строит сам
 * (`CustomVideoEncoderFactory` → `SimulcastVideoEncoderFactoryWrapper`): аппаратная
 * фабрика, каждый кодер — на своём потоке, программный — запасным через
 * `VideoEncoderFallback`, сверху `SimulcastVideoEncoderFactory` WebRTC. Отличие одно:
 * аппаратный кодер обёрнут в [Aligned16]. Классы SDK закрытые, поэтому повторены, а не
 * взяты; при обновлении SDK сверить с его `SimulcastVideoEncoderFactoryWrapper`.
 *
 * Обёртка стоит **над аппаратным кодером, а не над готовой связкой**: связка отдаёт
 * WebRTC родной объект (`createNative`), и его заявку WebRTC берёт из родного кода, мимо
 * Java. Аппаратный кодер — Java-объект, и его `getEncoderInfo` WebRTC спрашивает.
 */
internal class AlignedEncoderFactory(eglContext: EglBase.Context?) : VideoEncoderFactory {

    // ── ВСЁ — ЛЕНИВО, ПРИ ПЕРВОМ ОБРАЩЕНИИ WEBRTC ─────────────────────────────
    //
    // Фабрика создаётся ДО комнаты, а библиотека WebRTC грузится при создании комнаты.
    // Программный кодер WebRTC зовёт свой код в библиотеке уже в конструкторе, и созданный
    // здесь он ронял приложение: `UnsatisfiedLinkError … nativeCreateFactory` на Samsung
    // 2026-09-29, у звонящего и у принимающего. Первым к фабрике обращается сама WebRTC —
    // значит, библиотека к этому моменту уже загружена.
    //
    // Те же флаги, что у SDK: Intel VP8 — да, H.264 High — нет.
    private val combined by lazy {
        val primary: VideoEncoderFactory =
            OwnThreadFactory(Aligned16Factory(HardwareVideoEncoderFactory(eglContext, true, false)))
        val fallback: VideoEncoderFactory = OwnThreadFactory(FallbackFactory(primary))
        SimulcastVideoEncoderFactory(primary, fallback)
    }

    override fun createEncoder(info: VideoCodecInfo): VideoEncoder? = combined.createEncoder(info)

    /**
     * Как у SDK: VP9 **без** параметров не предлагается — остаются записи с профилем;
     * повторы сняты. Иначе согласование шло бы по другому списку, чем без этой фабрики.
     *
     * До 2026-09-29 условие стояло наоборот — выбрасывались записи с профилем, то есть VP9
     * целиком, — и набор «VP9» с кратностью уходил VP8 или H.264 (Samsung, realme).
     */
    override fun getSupportedCodecs(): Array<VideoCodecInfo> = combined.supportedCodecs
        .filterNot { it.name.equals("VP9", ignoreCase = true) && it.params.isNullOrEmpty() }
        .distinctBy { Triple(it.name, it.params, it.scalabilityModes) }
        .toTypedArray()
}

/** Каждый аппаратный кодер — в [Aligned16]. */
private class Aligned16Factory(private val hardware: VideoEncoderFactory) : VideoEncoderFactory {
    override fun createEncoder(info: VideoCodecInfo): VideoEncoder? =
        hardware.createEncoder(info)?.let { Aligned16(it) }

    override fun getSupportedCodecs(): Array<VideoCodecInfo> = hardware.supportedCodecs
}

/**
 * Аппаратный кодер как есть, но с заявкой кратности 16 на всех слоях.
 *
 * `createNative` не передаётся: вернув родной объект, мы отдали бы WebRTC его заявку, а
 * не нашу. Аппаратный кодер родного объекта и не имеет.
 */
private class Aligned16(private val encoder: VideoEncoder) : VideoEncoder {
    override fun isHardwareEncoder(): Boolean = encoder.isHardwareEncoder
    override fun initEncode(settings: VideoEncoder.Settings, callback: VideoEncoder.Callback): VideoCodecStatus =
        encoder.initEncode(settings, callback)
    override fun release(): VideoCodecStatus = encoder.release()
    override fun encode(frame: VideoFrame, info: VideoEncoder.EncodeInfo): VideoCodecStatus = encoder.encode(frame, info)
    @Deprecated("WebRTC зовёт setRates; этот оставлен интерфейсом")
    override fun setRateAllocation(allocation: VideoEncoder.BitrateAllocation, framerate: Int): VideoCodecStatus =
        encoder.setRateAllocation(allocation, framerate)
    override fun setRates(parameters: VideoEncoder.RateControlParameters): VideoCodecStatus = encoder.setRates(parameters)
    override fun getScalingSettings(): VideoEncoder.ScalingSettings = encoder.scalingSettings
    override fun getResolutionBitrateLimits(): Array<VideoEncoder.ResolutionBitrateLimits> = encoder.resolutionBitrateLimits
    override fun getImplementationName(): String = encoder.implementationName
    override fun getEncoderInfo(): VideoEncoder.EncoderInfo = VideoEncoder.EncoderInfo(ALIGNMENT, true)

    private companion object {
        const val ALIGNMENT = 16
    }
}

/** Аппаратный с программным запасным, как у SDK. Нет одного — отдаётся другой. */
private class FallbackFactory(private val hardware: VideoEncoderFactory) : VideoEncoderFactory {
    // Лениво — см. [AlignedEncoderFactory]: конструктор зовёт библиотеку WebRTC.
    private val software by lazy { SoftwareVideoEncoderFactory() }

    override fun createEncoder(info: VideoCodecInfo): VideoEncoder? {
        val soft = software.createEncoder(info)
        val hard = hardware.createEncoder(info)
        return if (hard != null && soft != null) VideoEncoderFallback(soft, hard) else hard ?: soft
    }

    override fun getSupportedCodecs(): Array<VideoCodecInfo> =
        (software.supportedCodecs.toList() + hardware.supportedCodecs.toList()).toTypedArray()
}

/** Каждый кодер — в [OwnThread]. */
private class OwnThreadFactory(private val inner: VideoEncoderFactory) : VideoEncoderFactory {
    override fun createEncoder(info: VideoCodecInfo): VideoEncoder? = inner.createEncoder(info)?.let { OwnThread(it) }

    override fun getSupportedCodecs(): Array<VideoCodecInfo> = inner.supportedCodecs
}

/**
 * Все вызовы кодера — на одном его потоке, и кадр — того размера, под который кодер
 * заведён. Так делает SDK (`StreamEncoderWrapper`): аппаратный кодер проверяет, что его
 * зовут с одного потока, а слои simulcast приходят с разных. Кадр другого размера
 * приходит, пока WebRTC перенастраивает кодер, — его доводят до заведённого.
 */
private class OwnThread(private val encoder: VideoEncoder) : VideoEncoder {
    private val thread: ExecutorService = Executors.newSingleThreadExecutor()
    private var settings: VideoEncoder.Settings? = null

    private fun <T> on(block: () -> T): T = thread.submit(Callable { block() }).get()

    override fun initEncode(settings: VideoEncoder.Settings, callback: VideoEncoder.Callback): VideoCodecStatus {
        this.settings = settings
        return on { encoder.initEncode(settings, callback) }
    }

    override fun release(): VideoCodecStatus = on { encoder.release() }

    override fun encode(frame: VideoFrame, info: VideoEncoder.EncodeInfo): VideoCodecStatus = on {
        val wanted = settings
        if (wanted == null || frame.buffer.width == wanted.width) {
            encoder.encode(frame, info)
        } else {
            val source = frame.buffer
            val scaled = source.cropAndScale(0, 0, source.width, source.height, wanted.width, wanted.height)
            try {
                encoder.encode(VideoFrame(scaled, frame.rotation, frame.timestampNs), info)
            } finally {
                scaled.release()
            }
        }
    }

    @Deprecated("WebRTC зовёт setRates; этот оставлен интерфейсом")
    override fun setRateAllocation(allocation: VideoEncoder.BitrateAllocation, framerate: Int): VideoCodecStatus =
        on { encoder.setRateAllocation(allocation, framerate) }
    override fun setRates(parameters: VideoEncoder.RateControlParameters): VideoCodecStatus = on { encoder.setRates(parameters) }
    override fun getScalingSettings(): VideoEncoder.ScalingSettings = on { encoder.scalingSettings }
    override fun getImplementationName(): String = on { encoder.implementationName }
    override fun createNative(webrtcEnvRef: Long): Long = on { encoder.createNative(webrtcEnvRef) }
    override fun isHardwareEncoder(): Boolean = on { encoder.isHardwareEncoder }
    override fun getResolutionBitrateLimits(): Array<VideoEncoder.ResolutionBitrateLimits> = on { encoder.resolutionBitrateLimits }
    override fun getEncoderInfo(): VideoEncoder.EncoderInfo = on { encoder.encoderInfo }
}

/**
 * Контекст EGL для комнат с [AlignedEncoderFactory] — один на процесс.
 *
 * SDK освобождает только тот контекст, что создал сам; отданный ему — нет. Новый на
 * каждый звонок так бы и копился, а один живёт, пока жив процесс, — как и у SDK.
 */
private val alignedEgl: EglBase by lazy { EglBase.create() }

internal fun sharedEgl(): EglBase = alignedEgl
