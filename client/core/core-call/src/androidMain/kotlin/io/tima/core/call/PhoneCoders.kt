package io.tima.core.call

import io.livekit.android.webrtc.CustomVideoEncoderFactory
import livekit.org.webrtc.EglBase
import livekit.org.webrtc.HardwareVideoDecoderFactory
import livekit.org.webrtc.HardwareVideoEncoderFactory
import livekit.org.webrtc.PlatformSoftwareVideoDecoderFactory
import livekit.org.webrtc.SoftwareVideoDecoderFactory
import livekit.org.webrtc.SoftwareVideoEncoderFactory
import livekit.org.webrtc.VideoCodecInfo
import livekit.org.webrtc.VideoDecoder
import livekit.org.webrtc.VideoDecoderFactory
import livekit.org.webrtc.VideoEncoder
import livekit.org.webrtc.VideoEncoderFactory
import livekit.org.webrtc.WrappedVideoDecoderFactory

/**
 * Что умеет телефон — кодировать и раскодировать, аппаратно и программно (ПЛАН-ВИДЕО.md
 * В1, В4).
 *
 * ── ТРИ ИСТОЧНИКА, А НЕ ДВА ─────────────────────────────────────────────────
 *
 * - **Аппаратные** — кодеки чипа через MediaCodec. WebRTC берёт не все: H.264 на Android 9
 *   только у Qualcomm и Exynos (Honor 8S его не получает).
 * - **Программные WebRTC** — libvpx (VP8, VP9) и AV1. **Программного H.264 нет вовсе** —
 *   ни кодера, ни раскодировщика (проверено по библиотеке 2026-09-29).
 * - **Системные программные** — раскодировщики самого Android (`c2.android.*`,
 *   `OMX.google.*`). Через них H.264 раскодируется и без аппаратного. Кодеры системы
 *   WebRTC не берёт.
 */
internal object PhoneCoders {

    /** Что можно кодировать при этом переключателе. Пусто — не узнали. */
    fun encodable(hardware: Boolean): Set<VideoCodec> = runCatching {
        val soft = names(SoftwareVideoEncoderFactory().supportedCodecs)
        if (!hardware) return soft
        soft + names(HardwareVideoEncoderFactory(null, true, false).supportedCodecs)
    }.getOrDefault(emptySet())

    /** Что можно раскодировать при этом переключателе. */
    fun decodable(hardware: Boolean): Set<VideoCodec> = runCatching {
        val soft = names(SoftwareVideoDecoderFactory().supportedCodecs) +
            names(PlatformSoftwareVideoDecoderFactory(null).supportedCodecs)
        if (!hardware) return soft
        soft + names(HardwareVideoDecoderFactory(null).supportedCodecs)
    }.getOrDefault(emptySet())

    /**
     * Строка журнала: каждый кодек — чем кодируется и чем раскодируется. `апп` —
     * аппаратно, `прог` — программно WebRTC, `сист` — программно системой.
     *
     * Пишется в начале звонка всегда, а не только при беде: «видео не подаётся» разбирается
     * вопросом «а чем ещё можно было», и ответ должен лежать в том же отчёте.
     */
    fun describe(): List<Pair<String, Any?>> = runCatching {
        val hardEnc = names(HardwareVideoEncoderFactory(null, true, false).supportedCodecs)
        val softEnc = names(SoftwareVideoEncoderFactory().supportedCodecs)
        val hardDec = names(HardwareVideoDecoderFactory(null).supportedCodecs)
        val softDec = names(SoftwareVideoDecoderFactory().supportedCodecs)
        val systemDec = names(PlatformSoftwareVideoDecoderFactory(null).supportedCodecs)
        fun ways(codec: VideoCodec, vararg sources: Pair<String, Set<VideoCodec>>): String =
            sources.filter { codec in it.second }.joinToString("+") { it.first }.ifEmpty { "нет" }
        VideoCodec.entries.map { codec ->
            codec.name to (
                "код " + ways(codec, "апп" to hardEnc, "прог" to softEnc) +
                    " · раскод " + ways(codec, "апп" to hardDec, "прог" to softDec, "сист" to systemDec)
                )
        }
    }.getOrElse { listOf("причина" to (it.message ?: it::class.simpleName)) }

