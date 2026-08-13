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
import io.tima.app.api.EscrowScopedKey
import io.tima.app.api.EscrowTrust
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
import io.tima.crypto.EscrowConfigSignature
import io.tima.crypto.EscrowKeyMeta
import io.tima.crypto.EscrowModule
import io.tima.crypto.GroupKeyManager
import io.tima.crypto.GroupMessageMeta
import io.tima.crypto.MessageSigner
import io.tima.crypto.WrappedKeyService
import io.tima.crypto.MediaCipher
import io.tima.crypto.MessageContent
import io.tima.crypto.MessageContentCodec
import io.tima.crypto.Markup
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
import io.tima.app.net.LinkState
import io.tima.app.net.classifyFailure
import io.tima.app.net.observeNetworkChanges
import io.tima.app.store.MessageStore
import io.tima.app.store.MsgState
import io.tima.app.store.OutboxAttachment
import io.tima.app.store.StoredChat
import io.tima.app.store.StoredMessage
import io.tima.app.store.openLocalDb
import kotlinx.coroutines.channels.Channel
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

/** Сервер просит сменить GK (отозвано устройство участника) — key-lifecycle.md §5. */
@Serializable
private data class RotationNeededFrame(
    @SerialName("group_id") val groupId: String,
    val reason: String = "",
)

/**
 * Размер страницы при обходе всей истории чата (выдача ключей помощником).
 * 200 — потолок, выше которого сервер молча срезает до 50 (`store.ListMessages`);
 * просить больше бессмысленно, просить меньше — лишние обращения.
 */
private const val HISTORY_PAGE = 200

/**
 * Потолок страниц за один догон истории (200 × 20 = до 4000 сообщений).
 * Предохранитель, а не ограничение возможностей: обход идёт от свежих к старым,
 * уже сохранённое остаётся в хранилище, и следующий вызов продолжит глубже.
 */
