@file:OptIn(ExperimentalEncodingApi::class)

package io.tima.app.chat

import io.kodium.Kodium
import io.kodium.KodiumPrivateKey
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.tima.app.api.DeviceKeyInfo
import io.tima.app.api.BackupItemDto
import io.tima.app.api.EscrowDto
import io.tima.app.api.GroupMessageDto
import io.tima.app.api.ProvideKeyDto
import io.tima.app.api.ProvideMsgKeyDto
import io.tima.app.api.TimaApi
import io.tima.app.api.TimaApiException
import io.tima.app.api.WrappedKeyDto
import io.tima.app.diag.AppDiagnostics
import io.tima.app.platform.contactsGranted
import io.tima.app.platform.normalizePhone
import io.tima.app.platform.readDeviceContacts
import io.tima.app.session.Session
import io.tima.crypto.CanonicalBytes
import io.tima.crypto.DeviceAddress
import io.tima.crypto.EnvelopeCipher
import io.tima.crypto.EnvelopeMeta
import io.tima.crypto.EscrowModule
import io.tima.crypto.GroupKeyManager
import io.tima.crypto.GroupMessageMeta
import io.tima.crypto.MessageSigner
import io.tima.crypto.WrappedKeyService
import io.tima.crypto.MediaCipher
import io.tima.crypto.MessageContent
import io.tima.crypto.MessageContentCodec
import io.tima.crypto.MessageSerializer
import io.tima.crypto.PersonalMessageSealer
import io.tima.crypto.SealedPersonalMessage
import io.tima.crypto.proto.MediaRef
import io.tima.crypto.proto.MessageBody
import java.util.concurrent.ConcurrentHashMap
import okio.ByteString.Companion.toByteString
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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

/**
 * Момент из RFC-3339 в миллисекунды. Сервер отдаёт окна валидности ключей escrow
 * в этом виде. Не разобралось — считаем ключ просроченным (0): лучше лишний запрос,
 * чем шифрование на ключ с неизвестным сроком.
 */
private fun parseInstantMs(value: String): Long =
    runCatching { java.time.Instant.parse(value).toEpochMilli() }.getOrDefault(0L)

@Serializable
private data class WsFrame(
    val event: String = "",
    @SerialName("event_id") val eventId: Long = 0,
    val envelope: String? = null,
)

@Serializable
private data class KeyRotatedFrame(
    @SerialName("group_id") val groupId: String,
    @SerialName("gk_version") val gkVersion: Int,
    @SerialName("sender_ephemeral_pub") val senderEphemeralPub: String,
    @SerialName("wrapped_gk") val wrappedGk: String,
)

@Serializable
private data class RecoveryRequestFrame(
    @SerialName("group_id") val groupId: String,
    @SerialName("requester_device") val requesterDevice: String,
    @SerialName("requester_enc_pub") val requesterEncPub: String,
    val versions: List<Int> = emptyList(),
)

@Serializable
private data class RecoveryReadyFrame(@SerialName("group_id") val groupId: String)

@Serializable
private data class MsgRecoveryRequestFrame(
    @SerialName("chat_id") val chatId: String,
    @SerialName("requester_device") val requesterDevice: String,
    @SerialName("requester_enc_pub") val requesterEncPub: String,
    val own: Boolean = false,
)

@Serializable
private data class MsgRecoveryReadyFrame(@SerialName("chat_id") val chatId: String)

@Serializable
private data class CallIncomingFrame(
    @SerialName("call_id") val callId: String,
    val room: String = "",
    val kind: String = "audio",
    val from: String = "",
)

@Serializable
private data class CallStateFrame(
    @SerialName("call_id") val callId: String,
    val state: String = "",
)

@Serializable
private data class VoiceEventFrame(
    @SerialName("room_id") val roomId: String,
    @SerialName("user_id") val userId: String = "",
)

@Serializable
private data class ReceiptFrame(
    @SerialName("chat_id") val chatId: String,
    @SerialName("message_id") val messageId: Long,
)

@Serializable
private data class TypingFrame(
    @SerialName("chat_id") val chatId: String,
    @SerialName("user_id") val userId: String = "",
)

class TimaClient(private val session: Session) : ChatClient {

    private val api = TimaApi(session.serverUrl)
    private val deviceKey = KodiumPrivateKey.fromRaw(b64url.decode(session.deviceSecretB64))
    // Ключ личности (из фразы) — для подписи запросов восстановления; null без фразы
    private val identityKey: KodiumPrivateKey? =
        session.identitySecretB64.takeIf { it.isNotEmpty() }?.let { KodiumPrivateKey.fromRaw(b64url.decode(it)) }
    // Симметричный ключ бэкапа «сообщений себе» (этап 4); null без фразы
    private val backupKey: ByteArray? = session.backupSecretB64.takeIf { it.isNotEmpty() }?.let { b64url.decode(it) }
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val random = SecureRandom()

    // chat_id → ключ escrow текущей эпохи. Ключ у каждой пары «чат × месяц» свой,
    // поэтому кэш по чатам, а не один модуль на клиента.
    private val escrowByChat = ConcurrentHashMap<String, CachedEscrowKey>()
    // ConcurrentHashMap, а не обычная карта: список читают и обновляют разные корутины
    private val devicesCache = ConcurrentHashMap<String, CachedDevices>()
    private val mediaCache = ConcurrentHashMap<String, ByteArray>() // media_id → plaintext (на сессию)
    private val groupKeyCache = ConcurrentHashMap<String, MutableMap<Int, ByteArray>>() // group_id → версия → GK

    private val _messages = MutableSharedFlow<ChatMessage>(extraBufferCapacity = 256)
    override val messages: Flow<ChatMessage> = _messages

