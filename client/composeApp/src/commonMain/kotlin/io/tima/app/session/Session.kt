@file:OptIn(ExperimentalEncodingApi::class)

package io.tima.app.session

import io.tima.app.platform.SecretVault
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Локальная сессия устройства.
 *
 * deviceSecret — 32-байтный seed `KodiumPrivateKey` (из него выводятся и X25519, и Ed25519).
 * На диске секрет лежит завёрнутым в [SecretVault] (Android Keystore; `secret_vaulted`);
 * в памяти — открытым (нужен конвейеру конверта).
 */
@Serializable
data class Session(
    @SerialName("server_url") val serverUrl: String,
    @SerialName("phone") val phone: String = "", // свой номер: показывается в UI (ВП скроют его — фаза ВП)
    @SerialName("user_id") val userId: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("access_token") val accessToken: String,
    @SerialName("device_secret_b64") val deviceSecretB64: String,
    @SerialName("secret_vaulted") val secretVaulted: Boolean = false,
)

/** Сохранённый диалог для списка чатов на главном экране. */
@Serializable
data class ChatEntry(
    @SerialName("title") val title: String,          // телефон или короткий user_id, если писал незнакомец
    @SerialName("peer_user_id") val peerUserId: String,
    @SerialName("chat_id") val chatId: String,
    @SerialName("last_text") val lastText: String = "",
    @SerialName("last_at_ms") val lastAtMs: Long = 0,
    @SerialName("unread") val unread: Int = 0,
)

object SessionCodec {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val b64url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
    private const val SESSION = "session.json"
    private const val CHATS = "chats.json"

    /** Возвращает сессию с открытым секретом в памяти; старый plaintext-файл мигрирует в vault. */
    fun load(): Session? = SessionStorage.read(SESSION)?.let {
        try {
            val stored = json.decodeFromString<Session>(it)
            if (stored.secretVaulted) {
                stored.copy(
                    deviceSecretB64 = b64url.encode(SecretVault.reveal(b64url.decode(stored.deviceSecretB64))),
                    secretVaulted = false,
                )
            } else {
                save(stored) // миграция: перезаписать на диске завёрнутым
                stored
            }
        } catch (_: Throwable) {
            null // vault-ключ утерян или файл побит — повторный вход
        }
    }

    /** На диск секрет уходит только через [SecretVault]. */
    fun save(session: Session) {
        val vaulted = session.copy(
            deviceSecretB64 = b64url.encode(SecretVault.protect(b64url.decode(session.deviceSecretB64))),
            secretVaulted = true,
        )
        SessionStorage.write(SESSION, json.encodeToString(Session.serializer(), vaulted))
    }

    /** Выход: сессия и список чатов стираются вместе (чаты принадлежат аккаунту). */
    fun clear() {
        SessionStorage.write(SESSION, null)
        SessionStorage.write(CHATS, null)
    }

    fun loadChats(): List<ChatEntry> = SessionStorage.read(CHATS)?.let {
        try { json.decodeFromString<List<ChatEntry>>(it) } catch (_: Throwable) { emptyList() }
    } ?: emptyList()

    private fun saveChats(chats: List<ChatEntry>) =
        SessionStorage.write(CHATS, json.encodeToString(chats))

    /** Добавляет/поднимает диалог, сохраняя превью и счётчик существующей записи. */
    fun rememberChat(entry: ChatEntry): List<ChatEntry> {
        val existing = loadChats().firstOrNull { it.peerUserId == entry.peerUserId }
        val merged = existing?.copy(title = entry.title) ?: entry
        val chats = listOf(merged) + loadChats().filter { it.peerUserId != entry.peerUserId }
        saveChats(chats)
        return chats
    }

    /**
     * Новое сообщение чата: превью + подъём наверх; unread растёт, если чат не открыт.
     * Сообщение незнакомца создаёт запись (title — короткий user_id, телефон неизвестен).
     */
    fun noteMessage(chatId: String, peerUserId: String, text: String, atMs: Long, isOpen: Boolean): List<ChatEntry> {
        val current = loadChats()
        val entry = current.firstOrNull { it.chatId == chatId }
            ?: ChatEntry(title = peerUserId.take(8) + "…", peerUserId = peerUserId, chatId = chatId)
        val updated = entry.copy(
            lastText = text,
            lastAtMs = atMs,
            unread = if (isOpen) 0 else entry.unread + 1,
        )
        val chats = listOf(updated) + current.filter { it.chatId != chatId }
        saveChats(chats)
        return chats
    }

    /** Чат открыт — непрочитанное сброшено. */
    fun markRead(chatId: String): List<ChatEntry> {
        val chats = loadChats().map { if (it.chatId == chatId) it.copy(unread = 0) else it }
        saveChats(chats)
        return chats
    }
}

/** Платформенное хранилище именованных файлов приложения. */
expect object SessionStorage {
    fun read(name: String): String?
    fun write(name: String, text: String?)
}

/** Адрес dev-сервера по умолчанию: эмулятор Android видит хост как 10.0.2.2. */
expect fun defaultServerUrl(): String
