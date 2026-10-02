package io.tima.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.http.contentType
import io.ktor.http.ContentType
import io.ktor.client.request.setBody
import io.ktor.client.request.put
import io.ktor.client.request.post
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
        // Цепочка доверия (ДУ3). Старый сервер её не отдаёт — тогда режим «off», и клиент
        // ведёт себя как прежде.
        val identity = body?.get("identity_pub")?.jsonPrimitive?.content?.let { decodeBase64Url(it) }
            ?.takeIf { it.size == KEY_BYTES }
        val signingKeys = runCatching {
            body?.get("signing_keys")?.jsonArray?.mapNotNull { el ->
                val o = el.jsonObject
                val id = o["ask_id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val pub = o["ask_pub"]?.jsonPrimitive?.content?.let { decodeBase64Url(it) } ?: return@mapNotNull null
                val sig = o["ask_sig"]?.jsonPrimitive?.content?.let { decodeBase64Url(it) } ?: return@mapNotNull null
                SigningKeyRecord(id, pub, sig)
            }.orEmpty()
        }.getOrDefault(emptyList())
        val mode = body?.get("trust_mode")?.jsonPrimitive?.content ?: TRUST_OFF

        // Пустой список — не ошибка сети и не «нет такого человека»: у аккаунта могли
        // отозвать все устройства. Отправлять при этом некому, и решать это выше.
        return DeviceKeysResult.Devices(devices, identity, signingKeys, mode)
    }

    private fun JsonObject.toDevice(): DeviceKeyRecord {
        val id = this["device_id"]!!.jsonPrimitive.content
        val enc = decodeBase64Url(this["encryption_pub"]!!.jsonPrimitive.content)
        val sig = decodeBase64Url(this["signing_pub"]!!.jsonPrimitive.content)
        require(enc != null && sig != null) { "ключи устройства $id не base64url" }
        require(enc.size == KEY_BYTES && sig.size == KEY_BYTES) {
            "ключи устройства $id не по $KEY_BYTES байт: ${enc.size}/${sig.size}"
        }
        val certBy = this["cert_by"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }
        val certSig = this["cert_sig"]?.jsonPrimitive?.content?.let { decodeBase64Url(it) }
        return DeviceKeyRecord(
            id, enc, sig,
            certBy = certBy?.takeIf { certSig != null },
            certAskId = this["cert_ask_id"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() },
            certSig = certSig?.takeIf { certBy != null },
        )
    }

    /**
     * Телефон, на котором человек ввёл фразу, заводит свой ключ подписи устройств и заверяет
     * им себя — `POST /users/me/signing-keys` (ДУ2).
     */
    suspend fun issueSigningKey(askPub: ByteArray, askSig: ByteArray, deviceCertSig: ByteArray): TrustCallResult =
        trustCall {
            client.post(route.api("/api/v1/users/me/signing-keys")) {
                header("Authorization", "Bearer ${token()}")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"ask_pub":"${encodeBase64Url(askPub)}","ask_sig":"${encodeBase64Url(askSig)}",""" +
                        """"device_cert_sig":"${encodeBase64Url(deviceCertSig)}"}""",
                )
            }
        }

    /** Заверить своё другое устройство — `PUT /devices/{id}/certificate` (ДУ2). */
    suspend fun certifyDevice(deviceId: String, by: String, signature: ByteArray): TrustCallResult =
        trustCall {
            client.put(route.api("/api/v1/devices/$deviceId/certificate")) {
                header("Authorization", "Bearer ${token()}")
                contentType(ContentType.Application.Json)
                setBody("""{"by":"$by","sig":"${encodeBase64Url(signature)}"}""")
            }
        }

    private suspend fun trustCall(call: suspend () -> io.ktor.client.statement.HttpResponse): TrustCallResult {
        val response = try {
            call()
        } catch (e: Throwable) {
            return TrustCallResult.Offline(classifyFailure(e))
        }
        if (response.status.value in 200..299) return TrustCallResult.Done
        val code = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject["code"]?.jsonPrimitive?.content }
            .getOrNull() ?: "без кода"
        return TrustCallResult.Refused(response.status.value, code)
    }

    private companion object {
        const val KEY_BYTES = 32
        const val TRUST_OFF = "off"
    }
}

/** Ключ подписи устройств аккаунта и его свидетельство ключом личности. */
class SigningKeyRecord(val askId: String, val askPub: ByteArray, val askSig: ByteArray)

/** Чем кончился вызов доверия к устройствам. */
sealed interface TrustCallResult {
    data object Done : TrustCallResult
    data class Refused(val status: Int, val code: String) : TrustCallResult
    data class Offline(val link: LinkState) : TrustCallResult
}

/** Устройство получателя: то, что нужно для обёртки ключа и проверки подписи. */
data class DeviceKeyRecord(
    val deviceId: String,
    /** X25519 — им оборачивается ключ сообщения. */
    val encryptionPub: ByteArray,
    /** Ed25519 — им проверяется подпись входящего от этого устройства. */
    val signingPub: ByteArray,
    /** Свидетельство (ДУ3): кем заверено — `ask` / `identity`; `null` — не заверено. */
    val certBy: String? = null,
    val certAskId: String? = null,
    val certSig: ByteArray? = null,
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
    /**
     * @param identityPub ключ личности аккаунта — корень цепочки доверия; `null` — у аккаунта
     *   нет фразы или сервер старый.
     * @param signingKeys действующие ключи подписи устройств со свидетельствами.
     * @param trustMode режим доверия сервера: `off` / `record` / `require` (ДУ3, Р24).
     */
    data class Devices(
        val devices: List<DeviceKeyRecord>,
        val identityPub: ByteArray? = null,
        val signingKeys: List<SigningKeyRecord> = emptyList(),
        val trustMode: String = "off",
    ) : DeviceKeysResult
    data class Refused(val status: Int, val code: String) : DeviceKeysResult
    data class Offline(val link: LinkState) : DeviceKeysResult
}