    // Есть ли живое соединение. Без этого человек в плохой сети видит просто
    // молчащее приложение и не может отличить «никто не пишет» от «мы отвалились».
    private val _online = MutableStateFlow(false)
    override val online: StateFlow<Boolean> = _online

    // Сигналы «обёртки восстановления готовы» по group_id / chat_id
    private val _recoveryReady = MutableSharedFlow<String>(extraBufferCapacity = 16)
    private val _msgRecoveryReady = MutableSharedFlow<String>(extraBufferCapacity = 16)
    private val _consentRequests = MutableSharedFlow<RecoveryConsent>(extraBufferCapacity = 16)
    override val consentRequests: Flow<RecoveryConsent> = _consentRequests
    private val _incomingCalls = MutableSharedFlow<IncomingCall>(extraBufferCapacity = 16)
    override val incomingCalls: Flow<IncomingCall> = _incomingCalls
    private val _callStates = MutableSharedFlow<CallStateEvent>(extraBufferCapacity = 16)
    override val callStates: Flow<CallStateEvent> = _callStates
    private val _voiceEvents = MutableSharedFlow<VoiceEvent>(extraBufferCapacity = 32)
    override val voiceEvents: Flow<VoiceEvent> = _voiceEvents
    private val _readReceipts = MutableSharedFlow<ReadReceipt>(extraBufferCapacity = 64)
    override val readReceipts: Flow<ReadReceipt> = _readReceipts
    private val _typingEvents = MutableSharedFlow<TypingEvent>(extraBufferCapacity = 64)
    override val typingEvents: Flow<TypingEvent> = _typingEvents

    override suspend fun markRead(chatId: String, upToMessageId: Long) {
        runCatching { api.markChatRead(session.accessToken, chatId, upToMessageId) }
    }

    override suspend fun sendTyping(chatId: String) {
        runCatching { api.sendTyping(session.accessToken, chatId) }
    }

    override fun chatIdWith(peerUserId: String): String = personalChatId(session.userId, peerUserId)

    private val peerCache = ConcurrentHashMap<String, PeerInfo>() // user_id → имя+телефон
    @Volatile private var bookNames: Map<String, String>? = null  // нормализованный телефон → имя из книги

    override suspend fun setMyName(name: String) {
        api.setDisplayName(session.accessToken, name)
        peerCache.remove(session.userId) // перечитаем с сервера вместе с телефоном
    }

    /**
     * Телефонная книга как «телефон → имя», один раз за сессию. Пусто, если доступ
     * к контактам не выдан: подстановка имени не должна поднимать системный диалог.
     */
    private suspend fun phoneBookNames(): Map<String, String> {
        bookNames?.let { return it }
        val map = if (!contactsGranted()) {
            emptyMap()
        } else {
            val out = LinkedHashMap<String, String>()
            for (c in runCatching { readDeviceContacts() }.getOrDefault(emptyList())) {
                if (c.name.isBlank()) continue
                normalizePhone(c.phone)?.let { out.putIfAbsent(it, c.name) }
            }
            out
        }
        bookNames = map
        return map
    }

    override suspend fun resolvePeers(ids: List<String>): Map<String, PeerInfo> {
        val missing = ids.distinct().filter { !peerCache.containsKey(it) }
        if (missing.isNotEmpty()) {
            // Ошибку не кэшируем: без ответа сервера id останется нерезолвленным и повторится
            val resolved = runCatching { api.resolveNames(session.accessToken, missing) }.getOrNull()
            if (resolved != null) {
                val book = phoneBookNames()
                for (id in missing) {
                    val phone = resolved.phones[id].orEmpty()
                    // Как человек записан у МЕНЯ важнее того, как он назвал себя сам
                    val name = book[phone] ?: resolved.names[id].orEmpty()
                    peerCache[id] = PeerInfo(id, name, phone)
                }
            }
        }
        // В кэше держим и «про этого ничего не знаем» (чтобы не дёргать сервер снова),
        // но наружу такие не отдаём: у вызывающего свой запасной вариант — введённый
        // номер или короткий id, и он лучше, чем пустышка.
        return ids.mapNotNull { id ->
            peerCache[id]?.takeIf { it.name.isNotEmpty() || it.phone.isNotEmpty() }?.let { id to it }
        }.toMap()
    }

    override suspend fun phoneBook(): List<Contact> {
        val raw = readDeviceContacts()
        if (raw.isEmpty()) return emptyList()
        // нормализованный телефон → имя из книги (первое встреченное)
        val byPhone = LinkedHashMap<String, String>()
        for (c in raw) normalizePhone(c.phone)?.let { p -> byPhone.putIfAbsent(p, c.name) }
        if (byPhone.isEmpty()) return emptyList()
        val matches = runCatching { api.discoverContacts(session.accessToken, byPhone.keys.toList()) }
            .getOrDefault(emptyMap())
        val userIds = matches.values.filter { it != session.userId }.distinct()
        val names = runCatching { api.resolveNames(session.accessToken, userIds) }.getOrNull()?.names.orEmpty()
        // Идём в порядке книги, а не ответа сервера: у контакта с несколькими номерами
        // побеждает первый (основной), а distinctBy убирает его же вторую строку.
        return byPhone.mapNotNull { (phone, bookName) ->
            val uid = matches[phone] ?: return@mapNotNull null
            if (uid == session.userId) return@mapNotNull null // себя не показываем
            Contact(uid, phone, bookName.ifBlank { names[uid].orEmpty() })
        }.distinctBy { it.userId }
    }

