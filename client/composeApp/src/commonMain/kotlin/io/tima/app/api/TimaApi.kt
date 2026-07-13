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
import io.ktor.client.request.put
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
import kotlinx.serialization.json.JsonObject

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
private data class MediaInitBody(
    @SerialName("size_bytes") val sizeBytes: Long,
    val mime: String,
    @SerialName("is_encrypted") val isEncrypted: Boolean,
)

@Serializable
data class MediaInitResponse(
    @SerialName("media_id") val mediaId: String,
    @SerialName("upload_urls") val uploadUrls: List<String> = emptyList(),
)

@Serializable
private data class MediaCompleteBody(@SerialName("media_id") val mediaId: String)

@Serializable
private data class MediaUrlResponse(@SerialName("urls") val urls: List<String>)

@Serializable
data class GroupDto(
    @SerialName("group_id") val groupId: String,
    val kind: String,
    val title: String,
    @SerialName("my_role") val myRole: String = "",
)

@Serializable
private data class GroupsResponse(val groups: List<GroupDto>)

@Serializable
private data class CreateGroupBody(val kind: String, val title: String)

@Serializable
private data class CreateGroupResponse(@SerialName("group_id") val groupId: String)

@Serializable
private data class AddMemberBody(@SerialName("user_id") val userId: String)

@Serializable
data class EscrowDto(
    @SerialName("mlkem_ct") val mlkemCt: String,
    @SerialName("wrapped_message_key") val wrappedMessageKey: String,
    @SerialName("escrow_key_version") val escrowKeyVersion: Int,
)

@Serializable
data class WrappedKeyDto(val recipient: String, val wrapped: String)

@Serializable
private data class RotateBody(
    @SerialName("gk_version") val gkVersion: Int,
    val reason: String,
    @SerialName("sender_ephemeral_pub") val senderEphemeralPub: String,
    val escrow: EscrowDto,
    @SerialName("wrapped_keys") val wrappedKeys: List<WrappedKeyDto>,
)

@Serializable
data class GroupKeyDto(
    @SerialName("gk_version") val gkVersion: Int,
    @SerialName("sender_ephemeral_pub") val senderEphemeralPub: String,
    val wrapped: String,
)

@Serializable
data class GroupKeysResponse(
    val keys: List<GroupKeyDto>,
    @SerialName("current_version") val currentVersion: Int = 0,
)

@Serializable
data class GroupMemberDto(@SerialName("user_id") val userId: String, val role: String)

@Serializable
private data class GroupMembersResponse(val members: List<GroupMemberDto>)

@Serializable
data class GroupMessageDto(
    @SerialName("message_id") val messageId: Long,
    @SerialName("group_id") val groupId: String,
    @SerialName("sender_id") val senderId: String,
    @SerialName("sender_device") val senderDevice: String,
    val kind: Int,
    @SerialName("gk_version") val gkVersion: Int,
    val payload: String, // base64url
    @SerialName("thread_root") val threadRoot: Long = 0,
    @SerialName("reply_to") val replyTo: Long = 0,
    @SerialName("created_at_unix_ms") val createdAtUnixMs: Long,
    val signature: String, // base64url
)

@Serializable
private data class GroupMessagesResponse(val messages: List<GroupMessageDto>)

@Serializable
private data class PostGroupMessageBody(
    @SerialName("client_msg_id") val clientMsgId: String,
    val kind: Int,
    @SerialName("gk_version") val gkVersion: Int,
    val payload: String,
    @SerialName("created_at_unix_ms") val createdAtUnixMs: Long,
    val signature: String,
)

