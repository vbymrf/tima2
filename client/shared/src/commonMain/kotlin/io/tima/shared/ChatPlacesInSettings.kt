package io.tima.shared

import io.tima.domain.chat.ChatPlaces
import kotlinx.coroutines.flow.first

/**
 * Где остановились в переписках (ПЛАН-(МП) М2) — в настройках устройства аккаунта: `chat.place.<переписка>` = номер
 * сообщения у верхнего края при выходе; 0 — внизу. База у каждого аккаунта своя — места тоже (М6).
 */
internal class ChatPlacesInSettings(private val settings: io.tima.domain.chat.Settings) : ChatPlaces {
    override suspend fun load(chatId: String): Long = settings.all().first()[KEY + chatId]?.toLongOrNull() ?: 0L

    override suspend fun save(chatId: String, serverId: Long) = settings.put(KEY + chatId, serverId.toString())

    private companion object {
        const val KEY = "chat.place."
    }
}