    /**
     * Тело сообщения из текста (ADR-0011). Кодек кладёт узлы И плоский текст:
     * клиент, не знающий про узлы, покажет сообщение без оформления, а не пустоту.
     */
    private fun bodyOf(text: String): MessageBody =
        MessageContentCodec.toBody(MessageContent.text(text))

    /**
     * Текст из тела: узлы, если они есть, иначе плоское поле. Читаем через кодек, а
     * не напрямую, чтобы сообщение от клиента, который перестанет заполнять `text`,
     * не превратилось в пустое.
     */
    private fun textOf(body: MessageBody): String =
        MessageContentCodec.fromBody(body).plainText()

    /** Список устройств с временем, когда он получен. */
    private data class CachedDevices(val devices: List<DeviceKeyInfo>, val atMs: Long)

    /**
     * Сколько живёт список устройств собеседника.
     *
     * Раньше он кэшировался НАВСЕГДА — до перезапуска приложения. А приложение держит
     * фоновую службу сутками, то есть «навсегда» и означало «навсегда». Последствие
     * серьёзное: устройство, зарегистрированное позже, для собеседника не
     * существовало — тот продолжал шифровать на прежний список, и на новое устройство
     * не приходило НИЧЕГО. Именно так выглядел вход с ПК: телефон отвечал, ответы
     * уходили пяти старым устройствам и ни одного — новому.
     */
    private val devicesTtlMs = 5 * 60 * 1000L

    private suspend fun devicesOf(userId: String): List<DeviceKeyInfo> {
        val now = System.currentTimeMillis()
        devicesCache[userId]?.let { if (now - it.atMs < devicesTtlMs) return it.devices }
        val fresh = api.listDevices(session.accessToken, userId)
        devicesCache[userId] = CachedDevices(fresh, now)
        return fresh
    }

    /**
     * Сообщение пришло с устройства, которого нет в нашем списке, — значит список
     * устарел прямо сейчас. Признак бесплатный и точный, ждать истечения срока незачем.
     */
    private fun noticeSenderDevice(userId: String, deviceId: String) {
        val cached = devicesCache[userId] ?: return
        if (cached.devices.none { it.deviceId == deviceId }) devicesCache.remove(userId)
    }

    /**
     * Escrow-модуль для чата на текущую эпоху (ADR-0012).
     *
     * Ключ свой у каждой пары «чат × месяц», поэтому единого модуля на клиента
     * больше нет. Кэш держим до конца окна валидности: сервер отдаёт `valid_to`,
     * и по его истечении ключ перезапрашивается — иначе на смене месяца мы бы
     * шифровали на закрывшееся окно.
     */
    private suspend fun escrowFor(chatId: String): EscrowModule {
        escrowByChat[chatId]?.let { if (System.currentTimeMillis() < it.validToMs) return it.module }
        val bundle = api.escrowKey(session.accessToken, chatId)
        val module = EscrowModule(b64url.decode(bundle.current.publicKey), bundle.current.id.toInt())
        escrowByChat[chatId] = CachedEscrowKey(module, parseInstantMs(bundle.current.validTo))
        return module
    }

    private suspend fun sealerFor(chatId: String): PersonalMessageSealer =
        PersonalMessageSealer(escrowFor(chatId))

    /** Ключ escrow чата с кэшем до конца эпохи. */
    private data class CachedEscrowKey(val module: EscrowModule, val validToMs: Long)

    /** Один WS на устройство: auth → sync.pull (догон) → live; обрыв → реконнект с паузой. */
    private val started = java.util.concurrent.atomic.AtomicBoolean(false)

