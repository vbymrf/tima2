package io.tima.app.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SmsRequestBody(val phone: String)

@Serializable
data class SmsRequestResponse(
    @SerialName("request_id") val requestId: String,
    @SerialName("dev_code") val devCode: String? = null, // только dev-сервер (TIMA_DEV_SMS=1)
)

@Serializable
data class SmsVerifyBody(
    @SerialName("request_id") val requestId: String,
    val code: String,
)

@Serializable
data class SmsVerifyResponse(@SerialName("registration_token") val registrationToken: String)

@Serializable
data class RegisterBody(
    @SerialName("registration_token") val registrationToken: String,
    @SerialName("encryption_pub") val encryptionPub: String, // base64url, X25519 32 B
    @SerialName("signing_pub") val signingPub: String,       // base64url, Ed25519 32 B
)

@Serializable
data class RegisterResponse(
    @SerialName("user_id") val userId: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("access_token") val accessToken: String,
)

@Serializable
data class DeviceKeyInfo(
    @SerialName("device_id") val deviceId: String,
    @SerialName("encryption_pub") val encryptionPub: String, // base64url X25519
    @SerialName("signing_pub") val signingPub: String,       // base64url Ed25519
)

@Serializable
private data class DevicesResponse(@SerialName("devices") val devices: List<DeviceKeyInfo>)

@Serializable
private data class LookupResponse(@SerialName("user_id") val userId: String)

@Serializable
data class EscrowPubkey(
    @SerialName("escrow_key_version") val version: Int,
    @SerialName("public_key") val publicKey: String, // base64url, 1184 B (ML-KEM-768)
)

@Serializable
data class HistoryItem(
    @SerialName("message_id") val messageId: Long,
    @SerialName("envelope") val envelope: String, // base64url(protobuf Envelope), обёртка этого устройства
)

@Serializable
private data class HistoryResponse(@SerialName("messages") val messages: List<HistoryItem>)

@Serializable
private data class ApiError(val error: String = "", val message: String = "")

class TimaApiException(val code: String, message: String) : Exception(message)

/** REST-клиент TIMA (api-overview.md). Auth-контур; остальные сервисы — следующими итерациями. */
class TimaApi(private val baseUrl: String) {

    private val json = Json { ignoreUnknownKeys = true }

    /** Общий HTTP/WS-клиент; WS-сессию чата открывает ChatService через [rawClient]. */
    val rawClient = HttpClient {
        install(ContentNegotiation) { json(json) }
        install(WebSockets)
    }
    private val client get() = rawClient

    /** ws://-адрес /ws из базового http(s)://-адреса. */
    fun wsUrl(): String = baseUrl.trimEnd('/').replaceFirst("http", "ws") + "/ws"

    private suspend fun fail(response: HttpResponse): Nothing {
        val err = try {
            json.decodeFromString<ApiError>(response.bodyAsText())
        } catch (_: Throwable) {
            ApiError("http_${response.status.value}", "HTTP ${response.status}")
        }
        throw TimaApiException(err.error, err.message.ifEmpty { "HTTP ${response.status.value}" })
    }

    private suspend inline fun <reified Req, reified Resp> post(path: String, requestBody: Req): Resp {
        val response = client.post(baseUrl.trimEnd('/') + path) {
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }
        if (!response.status.isSuccess()) fail(response)
        return response.body()
    }

    suspend fun smsRequest(phone: String): SmsRequestResponse =
        post("/api/v1/auth/sms/request", SmsRequestBody(phone))

    suspend fun smsVerify(requestId: String, code: String): SmsVerifyResponse =
        post("/api/v1/auth/sms/verify", SmsVerifyBody(requestId, code))

    suspend fun register(registrationToken: String, encryptionPub: String, signingPub: String): RegisterResponse =
        post("/api/v1/auth/register", RegisterBody(registrationToken, encryptionPub, signingPub))

    // ── Под device JWT ──

    /** user_id по телефону; null — не зарегистрирован. */
    suspend fun lookupUser(token: String, phone: String): String? {
        val response = client.get(baseUrl.trimEnd('/') + "/api/v1/users/lookup") {
            bearerAuth(token)
            parameter("phone", phone)
        }
        if (response.status == HttpStatusCode.NotFound) return null
        if (!response.status.isSuccess()) fail(response)
        return response.body<LookupResponse>().userId
    }

    suspend fun listDevices(token: String, userId: String): List<DeviceKeyInfo> {
        val response = client.get(baseUrl.trimEnd('/') + "/api/v1/keys/devices") {
            bearerAuth(token)
            parameter("user_id", userId)
        }
        if (!response.status.isSuccess()) fail(response)
        return response.body<DevicesResponse>().devices
    }

    suspend fun escrowPubkey(token: String): EscrowPubkey {
        val response = client.get(baseUrl.trimEnd('/') + "/api/v1/escrow/pubkey") { bearerAuth(token) }
        if (!response.status.isSuccess()) fail(response)
        return response.body()
    }

    suspend fun listMessages(token: String, chatId: String, limit: Int = 100): List<HistoryItem> {
        val response = client.get(baseUrl.trimEnd('/') + "/api/v1/chats/$chatId/messages") {
            bearerAuth(token)
            parameter("limit", limit)
        }
        if (!response.status.isSuccess()) fail(response)
        return response.body<HistoryResponse>().messages
    }

    /** POST /messages: конверт как protobuf; clientMsgId — дедуп повторной отправки. */
    suspend fun postEnvelope(token: String, envelope: ByteArray, clientMsgId: String) {
        val response = client.post(baseUrl.trimEnd('/') + "/api/v1/messages") {
            bearerAuth(token)
            header("X-Client-Msg-Id", clientMsgId)
            contentType(ContentType("application", "x-protobuf"))
            setBody(envelope)
        }
        if (!response.status.isSuccess()) fail(response)
    }
}
