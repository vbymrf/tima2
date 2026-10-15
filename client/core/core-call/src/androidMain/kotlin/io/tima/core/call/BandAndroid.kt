package io.tima.core.call

import io.livekit.android.room.track.LocalVideoTrack
import livekit.org.webrtc.RTCStats
import livekit.org.webrtc.RtpSender

/**
 * Передатчик своего видео — ради двух вещей ПЛАН-(ПЗ): видеть, остановил ли его сервер (dynacast,
 * ПЗ3), и менять битрейт на ходу, без перевыпуска камеры (ПЗ7).
 *
 * У `livekit-android` 2.28.2 он есть, но объявлен `internal` (`LocalVideoTrack.sender`), и из
 * нашего модуля Kotlin к нему не пускает. В байт-коде это обычный открытый метод
 * `getSender$livekit_android_sdk_release` — его и зовём отражением. При обновлении SDK имя может
 * смениться: тогда [of] вернёт `null`, ПЗ3 напишет «кодер — не знаем», а ПЗ7 — что битрейт не
 * поставлен. Звонку это не мешает.
 */
internal object VideoSender {
    private val getter by lazy {
        runCatching { LocalVideoTrack::class.java.getMethod("getSender\$livekit_android_sdk_release") }.getOrNull()
    }

    fun of(track: LocalVideoTrack): RtpSender? = runCatching { getter?.invoke(track) as? RtpSender }.getOrNull()

    /** Шлёт ли кодер: `false` — сервер сказал «никому не нужно» (dynacast). */
    fun active(sender: RtpSender): Boolean? =
        runCatching { sender.parameters.encodings.firstOrNull()?.active }.getOrNull()

    fun maxKbps(sender: RtpSender): Int? =
        runCatching { sender.parameters.encodings.firstOrNull()?.maxBitrateBps?.let { it / 1000 } }.getOrNull()

    /** Поставить потолок битрейта всем копиям видео. `false` — WebRTC не принял. */
    fun setMaxKbps(sender: RtpSender, kbps: Int): Boolean = runCatching {
        val p = sender.parameters
        if (p.encodings.isEmpty()) return@runCatching false
        for (e in p.encodings) e.maxBitrateBps = kbps * 1000
        sender.setParameters(p)
    }.getOrDefault(false)
}

/**
 * Выбранная пара кандидатов — та, по которой идёт звонок. В ней WebRTC держит оценку полосы
 * (`availableIncomingBitrate`, `availableOutgoingBitrate`) и счётчики всего соединения.
 */
internal fun selectedPair(report: Collection<RTCStats>): RTCStats? {
    val pairs = report.filter { it.type == "candidate-pair" }
    return pairs.firstOrNull { it.members["nominated"] == true && it.members["state"]?.toString() == "succeeded" }
        ?: pairs.firstOrNull { it.members["state"]?.toString() == "succeeded" }
}

/** Оценка приёма WebRTC, кбит/с; `null` — WebRTC её не дал. */
internal fun incomingEstimateKbps(pair: RTCStats?): Double? =
    (pair?.members?.get("availableIncomingBitrate") as? Number)?.toDouble()?.takeIf { it > 0 }?.div(1000)