private const val HISTORY_MAX_PAGES = 20

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
    // "group" — приглашение в групповой звонок. Отвечать на него надо входом
    // в комнату, а не «взять трубку»: у группового звонка ручка другая.
    val type: String = "",
    @SerialName("group_id") val groupId: String = "",
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

    private val _linkState = MutableStateFlow(LinkState.ONLINE)
    override val linkState: StateFlow<LinkState> = _linkState

    /**
     * Локальное хранилище — источник правды для экрана (ADR-0016). База своя у
     * каждого устройства: имя содержит device_id, иначе два аккаунта на одном ПК
     * читали бы переписку друг друга.
     */
    private val store = MessageStore(
        openLocalDb("tima-${session.deviceId.take(8)}.db"),
        b64url.decode(session.deviceSecretB64),
    )

    // Будильники: «появилось что слать» и «пора переподключиться». Каналы РАЗНЫЕ —
    // на одном сигнал достался бы только одной из петель, и вторая продолжала бы спать.
    private val wakeSender = Channel<Unit>(Channel.CONFLATED)
    private val wakeSocket = Channel<Unit>(Channel.CONFLATED)

    /** Разбудить обе петли: что-то переменилось — сеть или содержимое очереди. */
    private fun nudge() {
        wakeSender.trySend(Unit)
        wakeSocket.trySend(Unit)
    }

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

    /**
     * Разметка из тела (ADR-0011, Р5б) для локального хранилища — компактный JSON,
     * пусто у сообщения без оформления (подавляющее большинство). Испорченная
     * разметка уже отфильтрована кодеком (Markup.decode → null) на уровне
     * MessageContent — здесь просто кодируем обратно то, что он вернул.
     */
    private fun markupOf(body: MessageBody): String =
        MessageContentCodec.fromBody(body).markup?.let { Markup.encode(it) }.orEmpty()

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
        verifyEscrowSignature(bundle.region, chatId, bundle.current)
        val module = EscrowModule(b64url.decode(bundle.current.publicKey), bundle.current.id.toInt())
        escrowByChat[chatId] = CachedEscrowKey(module, parseInstantMs(bundle.current.validTo))
        return module
    }

    /**
     * Подпись анклава (Р2): без неё скомпрометированный бэкенд мог бы подсунуть
     * чужой публичный ключ, и шифрование ушло бы в обход анклава и всего
     * механизма контролируемого доступа. Без зашитого ключа ([EscrowTrust])
     * проверять нечем — деградация до сегодняшнего поведения, не тихий регресс
     * (подписи не было нигде до Р2). Если ключ зашит, несовпадение — жёсткий
     * отказ: escrow и так работает fail-closed, тут тот же принцип.
     */
    private fun verifyEscrowSignature(region: String, chatId: String, key: EscrowScopedKey) {
        val trusted = EscrowTrust.ENCLAVE_SIGNING_PUBLIC_KEY ?: return
        val meta = EscrowKeyMeta(
            id = key.id, region = region, epoch = key.epoch, chatId = chatId,
            publicKey = b64url.decode(key.publicKey),
            validFromUnixMs = parseInstantMs(key.validFrom),
            validToUnixMs = parseInstantMs(key.validTo),
            destroyAtUnixMs = parseInstantMs(key.destroyAt),
        )
        val message = EscrowConfigSignature.keyMetaSigningBytes(meta)
        val verified = runCatching { EscrowConfigSignature.verify(trusted, message, b64url.decode(key.signature)) }
            .getOrDefault(false)
        check(verified) { "escrow: подпись анклава не сходится для чата $chatId — похоже на подмену ключа" }
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
        startSender() // очередь разгребается независимо от соединения
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
                        _linkState.value = LinkState.ONLINE
                        nudge() // связь вернулась — разгрести очередь немедленно
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
                                            remember(m)
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
                                "group.rotation_needed" -> {
                                    // Отозвали устройство участника: ключ у него на руках остался,
                                    // и пока версия текущая — всё под ней для него читаемо. Меняем
                                    // GK: новый заворачивается только на действующие устройства.
                                    // Сервер сам этого сделать не может — ключа он не видит.
                                    try { json.decodeFromString<RotationNeededFrame>(text) } catch (_: Throwable) { null }
                                        ?.let { req ->
                                            try {
                                                val current = api.groupKeys(session.accessToken, req.groupId, 0)
                                                rotateGroup(req.groupId, current.currentVersion, req.reason)
                                                AppDiagnostics.add("GK группы ${req.groupId.take(8)}… сменён после отзыва устройства")
                                            } catch (e: TimaApiException) {
                                                // Просьба уходит всем админским устройствам сразу, поэтому
                                                // гонка здесь штатна: кто-то успел первым, версия занята.
                                                // Это успех системы, а не сбой — цель достигнута.
                                                if (e.code != "version_conflict") {
                                                    AppDiagnostics.add("ротация GK после отзыва не удалась: ${e.message}")
                                                }
                                            } catch (e: Throwable) {
                                                AppDiagnostics.add("ротация GK после отзыва не удалась: ${e.message}")
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
                                        ?.let { _incomingCalls.emit(IncomingCall(it.callId, it.room, it.kind, it.from, group = it.type == "group")) }
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
                    _linkState.value = classifyFailure(e)
                    AppDiagnostics.add("WS: обрыв — ${e.message ?: e::class.simpleName} (${_linkState.value})")
                } finally {
                    _online.value = false
                }
                // Пауза зависит от состояния связи, а не от одного счётчика: когда
                // соединение устанавливается, а содержимое не проходит, это стена на
                // часы, и долбиться в неё раз в 15 секунд — только севшая батарея.
                // Просыпаемся раньше, если сеть переменилась (ADR-0016 §4).
                val pause = maxOf(backoffMs, _linkState.value.retryDelayMs)
                withTimeoutOrNull(pause) { wakeSocket.receive() }
                backoffMs = (backoffMs * 2).coerceAtMost(15_000L)
            }
        }
    }

    /**
     * История чата — из локального хранилища, мгновенно и без сети (ADR-0016).
     *
     * Раньше здесь был поход на сервер, поэтому без связи не открывался ни один чат:
     * человек не мог прочесть даже то, что читал минуту назад. Теперь сеть догоняет
     * в фоне и дописывает недостающее, а экран не ждёт её вовсе.
     */
    override suspend fun history(peerUserId: String): List<ChatMessage> {
        val chatId = chatIdWith(peerUserId)
        store.upsertChat(StoredChat(chatId = chatId, peerUserId = peerUserId))
        scope.launch { runCatching { pullHistory(chatId) } }
        return store.messages(chatId).map { it.toChatMessage() }
    }

    /**
     * Догон истории с сервера в хранилище. Молча ничего не делает без связи.
     *
     * Идёт страницами, а не одной выдачей: после привязки нового устройства по QR
     * телефон отдаёт ему ключи ВСЕЙ переписки (`shareHistoryWithDevice`), и
     * одностраничный догон показал бы только последнюю сотню — остальное лежало
     * бы на сервере с готовыми обёртками и не появлялось на экране.
     *
     * Потолок [HISTORY_MAX_PAGES] — предохранитель от бесконечного обхода очень
     * длинной переписки при первом запуске; следующий вызов продолжит с того же
     * места, потому что уже сохранённое в хранилище не перезапрашивается заново.
     */
    private suspend fun pullHistory(chatId: String) {
        var before = 0L
        var added = 0
        var pages = 0
        while (pages < HISTORY_MAX_PAGES) {
            val fetched = try {
                api.listMessages(session.accessToken, chatId, limit = HISTORY_PAGE, before = before)
            } catch (e: Throwable) {
                _linkState.value = classifyFailure(e)
                return
            }
            _linkState.value = LinkState.ONLINE
            if (fetched.isEmpty()) break
            for (item in fetched) {
                val wrapEph = item.wrapEphemeral.takeIf { it.isNotEmpty() }?.let { b64url.decode(it) }
                val m = decrypt(b64url.decode(item.envelope), wrapEph) ?: continue
                remember(m)
                added++
                _messages.emit(m)
            }
            before = fetched.minOf { it.messageId }
            pages++
            if (fetched.size < HISTORY_PAGE) break
        }
        if (added > 0) AppDiagnostics.add("история: добавлено $added (чат ${chatId.take(8)}…)")
    }

    /**
     * Положить сообщение в хранилище.
     *
     * Ключ записи — `client_msg_id`. У своих сообщений он свой и сохраняется с
     * момента написания; у чужих его нет, поэтому берём серверный номер — догон
     * истории постоянно пересекается с live-потоком, и без общего ключа в чате
     * появлялись бы видимые глазом дубли.
     */
    private fun remember(m: ChatMessage) {
        val cmid = m.clientMsgId.ifEmpty { "srv-${m.messageId}" }
        store.put(
            StoredMessage(
                chatId = m.chatId, messageId = m.messageId, clientMsgId = cmid,
                senderId = m.senderId, isGroup = m.group, createdAtMs = m.createdAtMs,
                state = if (m.mine) MsgState.SENT else MsgState.INCOMING,
                replyTo = m.replyTo, text = m.text, markup = m.markup,
                // Ссылку на вложение храним вместе с сообщением — иначе офлайн
                // от фото осталась бы одна подпись, а сам файл было бы не открыть.
                mediaJson = m.media?.let(::packAttachment).orEmpty(),
            ),
        )
    }

    private fun packAttachment(a: MediaAttachment): String = listOf(
        a.mediaId, b64url.encode(a.mediaKey), a.mime, a.sizeBytes.toString(), a.durationMs.toString(),
    ).joinToString("|")

    private fun unpackAttachment(s: String): MediaAttachment? {
        val p = s.split("|")
        if (p.size < 5) return null
        return MediaAttachment(
            mediaId = p[0], mediaKey = b64url.decode(p[1]), mime = p[2],
            sizeBytes = p[3].toLongOrNull() ?: 0, durationMs = p[4].toIntOrNull() ?: 0,
        )
    }

    private fun StoredMessage.toChatMessage() = ChatMessage(
        chatId = chatId, messageId = messageId, senderId = senderId, text = text, markup = markup,
        createdAtMs = createdAtMs, mine = mine, group = isGroup, replyTo = replyTo,
        readByPeer = state == MsgState.READ, pending = pending, clientMsgId = clientMsgId,
        media = mediaJson.takeIf { it.isNotEmpty() }?.let(::unpackAttachment)
        // Вложение ещё ждёт отправки — показываем его из описания, чтобы своё фото
        // было видно в чате сразу, а не появлялось только после ухода на сервер.
            ?: attachment?.let { MediaAttachment("", ByteArray(0), it.mime, it.sizeBytes, it.durationMs) },
    )

    /**
     * Кладёт сообщение в очередь и сразу возвращает — отправкой занимается [senderLoop].
     *
     * Ошибки сети здесь не бывает по устройству: не ушло — лежит и ждёт. Раньше
     * неудачная посылка выбрасывала исключение, человек видел `Read timed out`, а
     * набранный текст пропадал совсем.
     *
     * Шифруем не здесь, а в момент отправки. За время ожидания успевает смениться
     * ключ эпохи escrow и список устройств собеседника — заранее запечатанный
     * конверт ушёл бы мимо адресата (ADR-0016 §3).
     */
    override suspend fun send(peerUserId: String, text: String, replyTo: Long): ChatMessage {
        val chatId = chatIdWith(peerUserId)
        val clientMsgId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        // Собеседника запоминаем рядом с чатом — по chat_id его не восстановить.
        store.upsertChat(
            StoredChat(chatId = chatId, peerUserId = peerUserId, lastText = text, lastAtMs = now),
        )
        store.put(
            StoredMessage(
                chatId = chatId, clientMsgId = clientMsgId, senderId = session.userId,
                createdAtMs = now, state = MsgState.QUEUED, replyTo = replyTo, text = text,
            ),
        )
        nudge()
        return ChatMessage(
            chatId = chatId, messageId = 0, senderId = session.userId, text = text,
            createdAtMs = now, mine = true, replyTo = replyTo,
            pending = true, clientMsgId = clientMsgId,
        )
    }

    /**
     * Разгребает очередь. Просыпается, когда появилось что слать или вернулась связь;
     * иначе спит столько, сколько велит состояние связи.
     *
     * В [LinkState.BLOCKED] пауза длинная намеренно: когда соединение устанавливается,
     * а содержимое не проходит, это не мигание сети на секунду, а стена на часы.
     * Прежние 15 секунд в этом состоянии давали только севшую батарею.
     */
    private fun startSender() = scope.launch {
        val requeued = store.requeueStuck()
        if (requeued > 0) AppDiagnostics.add("очередь: вернул в ожидание $requeued (приложение закрыли при отправке)")
        observeNetworkChanges { nudge() }
        while (isActive) {
            val pending = runCatching { store.queued() }.getOrDefault(emptyList())
            for (m in pending) {
                if (!isActive) break
                val peer = peerOf(m.chatId) ?: continue
                store.setState(m.localId, MsgState.SENDING)
                try {
                    val id = sealAndPost(
                        peerUserId = peer, body = bodyFor(m), kind = m.kind,
                        replyTo = m.replyTo.toULong(), clientMsgId = m.clientMsgId,
                    )
                    store.markSent(m.localId, id)
                    _linkState.value = LinkState.ONLINE
                    // Тем же client_msg_id — экран заменит часики галочкой, а не
                    // покажет второе такое же сообщение рядом. Перечитываем из базы:
                    // у вложения там уже лежит ссылка на выложенный файл.
                    val sent = store.messages(m.chatId).firstOrNull { it.clientMsgId == m.clientMsgId }
                    _messages.emit((sent ?: m.copy(messageId = id, state = MsgState.SENT)).toChatMessage())
                } catch (e: Throwable) {
                    // Обратно в очередь — без ошибки на экране. Сервер отсекает
                    // повтор по client_msg_id, так что досылать можно смело.
                    store.setState(m.localId, MsgState.QUEUED)
                    _linkState.value = classifyFailure(e)
                    AppDiagnostics.add("очередь: не ушло, жду (${_linkState.value})")
                    break
                }
            }
            withTimeoutOrNull(_linkState.value.retryDelayMs) { wakeSender.receive() }
        }
    }

    /**
     * Тело сообщения из очереди. У текста собирается сразу; у вложения сперва
     * выкладывается файл — но только если он ещё не выложен.
     *
     * Ссылку на выложенный файл запоминаем, а байты стираем. Иначе выгрузка на
     * 11 МБ повторялась бы при каждой неудачной посылке, а по мобильной сети это
     * минута работы и заметный расход трафика.
     */
    private suspend fun bodyFor(m: StoredMessage): MessageBody {
        if (m.kind == 1) return bodyOf(m.text)
        val existing = m.mediaJson.takeIf { it.isNotEmpty() }?.let(::unpackMediaRef)
        val ref = existing ?: run {
            val bytes = store.attachmentBytes(m.localId)
                ?: throw IllegalStateException("байты вложения потерялись")
            val a = m.attachment
            val fresh = uploadMedia(bytes, a?.mime ?: "application/octet-stream", a?.durationMs ?: 0)
            store.attachmentUploaded(m.localId, packMediaRef(fresh), m.text)
            AppDiagnostics.add("очередь: выложено вложение ${bytes.size / 1024} КБ")
            fresh
        }
        return MessageBody(text = m.text, media = listOf(ref))
    }

    /**
     * Собеседник по chat_id. Идентификатор чата — свёртка пары и обратно не
     * разворачивается, поэтому связь хранится рядом с чатом.
     *
     * Именно в хранилище, а не в памяти: очередь обязана пережить перезапуск, а
     * запомненное в памяти после него исчезнет — и сообщение осталось бы лежать
     * вечно, не зная, кому оно адресовано.
     */
    private fun peerOf(chatId: String): String? =
        store.chats().firstOrNull { it.chatId == chatId && it.peerUserId.isNotEmpty() }?.peerUserId

    // Вложения идут через ту же очередь, что и текст: выбрал фото в метро — ушло
    // само, когда появилась связь. Файл выкладывается не здесь, а отправителем:
    // до появления связи выкладывать некуда (ADR-0016).
    override suspend fun sendImage(peerUserId: String, imageBytes: ByteArray, mime: String, caption: String): ChatMessage =
        enqueueAttachment(peerUserId, imageBytes, mime, kind = 3, caption = caption) // CK_IMAGE

    override suspend fun sendVoice(peerUserId: String, audioBytes: ByteArray, mime: String, durationMs: Int): ChatMessage {
        return enqueueAttachment(peerUserId, audioBytes, mime, kind = 2, durationMs = durationMs) // CK_VOICE
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
        enqueueAttachment(peerUserId, bytes, mime, kind = 5, name = name) // CK_FILE

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

    override suspend fun approveRecovery(consent: RecoveryConsent) {
        provideChatKeys(consent)
    }

    /**
     * Отдать историю всех личных чатов только что подключённому по QR устройству.
     *
     * Механизм тот же, что у восстановления (`provideChatKeys`): для каждого
     * сообщения снимаем обёртку своим ключом устройства, достаём `message_key` и
     * заворачиваем его заново под X25519-ключ нового устройства. Сам ключ
     * личности из фразы новому устройству не передаётся — оно получает доступ к
     * содержимому переписки, но не право выступать от имени аккаунта.
     *
     * Групповые чаты сюда не входят: там доступ даёт `GK` версии, а не обёртка на
     * сообщение, и путь выдачи отдельный (`provideGroupKeys`). Новое устройство
     * получит групповые ключи обычным порядком — при первой же ротации.
     */
    override suspend fun shareHistoryWithDevice(deviceId: String, deviceEncPub: String): Int {
        var shared = 0
        for (chat in store.chats()) {
            if (chat.isGroup) continue
            val keys = runCatching {
                provideChatKeys(RecoveryConsent(chat.chatId, deviceId, deviceEncPub))
            }.getOrDefault(0)
            if (keys > 0) shared++
        }
        AppDiagnostics.add("привязка: история отдана новому устройству по $shared чатам")
        return shared
    }

    // ── Звонки 1:1 (сигналинг; медиа — LiveKit, здесь не подключается) ──

    override suspend fun startCall(peerUserId: String, kind: String): CallConnection {
        val r = api.startCall(session.accessToken, peerUserId, kind)
        return CallConnection(r.callId, r.room, r.mediaUrl, r.token)
    }

    override suspend fun answerCall(callId: String): CallConnection {
        val r = api.answerCall(session.accessToken, callId)
        return CallConnection(callId, r.room, r.mediaUrl, r.token)
    }

    override suspend fun startGroupCall(groupId: String, kind: String): CallConnection {
        val r = api.startGroupCall(session.accessToken, groupId, kind)
        AppDiagnostics.add("действие: групповой звонок ($kind) в группе ${groupId.take(8)}…")
        return CallConnection(r.callId, r.room, r.mediaUrl, r.token)
    }

    override suspend fun joinCall(callId: String): CallConnection {
        val r = api.joinCall(session.accessToken, callId)
        AppDiagnostics.add("действие: вход в групповой звонок ${callId.take(8)}…")
        return CallConnection(callId, r.room, r.mediaUrl, r.token)
    }

    override suspend fun endCall(callId: String) = api.endCall(session.accessToken, callId)

    /** Помощник: разворачивает свои ключи сообщений чата и заворачивает под устройство-запросившее. */
    /**
     * Отдаёт ключи ВСЕЙ переписки, а не только последней страницы.
     *
     * Раньше здесь был один вызов `listMessages` с умолчанием в 100 сообщений:
     * человек восстанавливал историю и получал её хвост, молча и без признака,
     * что остальное осталось недоступным. Сервер отдаёт страницами (`before` —
     * идентификатор, ниже которого брать; порядок по убыванию), поэтому идём
     * страницами до пустой выдачи.
     *
     * @return сколько ключей сообщений реально отдано (0 — отдавать было нечего).
     */
    private suspend fun provideChatKeys(consent: RecoveryConsent): Int {
        val recipientPub = b64url.decode(consent.requesterEncPub)
        val ephemeral = Kodium.generateKeyPair()
        val ephPub = b64url.encode(ephemeral.getPublicKey().encryptionKey)
        var before = 0L // 0 — с самого свежего
        var provided = 0
        while (true) {
            val page = api.listMessages(session.accessToken, consent.chatId, limit = HISTORY_PAGE, before = before)
            if (page.isEmpty()) break
            val keys = page.mapNotNull { item ->
                val sealed = MessageSerializer.decodeEnvelope(b64url.decode(item.envelope)).getOrNull() ?: return@mapNotNull null
                val wrapped = sealed.wrappedKeys[session.deviceId] ?: return@mapNotNull null
                val wrapEph = item.wrapEphemeral.takeIf { it.isNotEmpty() }?.let { b64url.decode(it) } ?: sealed.senderEphemeralPub
                val messageKey = WrappedKeyService.unwrap(deviceKey, wrapEph, wrapped).getOrNull() ?: return@mapNotNull null
                val reWrapped = WrappedKeyService.wrap(ephemeral, recipientPub, messageKey).getOrNull() ?: return@mapNotNull null
                ProvideMsgKeyDto(item.messageId, ephPub, b64url.encode(reWrapped))
            }
            if (keys.isNotEmpty()) {
                // Ошибку глушим намеренно: это фоновая любезность помощника, а не
                // действие человека — запросивший просто попробует ещё раз. Но
                // страницы дальше не идём: раз посылка не прошла, следующая скорее
                // всего тоже не пройдёт, а перебирать всю переписку впустую незачем.
                val ok = runCatching {
                    api.provideChatKeys(session.accessToken, consent.chatId, consent.requesterDevice, keys)
                }.isSuccess
                if (!ok) break
                provided += keys.size
            }
            // Следующая страница — строго старше самого старого в этой.
            before = page.minOf { it.messageId }
            if (page.size < HISTORY_PAGE) break // страница неполная — дальше пусто
        }
        return provided
    }

    /**
     * Запечатывает и отправляет. Возвращает серверный идентификатор сообщения.
     *
     * [clientMsgId] приходит снаружи, когда сообщение уже лежит в очереди: он
     * рождается вместе с сообщением и переживает перезапуск, поэтому повторная
     * посылка после сбоя не создаёт дубля — сервер отсекает её по этому полю.
     */
    private suspend fun sealAndPost(
        peerUserId: String, body: MessageBody, kind: Int, replyTo: ULong = 0u,
        clientMsgId: String = UUID.randomUUID().toString(),
    ): Long {
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
        api.postEnvelope(session.accessToken, MessageSerializer.encodeEnvelope(sealed), clientMsgId)

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
        return messageId.toLong()
    }

    /**
     * Ссылка на выложенный файл в одну строку — ложится туда же, где текст, и так
     * же под шифрованием хранилища. Отдельной таблицы не завожу: полей пять, и
     * читает их только отправитель.
     */
    private fun packMediaRef(m: MediaRef): String = listOf(
        m.media_id, b64url.encode(m.media_key.toByteArray()), m.mime,
        m.size_bytes.toString(), m.duration_ms.toString(),
    ).joinToString("|")

    private fun unpackMediaRef(s: String): MediaRef? {
        val p = s.split("|")
        if (p.size < 5) return null
        return MediaRef(
            media_id = p[0], media_key = b64url.decode(p[1]).toByteString(), mime = p[2],
            size_bytes = p[3].toLongOrNull() ?: 0, duration_ms = p[4].toIntOrNull() ?: 0,
        )
    }

    /**
     * Выложить файл в хранилище медиа. Зовёт ОТПРАВИТЕЛЬ, а не тот, кто выбрал файл:
     * до появления связи выкладывать некуда.
     */
    private suspend fun uploadMedia(bytes: ByteArray, mime: String, durationMs: Int): MediaRef {
        val mediaKey = ByteArray(32).also(random::nextBytes)
        val sealedFile = MediaCipher.seal(mediaKey, bytes).getOrThrow()
        val init = api.mediaInit(session.accessToken, sealedFile.size.toLong(), mime)
        api.putPresigned(init.uploadUrls.first(), sealedFile)
        api.mediaComplete(session.accessToken, init.mediaId)
        mediaCache[init.mediaId] = bytes // своё вложение не перекачивать
        return MediaRef(
            media_id = init.mediaId, media_key = mediaKey.toByteString(), mime = mime,
            size_bytes = bytes.size.toLong(), duration_ms = durationMs,
        )
    }

    /**
     * Положить вложение в очередь: байты ложатся на диск под шифрованием и ждут
     * связи наравне с текстом.
     */
    private fun enqueueAttachment(
        peerUserId: String, bytes: ByteArray, mime: String, kind: Int,
        caption: String = "", name: String = "", durationMs: Int = 0,
    ): ChatMessage {
        val chatId = chatIdWith(peerUserId)
        val clientMsgId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val text = if (kind == 5) name else caption // у файла в тексте едет имя
        store.upsertChat(StoredChat(chatId = chatId, peerUserId = peerUserId, lastText = text, lastAtMs = now))
        store.put(
            StoredMessage(
                chatId = chatId, clientMsgId = clientMsgId, senderId = session.userId,
                createdAtMs = now, state = MsgState.QUEUED, text = text, kind = kind,
                attachment = OutboxAttachment(mime, name, durationMs, bytes.size.toLong()),
            ),
            attachmentBytes = bytes,
        )
        nudge()
        return ChatMessage(
            chatId = chatId, messageId = 0, senderId = session.userId, text = text,
            createdAtMs = now, mine = true, pending = true, clientMsgId = clientMsgId,
        )
    }

    /** Отправка «сейчас же» — осталась для групповых вложений. */
    private suspend fun sealAndPostNow(
        peerUserId: String, body: MessageBody, kind: Int, replyTo: ULong = 0u,
    ): ChatMessage {
        val id = sealAndPost(peerUserId, body, kind, replyTo)
        val chatId = chatIdWith(peerUserId)
        val now = System.currentTimeMillis()
        store.put(
            StoredMessage(
                chatId = chatId, messageId = id, clientMsgId = "sent-$id",
                senderId = session.userId, createdAtMs = now, state = MsgState.SENT,
                replyTo = replyTo.toLong(), text = textOf(body), markup = markupOf(body),
            ),
        )
        return ChatMessage(
            chatId, id, session.userId, textOf(body), now, mine = true,
            media = body.media.firstOrNull()?.toAttachment(),
            replyTo = replyTo.toLong(), markup = markupOf(body),
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
            markup = markupOf(body),
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
            media = body.media.firstOrNull()?.toAttachment(), markup = markupOf(body),
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
            text = textOf(body), markup = markupOf(body), createdAtMs = m.createdAtUnixMs,
            mine = m.senderId == session.userId,
            media = body.media.firstOrNull()?.toAttachment(),
            group = true,
        )
    }

    override fun close() {
        scope.cancel()
        runCatching { store.close() }
    }
}

actual fun createChatClient(session: Session): ChatClient = TimaClient(session)
