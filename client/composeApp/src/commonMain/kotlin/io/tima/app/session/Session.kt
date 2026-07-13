package io.tima.app.session

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Локальная сессия устройства.
 *
 * deviceSecret — 32-байтный seed `KodiumPrivateKey` (из него выводятся и X25519, и Ed25519).
 * TODO(фаза 3 roadmap): перенести секрет в Android Keystore / Secure Enclave;
 * сейчас — файл во внутреннем хранилище приложения (dev).
 */
@Serializable
data class Session(
    @SerialName("server_url") val serverUrl: String,
    @SerialName("user_id") val userId: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("access_token") val accessToken: String,
    @SerialName("device_secret_b64") val deviceSecretB64: String,
)

/** Сохранённый диалог для списка чатов на главном экране. */
@Serializable
data class ChatEntry(
    @SerialName("peer_phone") val peerPhone: String,
    @SerialName("peer_user_id") val peerUserId: String,
)

object SessionCodec {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private const val SESSION = "session.json"
    private const val CHATS = "chats.json"

    fun load(): Session? = SessionStorage.read(SESSION)?.let {
        try { json.decodeFromString<Session>(it) } catch (_: Throwable) { null }
    }

    fun save(session: Session) = SessionStorage.write(SESSION, json.encodeToString(Session.serializer(), session))

    /** Выход: сессия и список чатов стираются вместе (чаты принадлежат аккаунту). */
    fun clear() {
        SessionStorage.write(SESSION, null)
        SessionStorage.write(CHATS, null)
    }

    fun loadChats(): List<ChatEntry> = SessionStorage.read(CHATS)?.let {
        try { json.decodeFromString<List<ChatEntry>>(it) } catch (_: Throwable) { emptyList() }
    } ?: emptyList()

    /** Добавляет диалог наверх списка (последний открытый — первый). */
    fun rememberChat(entry: ChatEntry) {
        val rest = loadChats().filter { it.peerUserId != entry.peerUserId }
        SessionStorage.write(CHATS, json.encodeToString(listOf(entry) + rest))
    }
}

/** Платформенное хранилище именованных файлов приложения. */
expect object SessionStorage {
    fun read(name: String): String?
    fun write(name: String, text: String?)
}

/** Адрес dev-сервера по умолчанию: эмулятор Android видит хост как 10.0.2.2. */
expect fun defaultServerUrl(): String
