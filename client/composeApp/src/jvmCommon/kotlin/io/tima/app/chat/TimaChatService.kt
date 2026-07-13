@file:OptIn(ExperimentalEncodingApi::class)

package io.tima.app.chat

import io.kodium.KodiumPrivateKey
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.tima.app.api.DeviceKeyInfo
import io.tima.app.api.TimaApi
import io.tima.app.session.Session
import io.tima.crypto.DeviceAddress
import io.tima.crypto.EnvelopeMeta
import io.tima.crypto.EscrowModule
import io.tima.crypto.MessageSerializer
import io.tima.crypto.PersonalMessageSealer
import io.tima.crypto.SealedPersonalMessage
import io.tima.crypto.proto.MessageBody
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val b64url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

/**
 * Детерминированный chat_id личного чата: обе стороны считают одинаково,
 * договариваться не нужно. UUID из sha256 доменной метки и отсортированной пары user_id.
 */
fun personalChatId(userA: String, userB: String): String {
    val (lo, hi) = if (userA <= userB) userA to userB else userB to userA
    val h = MessageDigest.getInstance("SHA-256").digest("tima.personal.chat|$lo|$hi".encodeToByteArray())
    h[6] = ((h[6].toInt() and 0x0f) or 0x40).toByte() // биты версии/варианта — валидный UUID
    h[8] = ((h[8].toInt() and 0x3f) or 0x80).toByte()
    val hex = h.take(16).joinToString("") { "%02x".format(it) }
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20, 32)}"
}

@Serializable
private data class WsFrame(
    val event: String = "",
    @SerialName("event_id") val eventId: Long = 0,
    val envelope: String? = null,
)

class TimaChatService(
    private val session: Session,
    override val peerUserId: String,
) : ChatService {

    override val chatId: String = personalChatId(session.userId, peerUserId)

    private val api = TimaApi(session.serverUrl)
    private val deviceKey = KodiumPrivateKey.fromRaw(b64url.decode(session.deviceSecretB64))
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val random = SecureRandom()

    private var sealer: PersonalMessageSealer? = null
    private val devicesCache = mutableMapOf<String, List<DeviceKeyInfo>>()

    private val _incoming = MutableSharedFlow<ChatMessage>(extraBufferCapacity = 256)
    override val incoming: Flow<ChatMessage> = _incoming

    private suspend fun devicesOf(userId: String): List<DeviceKeyInfo> =
        devicesCache.getOrPut(userId) { api.listDevices(session.accessToken, userId) }

    /** Escrow-модуль лениво: публичный ML-KEM-ключ анклава берётся с сервера. */
    private suspend fun ensureSealer(): PersonalMessageSealer = sealer ?: run {
        val pub = api.escrowPubkey(session.accessToken)
        PersonalMessageSealer(EscrowModule(b64url.decode(pub.publicKey), pub.version))
            .also { sealer = it }
    }

    override suspend fun start() {
        scope.launch {
            api.rawClient.webSocket(api.wsUrl()) {
                send(Frame.Text("""{"token":"${session.accessToken}"}"""))
                send(Frame.Text("""{"event":"sync.pull"}""")) // cursor серверный: догон пропущенного
                for (frame in incoming) {
                    val text = (frame as? Frame.Text)?.readText() ?: continue
                    val f = try { json.decodeFromString<WsFrame>(text) } catch (_: Throwable) { continue }
                    when (f.event) {
                        "message.new" -> {
                            f.envelope?.let { env ->
                                decrypt(b64url.decode(env))?.let { _incoming.emit(it) }
                            }
                            if (f.eventId > 0) send(Frame.Text("""{"event":"ack","event_id":${f.eventId}}"""))
                        }
                        "sync.gap" -> Unit // MVP: история и так грузится REST-ом при открытии чата
                        else -> Unit
                    }
                }
            }
        }
    }

    override suspend fun history(): List<ChatMessage> =
        api.listMessages(session.accessToken, chatId)
            .mapNotNull { decrypt(b64url.decode(it.envelope)) }
            .sortedBy { it.messageId }

    override suspend fun send(text: String): ChatMessage {
        val sealer = ensureSealer()
        // Обёртки: все устройства собеседника + все мои (мультиустройство и своя история)
        val recipients = (devicesOf(peerUserId) + devicesOf(session.userId)).map {
            DeviceAddress(it.deviceId, b64url.decode(it.encryptionPub))
        }
        val now = System.currentTimeMillis()
        // 44 бита времени + 20 случайных: уникален в чате без координации отправителей
        val messageId = (now.toULong() shl 20) or random.nextInt(1 shl 20).toULong()
        val meta = EnvelopeMeta(
            messageId = messageId,
            chatId = chatId,
            senderId = session.userId,
            senderDevice = session.deviceId,
            kind = 1, // CK_TEXT
            createdAtUnixMs = now,
        )
        val payload = MessageSerializer.encodeBody(MessageBody(text = text))
        val sealed = sealer.seal(meta, payload, deviceKey, recipients).getOrThrow()
        api.postEnvelope(session.accessToken, MessageSerializer.encodeEnvelope(sealed), UUID.randomUUID().toString())
        return ChatMessage(messageId.toLong(), session.userId, text, now, mine = true)
    }

    /** Путь B: подпись → wrapped_key своего устройства → конверт → body. Не наш чат/битое → null. */
    private suspend fun decrypt(envelopeBytes: ByteArray): ChatMessage? {
        val sealed: SealedPersonalMessage =
            MessageSerializer.decodeEnvelope(envelopeBytes).getOrNull() ?: return null
        if (sealed.meta.chatId != chatId) return null
        val senderSigningPub = devicesOf(sealed.meta.senderId)
            .firstOrNull { it.deviceId == sealed.meta.senderDevice }
            ?.let { b64url.decode(it.signingPub) } ?: return null
        val payload = PersonalMessageSealer
            .openWithWrappedKey(sealed, session.deviceId, deviceKey, senderSigningPub)
            .getOrNull() ?: return null
        val body = MessageSerializer.decodeBody(payload).getOrNull() ?: return null
        return ChatMessage(
            messageId = sealed.meta.messageId.toLong(),
            senderId = sealed.meta.senderId,
            text = body.text,
            createdAtMs = sealed.meta.createdAtUnixMs,
            mine = sealed.meta.senderId == session.userId,
        )
    }

    override fun close() {
        scope.cancel()
    }
}

actual fun createChatService(session: Session, peerUserId: String): ChatService =
    TimaChatService(session, peerUserId)