    override suspend fun start() {
        // Зовут и экран, и сервис; второй WS-цикл был бы лишним соединением
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            var backoffMs = 1_000L
            while (isActive) {
                try {
                    AppDiagnostics.add("WS: подключаюсь ${api.wsUrl()}")
                    api.rawClient.webSocket(api.wsUrl()) {
                        send(Frame.Text("""{"token":"${session.accessToken}"}"""))
                        send(Frame.Text("""{"event":"sync.pull"}""")) // cursor серверный
                        backoffMs = 1_000L
                        _online.value = true
                        AppDiagnostics.add("WS: соединение установлено")
                        for (frame in incoming) {
                            val text = (frame as? Frame.Text)?.readText() ?: continue
                            val f = try { json.decodeFromString<WsFrame>(text) } catch (_: Throwable) { continue }
                            when (f.event) {
                                "message.new" -> {
                                    f.envelope?.let { env ->
                                        val m = decrypt(b64url.decode(env))
                                        if (m != null) {
                                            AppDiagnostics.add("входящее: расшифровано (chat ${m.chatId.take(8)}…, от ${m.senderId.take(8)}…)")
                                            _messages.emit(m)
                                        } else {
                                            AppDiagnostics.add("входящее: НЕ расшифровано — нет ключа для этого устройства")
                                        }
                                    }
                                    if (f.eventId > 0) send(Frame.Text("""{"event":"ack","event_id":${f.eventId}}"""))
                                }
                                "message.group" -> {
                                    // кадр несёт все поля preimage — тот же DTO, что и история
                                    try { json.decodeFromString<GroupMessageDto>(text) } catch (_: Throwable) { null }
                                        ?.let { dto -> decryptGroup(dto)?.let { _messages.emit(it) } }
                                    if (f.eventId > 0) send(Frame.Text("""{"event":"ack","event_id":${f.eventId}}"""))
                                }
                                "key.rotated" -> {
                                    try { json.decodeFromString<KeyRotatedFrame>(text) } catch (_: Throwable) { null }
                                        ?.let { k ->
                                            GroupKeyManager.unwrapGroupKey(
                                                deviceKey, b64url.decode(k.senderEphemeralPub), b64url.decode(k.wrappedGk),
                                            ).getOrNull()?.let { gk ->
                                                groupKeyCache.getOrPut(k.groupId) { mutableMapOf() }[k.gkVersion] = gk
                                            }
                                        }
                                    if (f.eventId > 0) send(Frame.Text("""{"event":"ack","event_id":${f.eventId}}"""))
                                }
                                "recovery.gk_request" -> {
                                    // Помощник: заворачиваю имеющиеся GK под устройство-запросившее
                                    try { json.decodeFromString<RecoveryRequestFrame>(text) } catch (_: Throwable) { null }
                                        ?.let { req -> provideGroupKeys(req) }
                                    if (f.eventId > 0) send(Frame.Text("""{"event":"ack","event_id":${f.eventId}}"""))
                                }
                                "recovery.gk_ready" -> {
                                    try { json.decodeFromString<RecoveryReadyFrame>(text) } catch (_: Throwable) { null }
                                        ?.let { _recoveryReady.emit(it.groupId) }
                                    if (f.eventId > 0) send(Frame.Text("""{"event":"ack","event_id":${f.eventId}}"""))
                                }
                                "recovery.msg_request" -> {
                                    // Помощник личного чата: свои устройства — авто, собеседник — согласие
                                    try { json.decodeFromString<MsgRecoveryRequestFrame>(text) } catch (_: Throwable) { null }
                                        ?.let { req ->
                                            val consent = RecoveryConsent(req.chatId, req.requesterDevice, req.requesterEncPub)
                                            if (req.own) provideChatKeys(consent) else _consentRequests.emit(consent)
                                        }
                                    if (f.eventId > 0) send(Frame.Text("""{"event":"ack","event_id":${f.eventId}}"""))
                                }
                                "recovery.msg_ready" -> {
                                    try { json.decodeFromString<MsgRecoveryReadyFrame>(text) } catch (_: Throwable) { null }
                                        ?.let { _msgRecoveryReady.emit(it.chatId) }
                                    if (f.eventId > 0) send(Frame.Text("""{"event":"ack","event_id":${f.eventId}}"""))
                                }
                                "call.incoming" -> {
                                    try { json.decodeFromString<CallIncomingFrame>(text) } catch (_: Throwable) { null }
                                        ?.let { _incomingCalls.emit(IncomingCall(it.callId, it.room, it.kind, it.from)) }
                                    if (f.eventId > 0) send(Frame.Text("""{"event":"ack","event_id":${f.eventId}}"""))
                                }
                                "call.state" -> {
                                    try { json.decodeFromString<CallStateFrame>(text) } catch (_: Throwable) { null }
                                        ?.let { _callStates.emit(CallStateEvent(it.callId, it.state)) }
                                    if (f.eventId > 0) send(Frame.Text("""{"event":"ack","event_id":${f.eventId}}"""))
                                }
                                "voice.hand", "voice.granted", "voice.revoked" -> {
                                    try { json.decodeFromString<VoiceEventFrame>(text) } catch (_: Throwable) { null }
                                        ?.let { _voiceEvents.emit(VoiceEvent(f.event, it.roomId, it.userId)) }
                                    if (f.eventId > 0) send(Frame.Text("""{"event":"ack","event_id":${f.eventId}}"""))
                                }
                                "receipt.read" -> {
                                    try { json.decodeFromString<ReceiptFrame>(text) } catch (_: Throwable) { null }
                                        ?.let { _readReceipts.emit(ReadReceipt(it.chatId, it.messageId)) }
                                    if (f.eventId > 0) send(Frame.Text("""{"event":"ack","event_id":${f.eventId}}"""))
                                }
                                "typing" -> {
                                    // эфемерный (event_id=0) — не ack-аем
                                    try { json.decodeFromString<TypingFrame>(text) } catch (_: Throwable) { null }
                                        ?.let { _typingEvents.emit(TypingEvent(it.chatId, it.userId)) }
                                }
                                "sync.gap" -> Unit // история чата и так грузится REST-ом при открытии
                                else -> Unit
                            }
                        }
                    }
                } catch (e: Throwable) {
                    // сервер недоступен/сеть моргнула — переподключение ниже
                    AppDiagnostics.add("WS: обрыв — ${e.message ?: e::class.simpleName}")
                } finally {
                    _online.value = false
                }
                delay(backoffMs)
                // Потолок паузы 15 с, а не 30: в мобильной сети связь возвращается
                // рывками, и полминуты ожидания после её возврата человек читает как
                // «приложение сломалось».
                backoffMs = (backoffMs * 2).coerceAtMost(15_000L)
            }
        }
    }

    override suspend fun history(peerUserId: String): List<ChatMessage> =
        api.listMessages(session.accessToken, chatIdWith(peerUserId))
            .mapNotNull { item ->
                val wrapEph = item.wrapEphemeral.takeIf { it.isNotEmpty() }?.let { b64url.decode(it) }
                decrypt(b64url.decode(item.envelope), wrapEph)
            }
            .sortedBy { it.messageId }

    override suspend fun send(peerUserId: String, text: String, replyTo: Long): ChatMessage =
        sealAndPost(peerUserId, bodyOf(text), kind = 1, replyTo = replyTo.toULong()) // CK_TEXT

    override suspend fun sendImage(peerUserId: String, imageBytes: ByteArray, mime: String, caption: String): ChatMessage {
        // media_key — случайный на файл; сервер и MinIO видят только ciphertext
        val mediaKey = ByteArray(32).also(random::nextBytes)
        val sealedFile = MediaCipher.seal(mediaKey, imageBytes).getOrThrow()
        val init = api.mediaInit(session.accessToken, sealedFile.size.toLong(), mime)
        api.putPresigned(init.uploadUrls.first(), sealedFile)
        api.mediaComplete(session.accessToken, init.mediaId)

        val body = MessageBody(
            text = caption,
            media = listOf(
                MediaRef(
                    media_id = init.mediaId,
                    media_key = mediaKey.toByteString(),
                    mime = mime,
                    size_bytes = imageBytes.size.toLong(),
                ),
            ),
        )
        mediaCache[init.mediaId] = imageBytes // своё фото не перекачивать
        return sealAndPost(peerUserId, body, kind = 3) // CK_IMAGE
    }

    override suspend fun sendVoice(peerUserId: String, audioBytes: ByteArray, mime: String, durationMs: Int): ChatMessage {
        val mediaKey = ByteArray(32).also(random::nextBytes)
        val sealedFile = MediaCipher.seal(mediaKey, audioBytes).getOrThrow()
        val init = api.mediaInit(session.accessToken, sealedFile.size.toLong(), mime)
        api.putPresigned(init.uploadUrls.first(), sealedFile)
        api.mediaComplete(session.accessToken, init.mediaId)
        mediaCache[init.mediaId] = audioBytes
        val body = MessageBody(
            media = listOf(
                MediaRef(
                    media_id = init.mediaId,
                    media_key = mediaKey.toByteString(),
                    mime = mime,
                    size_bytes = audioBytes.size.toLong(),
                    duration_ms = durationMs,
                ),
            ),
        )
        return sealAndPost(peerUserId, body, kind = 2) // CK_VOICE
    }

    override suspend fun sendGroupVoice(groupId: String, audioBytes: ByteArray, mime: String, durationMs: Int): ChatMessage {
        val mediaKey = ByteArray(32).also(random::nextBytes)
        val sealedFile = MediaCipher.seal(mediaKey, audioBytes).getOrThrow()
        val init = api.mediaInit(session.accessToken, sealedFile.size.toLong(), mime)
        api.putPresigned(init.uploadUrls.first(), sealedFile)
        api.mediaComplete(session.accessToken, init.mediaId)
        mediaCache[init.mediaId] = audioBytes
        val body = MessageBody(
            media = listOf(
                MediaRef(
                    media_id = init.mediaId,
                    media_key = mediaKey.toByteString(),
                    mime = mime,
                    size_bytes = audioBytes.size.toLong(),
                    duration_ms = durationMs,
                ),
            ),
        )
        return sealAndPostGroup(groupId, body, kind = 2) // CK_VOICE
    }

    override suspend fun sendFile(peerUserId: String, bytes: ByteArray, name: String, mime: String): ChatMessage =
        sealAndPost(peerUserId, uploadFileBody(bytes, name, mime), kind = 5) // CK_FILE

    override suspend fun sendGroupFile(groupId: String, bytes: ByteArray, name: String, mime: String): ChatMessage =
        sealAndPostGroup(groupId, uploadFileBody(bytes, name, mime), kind = 5) // CK_FILE

    /** Шифрует файл, грузит в MinIO и собирает body (имя файла — в text). */
    private suspend fun uploadFileBody(bytes: ByteArray, name: String, mime: String): MessageBody {
        val mediaKey = ByteArray(32).also(random::nextBytes)
        val sealedFile = MediaCipher.seal(mediaKey, bytes).getOrThrow()
        val init = api.mediaInit(session.accessToken, sealedFile.size.toLong(), mime)
        api.putPresigned(init.uploadUrls.first(), sealedFile)
        api.mediaComplete(session.accessToken, init.mediaId)
        mediaCache[init.mediaId] = bytes
        return MessageBody(
            text = name,
            media = listOf(
                MediaRef(
                    media_id = init.mediaId,
                    media_key = mediaKey.toByteString(),
                    mime = mime,
                    size_bytes = bytes.size.toLong(),
                ),
            ),
        )
    }

    override suspend fun loadMedia(attachment: MediaAttachment): ByteArray =
        mediaCache.getOrPut(attachment.mediaId) {
            val urls = api.mediaUrls(session.accessToken, attachment.mediaId)
            MediaCipher.open(attachment.mediaKey, api.getPresigned(urls.first())).getOrThrow()
        }

    // ── Восстановление личного чата (ADR-0010 §этап 2) ──

    override suspend fun recoverChatHistory(peerUserId: String): List<ChatMessage> {
        val chatId = chatIdWith(peerUserId)
        // Self-чат (заметки): восстанавливаем из бэкапа под backup_key — без онлайн-источников
        if (peerUserId == session.userId && backupKey != null) {
            recoverFromBackup(chatId)
            return history(peerUserId)
        }
        val canonical = "tima.recover.v1|$chatId|${session.deviceId}".encodeToByteArray()
        val signature = identityKey?.let { b64url.encode(MessageSigner.sign(it, canonical).getOrThrow()) } ?: ""
        val resp = api.recoverChatKeys(session.accessToken, chatId, signature)
        // Свои устройства помогают сразу; согласие собеседника — асинхронно, ждём готовности
        if (resp.helpers > 0) {
            withTimeoutOrNull(20_000) { _msgRecoveryReady.first { it == chatId } }
        }
        return history(peerUserId)
    }

    /** Этап 4: скачать бэкап-обёртки, развернуть backup_key → message_key, завернуть под себя. */
    private suspend fun recoverFromBackup(chatId: String) {
        val key = backupKey ?: return
        val items = api.chatBackup(session.accessToken, chatId)
        if (items.isEmpty()) return
        val ephemeral = Kodium.generateKeyPair()
        val ephPub = b64url.encode(ephemeral.getPublicKey().encryptionKey)
        val myEncPub = deviceKey.getPublicKey().encryptionKey
        val keys = items.mapNotNull { item ->
            val messageKey = EnvelopeCipher.open(key, b64url.decode(item.wrapped)).getOrNull() ?: return@mapNotNull null
            val reWrapped = WrappedKeyService.wrap(ephemeral, myEncPub, messageKey).getOrNull() ?: return@mapNotNull null
            ProvideMsgKeyDto(item.messageId, ephPub, b64url.encode(reWrapped))
        }
        // Кладём обёртки под СВОЁ устройство (self-provide) — дальше history их развернёт
        if (keys.isNotEmpty()) {
            runCatching { api.provideChatKeys(session.accessToken, chatId, session.deviceId, keys) }
        }
    }

    override suspend fun approveRecovery(consent: RecoveryConsent) = provideChatKeys(consent)

    // ── Звонки 1:1 (сигналинг; медиа — LiveKit, здесь не подключается) ──

    override suspend fun startCall(peerUserId: String, kind: String): CallConnection {
        val r = api.startCall(session.accessToken, peerUserId, kind)
        return CallConnection(r.callId, r.room, r.url, r.token)
    }

    override suspend fun answerCall(callId: String): CallConnection {
        val r = api.answerCall(session.accessToken, callId)
        return CallConnection(callId, r.room, r.url, r.token)
    }

    override suspend fun endCall(callId: String) = api.endCall(session.accessToken, callId)

    /** Помощник: разворачивает свои ключи сообщений чата и заворачивает под устройство-запросившее. */
    private suspend fun provideChatKeys(consent: RecoveryConsent) {
        val recipientPub = b64url.decode(consent.requesterEncPub)
        val ephemeral = Kodium.generateKeyPair()
        val ephPub = b64url.encode(ephemeral.getPublicKey().encryptionKey)
        val keys = api.listMessages(session.accessToken, consent.chatId).mapNotNull { item ->
            val sealed = MessageSerializer.decodeEnvelope(b64url.decode(item.envelope)).getOrNull() ?: return@mapNotNull null
            val wrapped = sealed.wrappedKeys[session.deviceId] ?: return@mapNotNull null
            val wrapEph = item.wrapEphemeral.takeIf { it.isNotEmpty() }?.let { b64url.decode(it) } ?: sealed.senderEphemeralPub
            val messageKey = WrappedKeyService.unwrap(deviceKey, wrapEph, wrapped).getOrNull() ?: return@mapNotNull null
            val reWrapped = WrappedKeyService.wrap(ephemeral, recipientPub, messageKey).getOrNull() ?: return@mapNotNull null
            ProvideMsgKeyDto(item.messageId, ephPub, b64url.encode(reWrapped))
        }
        if (keys.isNotEmpty()) {
            runCatching { api.provideChatKeys(session.accessToken, consent.chatId, consent.requesterDevice, keys) }
        }
    }

    private suspend fun sealAndPost(peerUserId: String, body: MessageBody, kind: Int, replyTo: ULong = 0u): ChatMessage {
        val chatId = chatIdWith(peerUserId)
        val sealer = sealerFor(chatId)
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
            kind = kind,
            createdAtUnixMs = now,
            replyTo = replyTo,
        )
        val payload = MessageSerializer.encodeBody(body)
        val sealed = sealer.seal(meta, payload, deviceKey, recipients).getOrThrow()
        api.postEnvelope(session.accessToken, MessageSerializer.encodeEnvelope(sealed), UUID.randomUUID().toString())

        // Бэкап «сообщений себе» (этап 4): у self-чата нет живых источников, кроме бэкапа.
        // message_key достаём из своей же обёртки, заворачиваем под backup_key из фразы.
        if (peerUserId == session.userId && backupKey != null) {
            val myWrap = sealed.wrappedKeys[session.deviceId]
            if (myWrap != null) {
                WrappedKeyService.unwrap(deviceKey, sealed.senderEphemeralPub, myWrap).getOrNull()?.let { messageKey ->
                    EnvelopeCipher.seal(backupKey, messageKey).getOrNull()?.let { wrapped ->
                        runCatching {
                            api.saveChatBackup(
                                session.accessToken, chatId,
                                listOf(BackupItemDto(messageId.toLong(), b64url.encode(wrapped))),
                            )
                        }
                    }
                }
            }
        }
        return ChatMessage(
            chatId, messageId.toLong(), session.userId, textOf(body), now, mine = true,
            media = body.media.firstOrNull()?.toAttachment(),
            replyTo = replyTo.toLong(),
        )
    }

    /**
     * Путь B: подпись → wrapped_key своего устройства → конверт → body. Битое/чужое → null.
     * @param wrapEphemeral эфемерал обёртки восстановления, если он не из конверта (этап 2).
     */
    private suspend fun decrypt(envelopeBytes: ByteArray, wrapEphemeral: ByteArray? = null): ChatMessage? {
        val sealed: SealedPersonalMessage =
            MessageSerializer.decodeEnvelope(envelopeBytes).getOrNull() ?: return null
        // Устройства отправителя нет в списке — список устарел, а не сообщение чужое.
        // Обновляемся и пробуем ещё раз: иначе первое сообщение с только что
        // заведённого устройства собеседника не проходило проверку подписи.
        noticeSenderDevice(sealed.meta.senderId, sealed.meta.senderDevice)
        val senderSigningPub = devicesOf(sealed.meta.senderId)
            .firstOrNull { it.deviceId == sealed.meta.senderDevice }
            ?.let { b64url.decode(it.signingPub) } ?: return null
        val payload = PersonalMessageSealer
            .openWithWrappedKey(sealed, session.deviceId, deviceKey, senderSigningPub, wrapEphemeral)
            .getOrNull() ?: return null
        val body = MessageSerializer.decodeBody(payload).getOrNull() ?: return null
        return ChatMessage(
            chatId = sealed.meta.chatId,
            messageId = sealed.meta.messageId.toLong(),
            senderId = sealed.meta.senderId,
            text = textOf(body),
            createdAtMs = sealed.meta.createdAtUnixMs,
            mine = sealed.meta.senderId == session.userId,
            media = body.media.firstOrNull()?.toAttachment(),
            replyTo = sealed.meta.replyTo.toLong(),
        )
    }

    private fun MediaRef.toAttachment() = MediaAttachment(
        mediaId = media_id,
        mediaKey = media_key.toByteArray(),
        mime = mime,
        sizeBytes = size_bytes,
        durationMs = duration_ms,
    )

    // ── Группы (crypto-protocol §4: GK генерирует клиент-админ, сервер видит только обёртки) ──

    override suspend fun myGroups(): List<GroupSummary> =
        api.myGroups(session.accessToken).map { GroupSummary(it.groupId, it.title, it.myRole) }

    override suspend fun createGroup(title: String, memberPhones: List<String>): GroupSummary {
        val groupId = api.createGroup(session.accessToken, title)
        val notFound = mutableListOf<String>()
        for (phone in memberPhones.map { it.trim() }.filter { it.isNotEmpty() }) {
            val userId = api.lookupUser(session.accessToken, phone)
            if (userId == null) notFound += phone else api.addGroupMember(session.accessToken, groupId, userId)
        }
        rotateGroup(groupId, currentVersion = 0, reason = "member_join")
        if (notFound.isNotEmpty()) {
            throw TimaApiException("members_not_found", "Группа создана, но не в TIMA: ${notFound.joinToString()}")
        }
        return GroupSummary(groupId, title, myRole = "owner")
    }

    /** Новая версия GK: обёртки всем устройствам активных участников + escrow (один на версию). */
    private suspend fun rotateGroup(groupId: String, currentVersion: Int, reason: String): Int {
        val members = api.listGroupMembers(session.accessToken, groupId)
        val devices = members.flatMap { devicesOf(it.userId) }.map {
            DeviceAddress(it.deviceId, b64url.decode(it.encryptionPub))
        }
        // Групповой ключ депонируется на ключ эпохи ЭТОЙ группы: область та же, что у чата.
        val rotation = GroupKeyManager(escrowFor(groupId)).rotate(currentVersion, devices).getOrThrow()
        api.rotateGroupKey(
            session.accessToken, groupId, rotation.gkVersion, reason,
            senderEphemeralPub = b64url.encode(rotation.senderEphemeralPub),
            escrow = EscrowDto(
                mlkemCt = b64url.encode(rotation.escrow.mlkemCt),
                wrappedMessageKey = b64url.encode(rotation.escrow.wrappedMessageKey),
                escrowKeyVersion = rotation.escrow.escrowKeyVersion,
            ),
            wrappedKeys = rotation.wrappedKeys.map { (recipient, wrapped) ->
                WrappedKeyDto(recipient, b64url.encode(wrapped))
            },
        )
        groupKeyCache.getOrPut(groupId) { mutableMapOf() }[rotation.gkVersion] = rotation.groupKey
        return rotation.gkVersion
    }

    /** GK версии: кэш → догон с сервера (обёртки своего устройства) → unwrap. */
    private suspend fun groupKey(groupId: String, version: Int): ByteArray? {
        groupKeyCache[groupId]?.get(version)?.let { return it }
        fetchGroupKeys(groupId)
        return groupKeyCache[groupId]?.get(version)
    }

    /** Забирает обёртки для своего устройства; возвращает current_version группы на сервере. */
    private suspend fun fetchGroupKeys(groupId: String): Int {
        val cache = groupKeyCache.getOrPut(groupId) { mutableMapOf() }
        val since = cache.keys.maxOrNull() ?: 0
        val resp = api.groupKeys(session.accessToken, groupId, since)
        resp.keys.forEach { k ->
            GroupKeyManager.unwrapGroupKey(deviceKey, b64url.decode(k.senderEphemeralPub), b64url.decode(k.wrapped))
                .getOrNull()?.let { cache[k.gkVersion] = it }
        }
        return resp.currentVersion
    }

    override suspend fun sendGroup(groupId: String, text: String): ChatMessage =
        sealAndPostGroup(groupId, bodyOf(text), kind = 1) // CK_TEXT

    override suspend fun sendGroupImage(groupId: String, imageBytes: ByteArray, mime: String, caption: String): ChatMessage {
        // media_key на файл; сервер и MinIO видят только ciphertext (как в личных)
        val mediaKey = ByteArray(32).also(random::nextBytes)
        val sealedFile = MediaCipher.seal(mediaKey, imageBytes).getOrThrow()
        val init = api.mediaInit(session.accessToken, sealedFile.size.toLong(), mime)
        api.putPresigned(init.uploadUrls.first(), sealedFile)
        api.mediaComplete(session.accessToken, init.mediaId)
        mediaCache[init.mediaId] = imageBytes
        val body = MessageBody(
            text = caption,
            media = listOf(MediaRef(media_id = init.mediaId, media_key = mediaKey.toByteString(), mime = mime, size_bytes = imageBytes.size.toLong())),
        )
        return sealAndPostGroup(groupId, body, kind = 3) // CK_IMAGE
    }

    /** GK нужной версии → SecretBox(zstd(body)) → подпись group_message_canonical → отправка. */
    private suspend fun sealAndPostGroup(groupId: String, body: MessageBody, kind: Int): ChatMessage {
        val serverVersion = fetchGroupKeys(groupId)
        var version = groupKeyCache[groupId]?.keys?.maxOrNull() ?: 0
        if (version == 0) {
            // Ключа для этого устройства нет. Владелец/админ — ротирует (строго
            // current+1, чтобы покрыть в т.ч. новое устройство); иначе понятная ошибка.
            try {
                version = rotateGroup(groupId, serverVersion, "member_join")
            } catch (e: TimaApiException) {
                if (e.code == "not_group_admin") {
                    throw TimaApiException("no_group_key",
                        "Ключ группы ещё не выдан вашему устройству — дождитесь сообщения от владельца группы")
                }
                throw e
            }
        }
        val gk = groupKeyCache.getValue(groupId).getValue(version)
        val payload = EnvelopeCipher.seal(gk, MessageSerializer.encodeBody(body)).getOrThrow()
        val now = System.currentTimeMillis()
        val meta = GroupMessageMeta(
            groupId = groupId, senderId = session.userId, senderDevice = session.deviceId,
            kind = kind, createdAtUnixMs = now, gkVersion = version,
        )
        val signature = MessageSigner.sign(deviceKey, CanonicalBytes.buildGroupMessage(meta, payload)).getOrThrow()
        val messageId = api.postGroupMessage(
            session.accessToken, groupId, UUID.randomUUID().toString(),
            kind = kind, gkVersion = version, payload = b64url.encode(payload),
            createdAtUnixMs = now, signature = b64url.encode(signature),
        )
        return ChatMessage(
            groupId, messageId, session.userId, textOf(body), now, mine = true, group = true,
            media = body.media.firstOrNull()?.toAttachment(),
        )
    }

    override suspend fun groupHistory(groupId: String): List<ChatMessage> =
        api.listGroupMessages(session.accessToken, groupId)
            .mapNotNull { decryptGroup(it) }
            .sortedBy { it.messageId }

    override suspend fun recoverGroupHistory(groupId: String): List<ChatMessage> {
        // Подпись запроса ключом личности (этап 3): сервер сверит с identity_pub аккаунта.
        val canonical = "tima.recover.v1|$groupId|${session.deviceId}".encodeToByteArray()
        val signature = identityKey?.let { b64url.encode(MessageSigner.sign(it, canonical).getOrThrow()) } ?: ""
        val resp = api.recoverGroupKeys(session.accessToken, groupId, signature)
        // Есть помощники онлайн — ждём их обёртки; иначе сразу отдаём что есть.
        if (resp.helpers > 0) {
            withTimeoutOrNull(15_000) { _recoveryReady.first { it == groupId } }
        }
        fetchGroupKeys(groupId)
        return groupHistory(groupId)
    }

    /** Помощник: заворачивает свои GK запрошенных версий под ключ устройства-запросившего. */
    private suspend fun provideGroupKeys(req: RecoveryRequestFrame) {
        fetchGroupKeys(req.groupId) // подтянуть свои обёртки на случай пустого кэша
        val have = groupKeyCache[req.groupId] ?: return
        val recipientPub = b64url.decode(req.requesterEncPub)
        val ephemeral = Kodium.generateKeyPair()
        val ephPub = b64url.encode(ephemeral.getPublicKey().encryptionKey)
        val keys = req.versions.filter { have.containsKey(it) }.mapNotNull { v ->
            WrappedKeyService.wrap(ephemeral, recipientPub, have.getValue(v)).getOrNull()?.let { wrapped ->
                ProvideKeyDto(gkVersion = v, senderEphemeralPub = ephPub, wrapped = b64url.encode(wrapped))
            }
        }
        if (keys.isNotEmpty()) {
            runCatching { api.provideGroupKeys(session.accessToken, req.groupId, req.requesterDevice, keys) }
        }
    }

    /** Подпись по group_message_canonical → GK нужной версии → SecretBox → body. */
    private suspend fun decryptGroup(m: GroupMessageDto): ChatMessage? {
        val payload = b64url.decode(m.payload)
        noticeSenderDevice(m.senderId, m.senderDevice) // то же, что и в личном чате
        val senderPub = devicesOf(m.senderId).firstOrNull { it.deviceId == m.senderDevice }
            ?.let { b64url.decode(it.signingPub) } ?: return null
        val meta = GroupMessageMeta(
            groupId = m.groupId, senderId = m.senderId, senderDevice = m.senderDevice,
            kind = m.kind, createdAtUnixMs = m.createdAtUnixMs,
            threadRoot = m.threadRoot.toULong(), replyTo = m.replyTo.toULong(), gkVersion = m.gkVersion,
        )
        if (!MessageSigner.verify(senderPub, CanonicalBytes.buildGroupMessage(meta, payload), b64url.decode(m.signature))) {
            return null
        }
        val gk = groupKey(m.groupId, m.gkVersion) ?: return null
        val plain = EnvelopeCipher.open(gk, payload).getOrNull() ?: return null
        val body = MessageSerializer.decodeBody(plain).getOrNull() ?: return null
        return ChatMessage(
            chatId = m.groupId, messageId = m.messageId, senderId = m.senderId,
            text = textOf(body), createdAtMs = m.createdAtUnixMs,
            mine = m.senderId == session.userId,
            media = body.media.firstOrNull()?.toAttachment(),
            group = true,
        )
    }

    override fun close() {
        scope.cancel()
    }
}

actual fun createChatClient(session: Session): ChatClient = TimaClient(session)