@Serializable
private data class PostGroupMessageResponse(@SerialName("message_id") val messageId: Long)

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

    // ── Группы (api-overview §Группы; крипто — crypto-protocol §4) ──

    private suspend inline fun <reified T> getAuthed(path: String, token: String, vararg params: Pair<String, String>): T {
        val response = client.get(baseUrl.trimEnd('/') + path) {
            bearerAuth(token)
            params.forEach { (k, v) -> parameter(k, v) }
        }
        if (!response.status.isSuccess()) fail(response)
        return response.body()
    }

    private suspend inline fun <reified Req, reified Resp> postAuthed(path: String, token: String, requestBody: Req): Resp {
        val response = client.post(baseUrl.trimEnd('/') + path) {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }
        if (!response.status.isSuccess()) fail(response)
        return response.body()
    }

    suspend fun myGroups(token: String): List<GroupDto> =
        getAuthed<GroupsResponse>("/api/v1/groups", token).groups

    suspend fun createGroup(token: String, title: String): String =
        postAuthed<CreateGroupBody, CreateGroupResponse>("/api/v1/groups", token, CreateGroupBody("private", title)).groupId

    suspend fun addGroupMember(token: String, groupId: String, userId: String) {
        postAuthed<AddMemberBody, JsonObject>("/api/v1/groups/$groupId/members", token, AddMemberBody(userId))
    }

    suspend fun listGroupMembers(token: String, groupId: String): List<GroupMemberDto> =
        getAuthed<GroupMembersResponse>("/api/v1/groups/$groupId/members", token).members

    suspend fun rotateGroupKey(
        token: String, groupId: String, gkVersion: Int, reason: String,
        senderEphemeralPub: String, escrow: EscrowDto, wrappedKeys: List<WrappedKeyDto>,
    ) {
        postAuthed<RotateBody, JsonObject>(
            "/api/v1/groups/$groupId/keys", token,
            RotateBody(gkVersion, reason, senderEphemeralPub, escrow, wrappedKeys),
        )
    }

    suspend fun groupKeys(token: String, groupId: String, sinceVersion: Int): GroupKeysResponse =
        getAuthed("/api/v1/groups/$groupId/keys", token, "since_version" to sinceVersion.toString())

    suspend fun postGroupMessage(
        token: String, groupId: String, clientMsgId: String, kind: Int,
        gkVersion: Int, payload: String, createdAtUnixMs: Long, signature: String,
    ): Long = postAuthed<PostGroupMessageBody, PostGroupMessageResponse>(
        "/api/v1/groups/$groupId/messages", token,
        PostGroupMessageBody(clientMsgId, kind, gkVersion, payload, createdAtUnixMs, signature),
    ).messageId

    suspend fun listGroupMessages(token: String, groupId: String, limit: Int = 100): List<GroupMessageDto> =
        getAuthed<GroupMessagesResponse>("/api/v1/groups/$groupId/messages", token, "limit" to limit.toString()).messages

    // ── Media (media-storage.md: файлы ходят в MinIO напрямую, мимо бэкенда) ──

    /** Регистрация шифрованного медиа → media_id + presigned PUT. content_hash приватному запрещён. */
    suspend fun mediaInit(token: String, sizeBytes: Long, mime: String): MediaInitResponse {
        val response = client.post(baseUrl.trimEnd('/') + "/api/v1/media/init") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(MediaInitBody(sizeBytes, mime, isEncrypted = true))
        }
        if (!response.status.isSuccess()) fail(response)
        return response.body()
    }

    suspend fun mediaComplete(token: String, mediaId: String) {
        val response = client.post(baseUrl.trimEnd('/') + "/api/v1/media/complete") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(MediaCompleteBody(mediaId))
        }
        if (!response.status.isSuccess()) fail(response)
    }

    suspend fun mediaUrls(token: String, mediaId: String): List<String> {
        val response = client.get(baseUrl.trimEnd('/') + "/api/v1/media/$mediaId/url") { bearerAuth(token) }
        if (!response.status.isSuccess()) fail(response)
        return response.body<MediaUrlResponse>().urls
    }

    /** Загрузка ciphertext по presigned PUT (без Bearer — подпись в самом URL). */
    suspend fun putPresigned(url: String, bytes: ByteArray) {
        val response = client.put(url) { setBody(bytes) }
        if (!response.status.isSuccess()) throw TimaApiException("upload_failed", "MinIO PUT: HTTP ${response.status.value}")
    }

    /** Скачивание ciphertext по presigned GET. */
    suspend fun getPresigned(url: String): ByteArray {
        val response = client.get(url)
        if (!response.status.isSuccess()) throw TimaApiException("download_failed", "MinIO GET: HTTP ${response.status.value}")
        return response.body()
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
