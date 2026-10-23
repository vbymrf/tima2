package io.tima.core.media

/** На iOS приложения пока нет — голосовых тоже (ПЛАН-(ВМ)). */
actual object VoiceDevices {
    actual fun microphone(name: String?): PcmSource? = null
    actual fun speaker(): PcmSink? = null
    actual fun encoder(rate: Int): VoiceEncoder? = null
    actual fun decoder(): VoiceDecoder? = null
}
