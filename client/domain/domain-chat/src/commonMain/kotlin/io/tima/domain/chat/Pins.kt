package io.tima.domain.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Закреплённое сообщение (doc_mig/ПЛАН-(ЗО)-ЗАКРЕПЛЕНИЯ-И-ОПИСАНИЯ.md, ЗО3): одно на личную переписку
 * и группу, сверху. Сервер знает номер открыто — «иначе никто не узнает» (заказчик 2026-10-18);
 * здесь — его снимок на этом устройстве: что прочитано при открытии, что пришло событием.
 *
 * Право решает сервер (личная — любой из двух, группа — модератор и выше); экран лишь не предлагает
 * кнопку тому, у кого права нет.
 */
class ChatPins(private val remote: RemotePins) {

    private val board = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** Переписка → номер закреплённого. Нет ключа — не знаем или ничего не закреплено. */
    val pinned: StateFlow<Map<String, Long>> = board.asStateFlow()

    /** Прочитать с сервера при открытии переписки. Без сети — остаётся прежнее. */
    suspend fun refresh(chatId: String, group: Boolean) {
        remote.read(chatId, group)?.let { applied(chatId, it) }
    }

    suspend fun pin(chatId: String, group: Boolean, serverId: Long): PinStep =
        remote.set(chatId, group, serverId).also { if (it == PinStep.Done) applied(chatId, serverId) }

    suspend fun unpin(chatId: String, group: Boolean): PinStep =
        remote.clear(chatId, group).also { if (it == PinStep.Done) applied(chatId, 0) }

    /** Событие `message.pinned`: номер или 0 — сняли. */
    fun applied(chatId: String, serverId: Long) = board.update { it + (chatId to serverId) }

    /** Удалили у всех — закреплённое открепляется само: сервер снял его в самом удалении. */
    fun deleted(chatId: String, serverId: Long) = board.update { if (it[chatId] == serverId) it + (chatId to 0L) else it }
}

/** Ответ сервера на закрепление и снятие. */
sealed interface PinStep {
    data object Done : PinStep
    /** `not_moderator` — в группе закрепляет модератор и выше; `message_not_pinnable` — сообщения нет. */
    data class Refused(val code: String) : PinStep
    data object Offline : PinStep
}

/** Порт к серверу: `…/chats/{id}/pin` и `…/groups/{id}/pin`. Реализуется `core-network`. */
interface RemotePins {
    /** Номер закреплённого, 0 — ничего; `null` — сеть или отказ. */
    suspend fun read(chatId: String, group: Boolean): Long?
    suspend fun set(chatId: String, group: Boolean, serverId: Long): PinStep
    suspend fun clear(chatId: String, group: Boolean): PinStep
}
