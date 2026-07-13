package io.tima.app.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
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
private data class ApiError(val error: String = "", val message: String = "")

class TimaApiException(val code: String, message: String) : Exception(message)

/** REST-клиент TIMA (api-overview.md). Auth-контур; остальные сервисы — следующими итерациями. */
class TimaApi(private val baseUrl: String) {

    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient {
        install(ContentNegotiation) { json(json) }
    }

    private suspend inline fun <reified Req, reified Resp> post(path: String, requestBody: Req): Resp {
        val response = client.post(baseUrl.trimEnd('/') + path) {
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }
        if (!response.status.isSuccess()) {
            val err = try {
                json.decodeFromString<ApiError>(response.bodyAsText())
            } catch (_: Throwable) {
                ApiError("http_${response.status.value}", "HTTP ${response.status}")
            }
            throw TimaApiException(err.error, err.message.ifEmpty { "HTTP ${response.status.value}" })
        }
        return response.body()
    }

    suspend fun smsRequest(phone: String): SmsRequestResponse =
        post("/api/v1/auth/sms/request", SmsRequestBody(phone))

    suspend fun smsVerify(requestId: String, code: String): SmsVerifyResponse =
        post("/api/v1/auth/sms/verify", SmsVerifyBody(requestId, code))

    suspend fun register(registrationToken: String, encryptionPub: String, signingPub: String): RegisterResponse =
        post("/api/v1/auth/register", RegisterBody(registrationToken, encryptionPub, signingPub))
}
