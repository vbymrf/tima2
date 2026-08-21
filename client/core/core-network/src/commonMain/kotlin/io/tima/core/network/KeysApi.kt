package io.tima.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Ключи устройств собеседника и свои — `GET /api/v1/keys/devices?user_id=…`.
 *
 * **Зачем это отдельная ручка и почему без неё нельзя отправить.** Ключ сообщения
 * оборачивается **на каждое устройство** получателя и на **свои остальные**: без своих
 * второе устройство не прочитает отправленное с первого. Значит перед отправкой список
 * устройств обязан быть свежим, иначе новое устройство собеседника не прочитает
 * ничего, и выглядеть это будет как «сообщения не приходят на телефон, а на ПК
 * приходят».
 *
 * Контракт из `internal/api/auth.go`: `{user_id, devices:[{device_id, encryption_pub,
 * signing_pub}]}`, ключи — **base64url без выравнивания**.
 */
class KeysApi(
    private val route: ServerRoute,
    private val client: HttpClient,
    private val token: () -> String,
) {

    suspend fun devicesOf(userId: String): DeviceKeysResult {
        require(userId.isNotBlank()) { "user_id пустой" }
        val response = try {
            client.get(route.api("/api/v1/keys/devices")) {
                header("Authorization", "Bearer ${token()}")
                parameter("user_id", userId)
            }
        } catch (e: Throwable) {
            return DeviceKeysResult.Offline(classifyFailure(e))
        }

        val body = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
        if (response.status != HttpStatusCode.OK) {
            return DeviceKeysResult.Refused(
                response.status.value,
                body?.get("code")?.jsonPrimitive?.content ?: "без кода",
            )
        }
        // Испорченный ответ — исход, а не исключение: вход из сети недоверенный, и
        // остальные случаи здесь тоже исходы. Но причина доносится дословно: «ключ не
        // того размера» и «поля нет» — разные беды, и искать их надо в разных местах.
        val devices = runCatching {
            body?.get("devices")?.jsonArray?.map { it.jsonObject.toDevice() }
                ?: error("в ответе нет devices")
        }.getOrElse {
            return DeviceKeysResult.Refused(response.status.value, "ответ не разобран: " + it.message)
        }

        // Пустой список — не ошибка сети и не «нет такого человека»: у аккаунта могли
        // отозвать все устройства. Отправлять при этом некому, и решать это выше.
        return DeviceKeysResult.Devices(devices)
    }

    private fun JsonObject.toDevice(): DeviceKeyRecord {
        val id = this["device_id"]!!.jsonPrimitive.content
        val enc = decodeBase64Url(this["encryption_pub"]!!.jsonPrimitive.content)
        val sig = decodeBase64Url(this["signing_pub"]!!.jsonPrimitive.content)
        require(enc != null && sig != null) { "ключи устройства $id не base64url" }
        require(enc.size == KEY_BYTES && sig.size == KEY_BYTES) {
            "ключи устройства $id не по $KEY_BYTES байт: ${enc.size}/${sig.size}"
        }
        return DeviceKeyRecord(id, enc, sig)
    }

    private companion object {
        const val KEY_BYTES = 32
    }
}

/** Устройство получателя: то, что нужно для обёртки ключа и проверки подписи. */
data class DeviceKeyRecord(
    val deviceId: String,
    /** X25519 — им оборачивается ключ сообщения. */
    val encryptionPub: ByteArray,
    /** Ed25519 — им проверяется подпись входящего от этого устройства. */
    val signingPub: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is DeviceKeyRecord &&
        deviceId == other.deviceId &&
        encryptionPub.contentEquals(other.encryptionPub) &&
        signingPub.contentEquals(other.signingPub)

    override fun hashCode(): Int {
        var h = deviceId.hashCode()
        h = 31 * h + encryptionPub.contentHashCode()
        h = 31 * h + signingPub.contentHashCode()
        return h
    }
}

sealed interface DeviceKeysResult {
    data class Devices(val devices: List<DeviceKeyRecord>) : DeviceKeysResult
    data class Refused(val status: Int, val code: String) : DeviceKeysResult
    data class Offline(val link: LinkState) : DeviceKeysResult
}
