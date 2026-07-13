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

object SessionCodec {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun load(): Session? = SessionStorage.read()?.let {
        try { json.decodeFromString<Session>(it) } catch (_: Throwable) { null }
    }

    fun save(session: Session) = SessionStorage.write(json.encodeToString(Session.serializer(), session))

    fun clear() = SessionStorage.write(null)
}

/** Платформенное хранилище одного файла session.json. */
expect object SessionStorage {
    fun read(): String?
    fun write(text: String?)
}

/** Адрес dev-сервера по умолчанию: эмулятор Android видит хост как 10.0.2.2. */
expect fun defaultServerUrl(): String
