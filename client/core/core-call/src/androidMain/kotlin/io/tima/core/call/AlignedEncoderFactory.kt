package io.tima.core.call

import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode
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
 *
 * ── ТРИ СПОСОБА ─────────────────────────────────────────────────────────────
 *
 * `way` — галочки стенда под «Кратность» (заказчик 2026-09-29):
 * - заявка на все слои — [Aligned16] с распространением на слои, так было первым;
 * - «Только один слой» — [Aligned16] без распространения, как в WebRTC Google;
 * - «Обрезка кодером» — [Crop16]: заявки нет, кадр обрезается по центру перед кодером.
 */
internal class AlignedEncoderFactory(eglContext: EglBase.Context?, way: Alignment) : VideoEncoderFactory {

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
            OwnThreadFactory(AlignFactory(HardwareVideoEncoderFactory(eglContext, true, false), way))
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

/**
 * Каждый аппаратный кодер — в [Aligned16] или [Crop16], по способу [way], а снаружи — в
 * [Logged]: заведение и закрытие каждого кодера видны в журнале.
 */
private class AlignFactory(private val hardware: VideoEncoderFactory, private val way: Alignment) : VideoEncoderFactory {
    override fun createEncoder(info: VideoCodecInfo): VideoEncoder? {
        val made = hardware.createEncoder(info)
        if (made == null) {
            Journal.trouble(LogCode.CALL, "кодер: аппаратный не создан", "кодек" to info.name)
            return null
        }
        val aligned = when (way) {
            Alignment.RequestAllLayers -> Aligned16(made, allLayers = true)
            Alignment.RequestOneLayer -> Aligned16(made, allLayers = false)
            Alignment.Crop -> Crop16(made)
        }
        return Logged(aligned, info.name)
    }

    override fun getSupportedCodecs(): Array<VideoCodecInfo> = hardware.supportedCodecs
}

/**
 * Аппаратный кодер как есть, но с заявкой кратности 16: на всех слоях ([allLayers]) или,
 * как в WebRTC Google, только на один поток.
 *
 * `createNative` не передаётся: вернув родной объект, мы отдали бы WebRTC его заявку, а
 * не нашу. Аппаратный кодер родного объекта и не имеет.
 */
private class Aligned16(private val encoder: VideoEncoder, private val allLayers: Boolean) : VideoEncoder {
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
    override fun getEncoderInfo(): VideoEncoder.EncoderInfo = VideoEncoder.EncoderInfo(ALIGNMENT, allLayers)

    private companion object {
        const val ALIGNMENT = 16
    }
}

/**
 * Аппаратный кодер, которому кадр обрезают по центру до кратного 16 («Обрезка кодером»,
 * заказчик 2026-09-29). WebRTC о кратности не знает: заявка остаётся той, что у кодера
 * (2), и перенастраивать кодер из-за неё нечего.
 *
 * WebRTC заводит кодер под свой размер, например 270×480. Кодер заводится под кратный —
 * 256×480, — а из каждого кадра вырезается середина этого размера: 6 точек слева и 8
 * справа (смещение чётное). Кадр другого размера кодер принял бы за смену размера и перезапустился
 * бы, поэтому обрезается каждый кадр, а не только первый.
 *
 * Стоит под [OwnThread]: тот уже довёл кадр до размера, под который WebRTC завёл кодер.
 */
private class Crop16(private val encoder: VideoEncoder) : VideoEncoder {
    private var crop: CenterCrop? = null

    override fun initEncode(settings: VideoEncoder.Settings, callback: VideoEncoder.Callback): VideoCodecStatus {
        val next = CenterCrop(settings.width, settings.height)
        if (next != crop && !next.none) {
            Journal.note(
                LogCode.CALL, "кодер: обрезка до кратного 16",
                "было" to "${next.width}×${next.height}", "стало" to "${next.alignedWidth}×${next.alignedHeight}",
            )
        }
        crop = next
        val aligned = if (next.none) settings else VideoEncoder.Settings(
            settings.numberOfCores, next.alignedWidth, next.alignedHeight, settings.startBitrate,
            settings.maxFramerate, settings.numberOfSimulcastStreams, settings.automaticResizeOn,
            settings.capabilities,
        )
        return encoder.initEncode(aligned, callback)
    }

    override fun encode(frame: VideoFrame, info: VideoEncoder.EncodeInfo): VideoCodecStatus {
        val wanted = crop
        val source = frame.buffer
        if (wanted == null || wanted.none ||
            (source.width == wanted.alignedWidth && source.height == wanted.alignedHeight)
        ) {
            return encoder.encode(frame, info)
        }
        val (x, y, w, h) = wanted.region(source.width, source.height)
        val cut = source.cropAndScale(x, y, w, h, wanted.alignedWidth, wanted.alignedHeight)
        return try {
            encoder.encode(VideoFrame(cut, frame.rotation, frame.timestampNs), info)
        } finally {
            cut.release()
        }
    }

