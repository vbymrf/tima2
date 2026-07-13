package io.tima.app.chat

import io.tima.app.session.Session
import kotlinx.coroutines.flow.Flow

/** Вложение сообщения; media_key пришёл под шифрованием конверта. */
data class MediaAttachment(
    val mediaId: String,
    val mediaKey: ByteArray,
    val mime: String,
    val sizeBytes: Long,
)

/** Расшифрованное сообщение для UI. */
data class ChatMessage(
    val chatId: String,
    val messageId: Long,
    val senderId: String,
    val text: String,
    val createdAtMs: Long,
    val mine: Boolean,
    val media: MediaAttachment? = null,
)

/** Что показать в списке чатов как последнее сообщение. */
fun ChatMessage.preview(): String = when {
    media != null && text.isEmpty() -> "📷 Фото"
    media != null -> "📷 $text"
    else -> text
}

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

    /**
     * Фото: шифрование файла (MediaCipher) → MinIO по presigned PUT →
     * сообщение CK_IMAGE с MediaRef (media_key под шифрованием конверта).
     */
    suspend fun sendImage(peerUserId: String, imageBytes: ByteArray, mime: String, caption: String = ""): ChatMessage

    /** Скачивает и расшифровывает вложение (с кэшем в памяти). */
    suspend fun loadMedia(attachment: MediaAttachment): ByteArray

    fun close()
}

expect fun createChatClient(session: Session): ChatClient
