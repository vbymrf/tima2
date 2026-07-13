package io.tima.app.chat

import io.tima.app.session.Session
import kotlinx.coroutines.flow.Flow

/** Расшифрованное сообщение для UI. */
data class ChatMessage(
    val chatId: String,
    val messageId: Long,
    val senderId: String,
    val text: String,
    val createdAtMs: Long,
    val mine: Boolean,
)

/**
 * Клиент мессенджера на одну сессию устройства: ЕДИНОЕ WS-соединение
 * (websocket-events.md: одно на устройство) с реконнектом, поток расшифрованных
 * сообщений всех чатов; конверты — crypto-protocol.md §3 (слои 1+2+4 + подпись).
 */
interface ChatClient {
    /** Открывает WS-цикл с догоном (sync.pull) и реконнектом; вызывать один раз. */
    suspend fun start()

    /** Live-поток входящих по всем чатам (уже расшифрованных). */
    val messages: Flow<ChatMessage>

    /** Детерминированный chat_id личного чата с собеседником. */
    fun chatIdWith(peerUserId: String): String

    /** История чата с расшифровкой, старые → новые. */
    suspend fun history(peerUserId: String): List<ChatMessage>

    /** Шифрует, подписывает и отправляет; возвращает своё сообщение для UI. */
    suspend fun send(peerUserId: String, text: String): ChatMessage

    fun close()
}

expect fun createChatClient(session: Session): ChatClient