    override fun release(): VideoCodecStatus = encoder.release()
    override fun isHardwareEncoder(): Boolean = encoder.isHardwareEncoder
    @Deprecated("WebRTC зовёт setRates; этот оставлен интерфейсом")
    override fun setRateAllocation(allocation: VideoEncoder.BitrateAllocation, framerate: Int): VideoCodecStatus =
        encoder.setRateAllocation(allocation, framerate)
    override fun setRates(parameters: VideoEncoder.RateControlParameters): VideoCodecStatus = encoder.setRates(parameters)
    override fun getScalingSettings(): VideoEncoder.ScalingSettings = encoder.scalingSettings
    override fun getResolutionBitrateLimits(): Array<VideoEncoder.ResolutionBitrateLimits> = encoder.resolutionBitrateLimits
    override fun getImplementationName(): String = encoder.implementationName
    override fun getEncoderInfo(): VideoEncoder.EncoderInfo = encoder.encoderInfo
}

/**
 * Кодер как есть, но каждое заведение, закрытие и первая ошибка кодирования — строкой
 * журнала (заказчик 2026-09-29, вариант 1а).
 *
 * ── ЗАЧЕМ ───────────────────────────────────────────────────────────────────
 *
 * Samsung с «Кратность 16» на H.264 застревал на 240×320: через 7 с WebRTC хотел поднять
 * размер, старый кодер закрывался, а новый 21 с не открывался вовсе — в системном журнале
 * ни одной попытки. Какой размер просил WebRTC и что ответил кодер, не было видно нигде:
 * внутренний журнал WebRTC выключен. Теперь видно: `размер=` — что просил WebRTC (до
 * обрезки), `итог=` — что ответил кодер. Закрытие без следующего заведения и есть провал.
 *
 * Ошибка кодирования пишется одна на заведение, иначе при 30 кадрах в секунду журнал
 * утонул бы.
 */
private class Logged(private val encoder: VideoEncoder, private val codec: String) : VideoEncoder {
    private var size = "—"
    private var failed = false

    override fun initEncode(settings: VideoEncoder.Settings, callback: VideoEncoder.Callback): VideoCodecStatus {
        size = "${settings.width}×${settings.height}"
        failed = false
        val status = encoder.initEncode(settings, callback)
        val details = arrayOf<Pair<String, Any?>>(
            "кодек" to codec, "размер" to size, "кбит/с" to settings.startBitrate,
            "кадров" to settings.maxFramerate, "итог" to status.name,
        )
        if (status == VideoCodecStatus.OK) {
            Journal.note(LogCode.CALL, "кодер заведён", *details)
        } else {
            Journal.trouble(LogCode.CALL, "кодер не завёлся", *details)
        }
        return status
    }

    override fun release(): VideoCodecStatus {
        val status = encoder.release()
        Journal.note(LogCode.CALL, "кодер закрыт", "кодек" to codec, "размер" to size)
        return status
    }

    override fun encode(frame: VideoFrame, info: VideoEncoder.EncodeInfo): VideoCodecStatus {
        val status = encoder.encode(frame, info)
        if (status !in QUIET && !failed) {
            failed = true
            Journal.trouble(
                LogCode.CALL, "кодер: ошибка кодирования",
                "кодек" to codec, "размер" to size,
                "кадр" to "${frame.buffer.width}×${frame.buffer.height}", "итог" to status.name,
            )
        }
        return status
    }

    override fun isHardwareEncoder(): Boolean = encoder.isHardwareEncoder
    @Deprecated("WebRTC зовёт setRates; этот оставлен интерфейсом")
    override fun setRateAllocation(allocation: VideoEncoder.BitrateAllocation, framerate: Int): VideoCodecStatus =
        encoder.setRateAllocation(allocation, framerate)
    override fun setRates(parameters: VideoEncoder.RateControlParameters): VideoCodecStatus = encoder.setRates(parameters)
    override fun getScalingSettings(): VideoEncoder.ScalingSettings = encoder.scalingSettings
    override fun getResolutionBitrateLimits(): Array<VideoEncoder.ResolutionBitrateLimits> = encoder.resolutionBitrateLimits
    override fun getImplementationName(): String = encoder.implementationName
    override fun getEncoderInfo(): VideoEncoder.EncoderInfo = encoder.encoderInfo

    private companion object {
        /** Не ошибки: кадр пропущен или поток чуть выше заказанного. */
        val QUIET = setOf(VideoCodecStatus.OK, VideoCodecStatus.NO_OUTPUT, VideoCodecStatus.TARGET_BITRATE_OVERSHOOT)
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