    private fun names(infos: Array<VideoCodecInfo>): Set<VideoCodec> =
        infos.mapNotNull { CodecChoice.fromWebRtcName(it.name) }.toSet()
}

/**
 * Фабрика кодеров с переключателем «Аппаратное кодирование» (ПЛАН-ВИДЕО.md В4).
 *
 * Переключатель читается **при создании кодера**, а кодер создаётся при публикации камеры.
 * Поэтому смена посреди звонка действует через переопубликацию — её делает движок. Список
 * кодеков для согласования не меняется: иначе комната с выключенным переключателем
 * договаривалась бы о другом, чем без него.
 *
 * Внутри — связка SDK как есть ([CustomVideoEncoderFactory]) или наша с кратностью 16
 * ([AlignedEncoderFactory]). Выключено — программный кодер WebRTC.
 */
internal class SwitchableEncoderFactory(
    makeInner: () -> VideoEncoderFactory,
    private val hardware: () -> Boolean,
) : VideoEncoderFactory {
    // ── ВСЁ — ЛЕНИВО ────────────────────────────────────────────────────────
    //
    // Фабрика создаётся до комнаты, а библиотека WebRTC грузится при её создании.
    // Программные фабрики WebRTC зовут библиотеку уже в конструкторе — созданные здесь,
    // они роняют приложение (`UnsatisfiedLinkError`, Samsung 2026-09-29). Первым к фабрике
    // обращается сама WebRTC, и библиотека к тому времени загружена.
    private val inner by lazy(makeInner)
    private val software by lazy { SoftwareVideoEncoderFactory() }

    override fun createEncoder(info: VideoCodecInfo): VideoEncoder? =
        if (hardware()) inner.createEncoder(info) else software.createEncoder(info)

    override fun getSupportedCodecs(): Array<VideoCodecInfo> = inner.supportedCodecs

    companion object {
        /** Связка кодеров комнаты: с кратностью 16 ([align] — каким способом) или та, что строит SDK. */
        fun of(egl: EglBase.Context, align: Alignment?, hardware: () -> Boolean): SwitchableEncoderFactory =
            SwitchableEncoderFactory(
                // Флаги — как у SDK: Intel VP8 — да, H.264 High — нет, программный — по нам.
                {
                    if (align != null) AlignedEncoderFactory(egl, align)
                    else CustomVideoEncoderFactory(egl, true, false, false, emptyList())
                },
                hardware,
            )
    }
}

/**
 * Фабрика раскодировщиков с переключателем «Аппаратное раскодирование» (ПЛАН-ВИДЕО.md В4).
 *
 * Включено — ровно то, что строит SDK (`WrappedVideoDecoderFactory`: аппаратный, при отказе
 * — программный). Выключено — программный WebRTC, а для H.264 — системный программный
 * Android: своего программного H.264 у WebRTC нет, а принимать его всё равно надо.
 *
 * Переключатель читается при создании раскодировщика — то есть при подписке на видео.
 * Посреди звонка движок переподписывается, и раскодировщик создаётся заново.
 */
internal class SwitchableDecoderFactory(
    egl: EglBase.Context,
    private val hardware: () -> Boolean,
) : VideoDecoderFactory {
    // Лениво — по той же причине, что у [SwitchableEncoderFactory].
    private val wrapped by lazy { WrappedVideoDecoderFactory(egl) }
    private val software by lazy { SoftwareVideoDecoderFactory() }
    private val system by lazy { PlatformSoftwareVideoDecoderFactory(egl) }

    override fun createDecoder(info: VideoCodecInfo): VideoDecoder? =
        if (hardware()) wrapped.createDecoder(info) else software.createDecoder(info) ?: system.createDecoder(info)

    override fun getSupportedCodecs(): Array<VideoCodecInfo> = wrapped.supportedCodecs
}
