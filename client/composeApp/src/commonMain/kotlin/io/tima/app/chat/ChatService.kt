package io.tima.app.chat

import io.tima.app.session.Session
import kotlinx.coroutines.flow.Flow

/** Расшифрованное сообщение для UI. */
data class ChatMessage(
    val messageId: Long,
    val senderId: String,
    val text: String,
    val createdAtMs: Long,
    val mine: Boolean,
)

/**
 * Чат 1-на-1: конверт по crypto-protocol.md §3 (слои 1+2+4 + подпись), история REST,
 * live-приём по WS (websocket-events.md: auth → sync.pull → события + ack).
 */
interface ChatService {
    val chatId: String
    val peerUserId: String

    /** Открывает WS и запускает приём; вызывать один раз, после — [incoming]. */
    suspend fun start()

    /** История чата с расшифровкой, старые → новые. */
    suspend fun history(): List<ChatMessage>

    /** Шифрует, подписывает и отправляет; возвращает своё сообщение для UI. */
    suspend fun send(text: String): ChatMessage

    /** Live-поток входящих (уже расшифрованных) сообщений ЭТОГО чата. */
    val incoming: Flow<ChatMessage>

    fun close()
}

expect fun createChatService(session: Session, peerUserId: String): ChatService
