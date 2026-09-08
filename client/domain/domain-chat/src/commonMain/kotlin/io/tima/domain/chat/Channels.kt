package io.tima.domain.chat

/**
 * Создание канала (ПЛАН-СООБЩЕСТВ С5, мастер создания).
 *
 * **Канал — односторонняя трансляция:** пишет владелец, читают подписчики. Писать в него
 * нельзя вовсе — можно комментировать (ADR-0024), и потому в мастере два вопроса, а не
 * три: виден ли канал в каталоге и принимает ли он обсуждения.
 *
 * «По подписке» значит «не в каталоге»: канал находят по ссылке, а не листая список. Это
 * не отдельный вид канала, а одно поле — иначе пришлось бы заводить два вида, ведущих
 * себя одинаково во всём остальном.
 */
class CreateChannel(private val channels: Channels) {

    suspend fun create(
        title: String,
        description: String = "",
        inCatalogue: Boolean = true,
        comments: Boolean = true,
    ): ChannelStep {
        val name = title.trim()
        if (name.isEmpty()) return ChannelStep.BadTitle("Название обязательно")
        if (name.length > MAX_TITLE) return ChannelStep.BadTitle("Название длиннее $MAX_TITLE знаков")
        return channels.create(name, description.trim(), inCatalogue, comments)
    }

    companion object {
        const val MAX_TITLE: Int = 200
    }
}

/** Что вышло из создания канала. */
sealed interface ChannelStep {
    data class Created(val channelId: String) : ChannelStep
    data class BadTitle(val reason: String) : ChannelStep
    data class Offline(val retryAfterMs: Long) : ChannelStep
    data class Refused(val reason: String) : ChannelStep
}

/** Порт к серверу: каналы. */
interface Channels {
    suspend fun create(
        title: String,
        description: String,
        inCatalogue: Boolean,
        comments: Boolean,
    ): ChannelStep
}
