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

/** Расшифрованное сообщение для UI. Для группы [chatId] = group_id, [group] = true. */
data class ChatMessage(
    val chatId: String,
    val messageId: Long,
    val senderId: String,
    val text: String,
    val createdAtMs: Long,
    val mine: Boolean,
    val media: MediaAttachment? = null,
    val group: Boolean = false,
)

/** Группа в списке на главном экране. */
data class GroupSummary(val groupId: String, val title: String, val myRole: String)

/** Запрос собеседника на восстановление: показать пользователю «разрешить?». */
data class RecoveryConsent(val chatId: String, val requesterDevice: String, val requesterEncPub: String)

/** Входящий звонок (WS call.incoming). */
data class IncomingCall(val callId: String, val room: String, val kind: String, val fromUserId: String)

/** Смена состояния звонка (WS call.state): answered|ended|missed. */
data class CallStateEvent(val callId: String, val state: String)

/** Данные для подключения к комнате LiveKit (звонок или аудио-чат). */
data class CallConnection(val callId: String, val room: String, val url: String, val token: String)

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

    /** Задать своё публичное имя (собеседники увидят его вместо номера). */
    suspend fun setMyName(name: String)

    /** Публичные имена по user_id (кэш); id без имени в карту не попадает. */
    suspend fun resolveNames(ids: List<String>): Map<String, String>

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

    /**
     * Восстановление истории личного чата у своих устройств (авто) или собеседника
     * (с согласия). Возвращает историю после восстановления (ADR-0010 §этап 2).
     */
    suspend fun recoverChatHistory(peerUserId: String): List<ChatMessage>

    /** Запросы собеседника на выдачу истории — UI показывает диалог согласия. */
    val consentRequests: Flow<RecoveryConsent>

    /** Согласиться отдать историю чата запросившему устройству (после подтверждения в UI). */
    suspend fun approveRecovery(consent: RecoveryConsent)

    // ── Звонки 1:1 (calls-livekit.md; сигналинг + LiveKit-токен, без живого медиа) ──

    val incomingCalls: Flow<IncomingCall>
    val callStates: Flow<CallStateEvent>

    /** Начать звонок собеседнику (kind: audio|video); возвращает данные подключения. */
    suspend fun startCall(peerUserId: String, kind: String): CallConnection

    /** Ответить на входящий звонок; возвращает данные подключения. */
    suspend fun answerCall(callId: String): CallConnection

    /** Завершить/отклонить звонок. */
    suspend fun endCall(callId: String)

    // ── Группы (crypto-protocol §4: GK генерирует клиент-админ) ──

    suspend fun myGroups(): List<GroupSummary>

    /** Создаёт private-группу, добавляет участников по телефонам и ротирует GK v1. */
    suspend fun createGroup(title: String, memberPhones: List<String>): GroupSummary

    /** История группы с расшифровкой GK нужных версий, старые → новые. */
    suspend fun groupHistory(groupId: String): List<ChatMessage>

    /**
     * Восстановление истории группы: запрос недостающих версий GK у участников
     * (ADR-0010 §этап 1), ожидание обёрток, повторная расшифровка. Возвращает историю
     * после восстановления.
     */
    suspend fun recoverGroupHistory(groupId: String): List<ChatMessage>

    /** Шифрует GK текущей версии, подписывает group_message_canonical и отправляет. */
    suspend fun sendGroup(groupId: String, text: String): ChatMessage

    fun close()
}

expect fun createChatClient(session: Session): ChatClient
