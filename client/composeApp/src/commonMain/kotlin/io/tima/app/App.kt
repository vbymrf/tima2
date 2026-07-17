@file:OptIn(ExperimentalEncodingApi::class, ExperimentalFoundationApi::class)

package io.tima.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.kodium.Kodium
import io.tima.app.api.AppVersionDto
import io.tima.app.api.ChannelDto
import io.tima.app.api.ChannelPostDto
import io.tima.app.api.TimaApi
import io.tima.app.api.VoiceRoomDto
import io.tima.app.chat.CallConnection
import io.tima.app.chat.ChatClient
import io.tima.app.chat.ChatMessage
import io.tima.app.chat.ChatClientHolder
import io.tima.app.chat.Contact
import io.tima.app.chat.GroupSummary
import io.tima.app.chat.MediaAttachment
import io.tima.app.chat.PeerInfo
import io.tima.app.chat.RecoveryConsent
import io.tima.app.chat.isFile
import io.tima.app.chat.isVoice
import io.tima.app.chat.preview
import io.tima.app.diag.AppDiagnostics
import io.tima.app.platform.CallEngine
import io.tima.app.platform.CallMediaState
import io.tima.app.platform.CallVideoView
import io.tima.app.platform.contactsSupported
import io.tima.app.platform.currentVersionCode
import io.tima.app.platform.decodeImage
import io.tima.app.platform.ensureCallPermissions
import io.tima.app.platform.cancelVoiceRecording
import io.tima.app.platform.identityFromPhrase
import io.tima.app.platform.keepScreenOn
import io.tima.app.platform.installUpdate
import io.tima.app.platform.newIdentity
import io.tima.app.platform.normalizePhone
import io.tima.app.platform.openFile
import io.tima.app.platform.pickFile
import io.tima.app.platform.pickImage
import io.tima.app.platform.playVoice
import io.tima.app.platform.shareText
import io.tima.app.platform.backgroundServiceRunning
import io.tima.app.platform.backgroundSupported
import io.tima.app.platform.batteryOptimizationIgnored
import io.tima.app.platform.requestIgnoreBatteryOptimization
import io.tima.app.platform.startBackgroundService
import io.tima.app.platform.startRinging
import io.tima.app.platform.startVoiceRecording
import io.tima.app.platform.stopBackgroundService
import io.tima.app.platform.stopRinging
import io.tima.app.platform.stopVoice
import io.tima.app.platform.stopVoiceRecording
import io.tima.app.platform.voiceRecordingSupported
import io.tima.app.session.AppPrefs
import io.tima.app.session.ChatEntry
import io.tima.app.session.Session
import io.tima.app.session.SessionCodec
import io.tima.app.session.defaultServerUrl
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private sealed interface Screen {
    data object Phone : Screen
    data class Code(val serverUrl: String, val phone: String, val requestId: String, val devCode: String?) : Screen
    data class Home(val session: Session) : Screen
    data class Contacts(val session: Session) : Screen
    data class Chat(val session: Session, val peerUserId: String, val peerPhone: String) : Screen
    data class GroupChat(val session: Session, val groupId: String, val title: String) : Screen
    data class PhraseReveal(val session: Session, val phrase: List<String>) : Screen
    data class ChannelView(val session: Session, val channelId: String, val title: String, val owner: Boolean) : Screen
    data class VoiceRoom(val session: Session, val roomId: String, val title: String) : Screen
}

private val b64url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

/** Имя+телефон одного собеседника; null, пока сервер не ответил — тогда решает вызывающий. */
@Composable
private fun rememberPeer(client: ChatClient?, userId: String): PeerInfo? {
    var info by remember(userId) { mutableStateOf<PeerInfo?>(null) }
    LaunchedEffect(client, userId) {
        if (client != null && userId.isNotEmpty()) {
            info = runCatching { client.resolvePeers(listOf(userId)) }.getOrNull()?.get(userId)
        }
    }
    return info
}

/** То же пачкой — для списка чатов (один запрос на все записи). */
@Composable
private fun rememberPeers(client: ChatClient?, userIds: List<String>): Map<String, PeerInfo> {
    val map = remember { mutableStateMapOf<String, PeerInfo>() }
    val key = userIds.sorted().joinToString(",")
    LaunchedEffect(client, key) {
        if (client != null && userIds.isNotEmpty()) {
            runCatching { client.resolvePeers(userIds) }.getOrNull()?.let { map.putAll(it) }
        }
    }
    return map
}

@Composable
fun App() {
    var screen by remember { mutableStateOf(SessionCodec.load()?.let { Screen.Home(it) } ?: Screen.Phone) }
    var chats by remember { mutableStateOf(SessionCodec.loadChats()) }
    val scope = rememberCoroutineScope()

    val session = when (val s = screen) {
        is Screen.Home -> s.session
        is Screen.Chat -> s.session
        is Screen.GroupChat -> s.session
        is Screen.ChannelView -> s.session
        is Screen.VoiceRoom -> s.session
        else -> null
    }
    // Один ChatClient (одно WS) на сессию. Живёт в холдере, а не в композиции: соединение
    // должно пережить закрытие экрана, иначе входящий звонок приходить некуда.
    // Закрывается только при выходе из аккаунта.
    val client = remember(session?.deviceId) { session?.let { ChatClientHolder.get(it) } }
    // Запрос собеседника на восстановление — показываем диалог согласия
    var consent by remember { mutableStateOf<RecoveryConsent?>(null) }
    LaunchedEffect(client) {
        client?.consentRequests?.collect { consent = it }
    }
    consent?.let { req ->
        val c = client
        AlertDialog(
            onDismissRequest = { consent = null },
            title = { Text("Запрос на восстановление") },
            text = { Text("Собеседник просит отдать ему историю этой переписки для входа на новом устройстве. Разрешить?") },
            confirmButton = {
                Button(onClick = {
                    consent = null
                    scope.launch { runCatching { c?.approveRecovery(req) } }
                }) { Text("Разрешить") }
            },
            dismissButton = {
                Button(onClick = { consent = null }) { Text("Отклонить") }
            },
        )
    }

    // Авто-обновление: при входе спрашиваем сервер о последней версии клиента
    var update by remember { mutableStateOf<AppVersionDto?>(null) }
    var updating by remember { mutableStateOf(false) }
    var updatePercent by remember { mutableStateOf(0) }
    LaunchedEffect(session?.serverUrl) {
        val url = session?.serverUrl ?: return@LaunchedEffect
        AppDiagnostics.serverUrl = url
        AppDiagnostics.appVersion = currentVersionCode()
        runCatching {
            val latest = TimaApi(url).appVersion()
            if (latest != null && latest.url.isNotEmpty() && latest.versionCode > currentVersionCode()) {
                update = latest
            }
        }
    }
    update?.let { u ->
        AlertDialog(
            onDismissRequest = { if (!updating) update = null },
            title = { Text("Доступно обновление") },
            text = {
                Text(buildString {
                    append("Новая версия ${u.versionName}.")
                    if (u.notes.isNotEmpty()) append("\n\n${u.notes}")
                    // Показываем процент: иначе непонятно, идёт ли что-то, и хочется
                    // нажать на уведомление загрузки — а это чужой путь установки
                    if (updating) append("\n\nСкачиваю… $updatePercent%")
                })
            },
            confirmButton = {
                Button(enabled = !updating, onClick = {
                    updating = true; updatePercent = 0
                    scope.launch {
                        runCatching { installUpdate(u) { p -> updatePercent = p } }
                        updating = false
                        update = null
                    }
                }) { Text("Обновить") }
            },
            dismissButton = {
                Button(enabled = !updating, onClick = { update = null }) { Text("Позже") }
            },
        )
    }

    // Активный звонок (оверлей поверх всего): входящий/исходящий/в разговоре
    var activeCall by remember { mutableStateOf<CallUi?>(null) }
    LaunchedEffect(client) {
        val c = client ?: return@LaunchedEffect
        c.incomingCalls.collect { inc ->
            // Не перебиваем уже идущий звонок; иначе показываем входящий
            if (activeCall == null) {
                val who = runCatching { c.resolvePeers(listOf(inc.fromUserId)) }.getOrNull()?.get(inc.fromUserId)
                activeCall = CallUi(
                    inc.callId,
                    who?.label ?: (inc.fromUserId.take(8) + "…"),
                    inc.kind, CallDir.Incoming, null,
                )
            }
        }
    }
    LaunchedEffect(client) {
        client?.callStates?.collect { st ->
            if (activeCall?.callId != st.callId) return@collect
            when (st.state) {
                "answered" -> activeCall = activeCall?.copy(direction = CallDir.Connected)
                "ended", "missed" -> activeCall = null
            }
        }
    }

    // Вошёл — поднимаем фоновую доставку (после входа сервис ещё не запущен)
    LaunchedEffect(session?.deviceId) {
        if (session != null && AppPrefs.backgroundEnabled) startBackgroundService()
    }
    LaunchedEffect(client) {
        val c = client ?: return@LaunchedEffect
        c.start()
        c.messages.collect { msg ->
            if (msg.group) return@collect // группы живут своим списком (серверным), без локальных записей
            val known = SessionCodec.loadChats().any { it.chatId == msg.chatId }
            // Своё эхо с неизвестным чатом не создаёт запись: peer из senderId не извлечь
            if (!known && msg.mine) return@collect
            val isOpen = msg.mine ||
                (screen as? Screen.Chat)?.let { c.chatIdWith(it.peerUserId) == msg.chatId } == true
            chats = SessionCodec.noteMessage(msg.chatId, msg.senderId, msg.preview(), msg.createdAtMs, isOpen)
        }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            // Оверлей звонка перекрывает контент, когда звонок активен
            activeCall?.let { call ->
                CallOverlay(
                    call = call,
                    onAccept = {
                        val c = client ?: return@CallOverlay
                        scope.launch {
                            runCatching { c.answerCall(call.callId) }
                                .onSuccess { activeCall = call.copy(direction = CallDir.Connected, connection = it) }
                                .onFailure { activeCall = null }
                        }
                    },
                    onEnd = {
                        val c = client
                        val id = call.callId
                        activeCall = null
                        scope.launch { runCatching { c?.endCall(id) } }
                    },
                )
                return@Surface
            }
            when (val s = screen) {
                // Чат сам управляет раскладкой (LazyColumn несовместим с внешним verticalScroll)
                is Screen.Chat -> {
                    // «Имя +7999…»; s.peerPhone (как чат назван локально) — пока сервер не ответил
                    val self = s.peerUserId == s.session.userId
                    val peerLabel = rememberPeer(client, s.peerUserId)?.label ?: s.peerPhone
                    ChatScreen(
                        client = client ?: return@Surface,
                        title = if (self) "Заметки" else "Чат с $peerLabel",
                        targetId = s.peerUserId,
                        isGroup = false,
                        onRead = { chatId -> chats = SessionCodec.markRead(chatId) },
                        onCall = { kind ->
                            val c = client ?: return@ChatScreen
                            AppDiagnostics.add("действие: звонок ($kind) → $peerLabel")
                            scope.launch {
                                runCatching { c.startCall(s.peerUserId, kind) }
                                    .onSuccess { activeCall = CallUi(it.callId, peerLabel, kind, CallDir.Outgoing, it) }
                                    .onFailure { activeCall = null }
                            }
                        },
                        onBack = { screen = Screen.Home(s.session) },
                    )
                }
                is Screen.GroupChat -> ChatScreen(
                    client = client ?: return@Surface,
                    title = "Группа: ${s.title}",
                    targetId = s.groupId,
                    isGroup = true,
                    onRead = {},
                    onBack = { screen = Screen.Home(s.session) },
                )
                is Screen.ChannelView -> ChannelScreen(s, onBack = { screen = Screen.Home(s.session) })
                is Screen.VoiceRoom -> VoiceRoomScreen(s, client = client, onBack = { screen = Screen.Home(s.session) })
                is Screen.Contacts -> ContactsScreen(
                    client = client ?: return@Surface,
                    onOpenChat = { c ->
                        val entry = ChatEntry(
                            title = c.name.ifBlank { c.phone },
                            peerUserId = c.userId,
                            chatId = client.chatIdWith(c.userId),
                        )
                        chats = SessionCodec.rememberChat(entry)
                        screen = Screen.Chat(s.session, entry.peerUserId, entry.title)
                    },
                    onCall = { c, kind ->
                        val label = (c.name + " " + c.phone).trim()
                        AppDiagnostics.add("действие: звонок ($kind) → $label")
                        scope.launch {
                            runCatching { client.startCall(c.userId, kind) }
                                .onSuccess { activeCall = CallUi(it.callId, label, kind, CallDir.Outgoing, it) }
                                .onFailure { activeCall = null }
                        }
                    },
                    onBack = { screen = Screen.Home(s.session) },
                )
                else -> Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    when (s) {
                        is Screen.Phone -> PhoneScreen(onCode = { screen = it })
                        is Screen.Code -> CodeScreen(
                            s,
                            onRegistered = { session, newPhrase ->
                                screen = if (newPhrase != null) Screen.PhraseReveal(session, newPhrase)
                                else Screen.Home(session)
                            },
                            onBack = { screen = Screen.Phone },
                        )
                        is Screen.PhraseReveal -> PhraseRevealScreen(s, onDone = { screen = Screen.Home(s.session) })
                        is Screen.Home -> HomeScreen(
                            s.session,
                            chats = chats,
                            client = client,
                            onOpen = { entry ->
                                chats = SessionCodec.markRead(entry.chatId)
                                screen = Screen.Chat(s.session, entry.peerUserId, entry.title)
                            },
                            onOpenGroup = { g -> screen = Screen.GroupChat(s.session, g.groupId, g.title) },
                            onOpenChannel = { c -> screen = Screen.ChannelView(s.session, c.channelId, c.title, c.owner) },
                            onOpenVoice = { v -> screen = Screen.VoiceRoom(s.session, v.roomId, v.title) },
                            onOpenContacts = { screen = Screen.Contacts(s.session) },
                            onChatsChange = { chats = it },
                            onLogout = {
                                stopBackgroundService()
                                ChatClientHolder.close() // соединение живёт вне экрана — рвём явно
                                SessionCodec.clear()
                                chats = emptyList()
                                screen = Screen.Phone
                            },
                        )
                        else -> Unit // Chat/GroupChat/ChannelView/VoiceRoom/Contacts обработаны выше
                    }
                }
            }
        }
    }
}

@Composable
private fun PhoneScreen(onCode: (Screen.Code) -> Unit) {
    var serverUrl by remember { mutableStateOf(defaultServerUrl()) }
    var phone by remember { mutableStateOf("+7") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Text("TIMA", style = MaterialTheme.typography.headlineLarge)
    Spacer(Modifier.height(24.dp))
    OutlinedTextField(
        value = serverUrl, onValueChange = { serverUrl = it },
        label = { Text("Сервер") }, singleLine = true,
        modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = phone, onValueChange = { phone = it },
        label = { Text("Телефон (+79991234567)") }, singleLine = true,
        modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
    )
    Spacer(Modifier.height(16.dp))
    if (busy) {
        CircularProgressIndicator()
    } else {
        Button(onClick = {
            busy = true; error = null
            scope.launch {
                try {
                    val p = normalizePhone(phone) ?: phone.trim()
                    AppDiagnostics.add("действие: запрос кода для $p (сервер $serverUrl)")
                    val resp = TimaApi(serverUrl).smsRequest(p)
                    onCode(Screen.Code(serverUrl, p, resp.requestId, resp.devCode))
                } catch (e: Throwable) {
                    error = e.message ?: e.toString()
                } finally {
                    busy = false
                }
            }
        }) { Text("Получить код") }
    }
    ErrorText(error)
}

@Composable
private fun CodeScreen(
    state: Screen.Code,
    onRegistered: (Session, List<String>?) -> Unit, // phrase != null → показать её (новый аккаунт)
    onBack: () -> Unit,
) {
    var code by remember { mutableStateOf(state.devCode ?: "") }
    var phraseInput by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Text("Код из SMS", style = MaterialTheme.typography.headlineMedium)
    if (state.devCode != null) {
        Spacer(Modifier.height(8.dp))
        Text("dev-сервер прислал код: ${state.devCode}", style = MaterialTheme.typography.bodyMedium)
    }
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        value = code, onValueChange = { code = it },
        label = { Text("6 цифр") }, singleLine = true,
        modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = phraseInput, onValueChange = { phraseInput = it },
        label = { Text("Секретная фраза (для входа на новом устройстве)") },
        modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
    )
    Text(
        "Пусто — создадим новый аккаунт и покажем фразу.",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.widthIn(max = 420.dp),
    )
    Spacer(Modifier.height(16.dp))
    if (busy) {
        CircularProgressIndicator()
    } else {
        Button(onClick = {
            busy = true; error = null
            scope.launch {
                try {
                    // Ключ личности: введённая фраза (новое устройство) или новая (регистрация)
                    val entered = phraseInput.trim()
                    val identity = if (entered.isEmpty()) newIdentity()
                    else identityFromPhrase(entered.split(Regex("\\s+")))
                    if (identity == null) {
                        error = "Неверная секретная фраза — проверьте слова"
                        busy = false
                        return@launch
                    }
                    val api = TimaApi(state.serverUrl)
                    val token = api.smsVerify(state.requestId, code.trim()).registrationToken
                    // Ключи устройства: один seed → X25519 (конверты) + Ed25519 (подпись)
                    val deviceKey = Kodium.generateKeyPair()
                    val pub = deviceKey.getPublicKey()
                    val reg = api.register(
                        registrationToken = token,
                        encryptionPub = b64url.encode(pub.encryptionKey),
                        signingPub = b64url.encode(pub.signingKey),
                        identityPub = identity.pubB64,
                    )
                    val session = Session(
                        serverUrl = state.serverUrl,
                        phone = state.phone,
                        userId = reg.userId,
                        deviceId = reg.deviceId,
                        accessToken = reg.accessToken,
                        deviceSecretB64 = b64url.encode(deviceKey.secretKey),
                        identitySecretB64 = identity.secretB64,
                        backupSecretB64 = identity.backupB64,
                    )
                    SessionCodec.save(session)
                    // Новую фразу показываем; введённую — не повторяем
                    onRegistered(session, if (entered.isEmpty()) identity.phrase else null)
                } catch (e: Throwable) {
                    error = e.message ?: e.toString()
                } finally {
                    busy = false
                }
            }
        }) { Text("Войти") }
    }
    Spacer(Modifier.height(8.dp))
    Button(onClick = onBack, enabled = !busy) { Text("Назад") }
    ErrorText(error)
}

@Composable
private fun PhraseRevealScreen(state: Screen.PhraseReveal, onDone: () -> Unit) {
    Text("Сохраните секретную фразу", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(12.dp))
    Text(
        "Эти 12 слов восстанавливают доступ на новом устройстве и вашу переписку. " +
            "Запишите их по порядку и храните в тайне — восстановить фразу нельзя.",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.widthIn(max = 420.dp),
    )
    Spacer(Modifier.height(16.dp))
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
    ) {
        Text(
            state.phrase.mapIndexed { i, w -> "${i + 1}. $w" }.joinToString("   "),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp),
        )
    }
    Spacer(Modifier.height(20.dp))
    Button(onClick = onDone) { Text("Я записал(а) фразу") }
}

@Composable
private fun HomeScreen(
    session: Session,
    chats: List<ChatEntry>,
    client: ChatClient?,
    onOpen: (ChatEntry) -> Unit,
    onOpenGroup: (GroupSummary) -> Unit,
    onOpenChannel: (ChannelDto) -> Unit,
    onOpenVoice: (VoiceRoomDto) -> Unit,
    onOpenContacts: () -> Unit,
    onChatsChange: (List<ChatEntry>) -> Unit,
    onLogout: () -> Unit,
) {
    var peerPhone by remember { mutableStateOf("+7") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var groups by remember { mutableStateOf<List<GroupSummary>>(emptyList()) }
    var groupTitle by remember { mutableStateOf("") }
    var groupPhones by remember { mutableStateOf("") }
    var showCreateGroup by remember { mutableStateOf(false) }
    var myChannels by remember { mutableStateOf<List<ChannelDto>>(emptyList()) }
    var discover by remember { mutableStateOf<List<ChannelDto>>(emptyList()) }
    var channelTitle by remember { mutableStateOf("") }
    var showCreateChannel by remember { mutableStateOf(false) }
    var voiceRooms by remember { mutableStateOf<List<VoiceRoomDto>>(emptyList()) }
    var voiceTitle by remember { mutableStateOf("") }
    var showCreateVoice by remember { mutableStateOf(false) }
    val api = remember(session.deviceId) { TimaApi(session.serverUrl) }
    val scope = rememberCoroutineScope()

    suspend fun refreshGroups() {
        client ?: return
        try {
            groups = client.myGroups()
        } catch (_: Throwable) {
            // сервер недоступен — секция просто пуста, чаты работают из локального списка
        }
    }
    suspend fun refreshChannels() {
        try {
            myChannels = api.myChannels(session.accessToken)
            discover = api.discoverChannels(session.accessToken)
        } catch (_: Throwable) {
        }
    }
    suspend fun refreshVoice() {
        try { voiceRooms = api.listVoiceRooms(session.accessToken) } catch (_: Throwable) {}
    }
    LaunchedEffect(client) {
        refreshGroups()
        refreshChannels()
        refreshVoice()
    }

    var myName by remember { mutableStateOf("") }
    var nameSaved by remember { mutableStateOf(false) }
    // Подтягиваем ранее сохранённое имя с сервера, чтобы поле не выглядело пустым при входе
    LaunchedEffect(client) {
        val c = client ?: return@LaunchedEffect
        runCatching { c.resolvePeers(listOf(session.userId)) }.getOrNull()
            ?.get(session.userId)?.name?.takeIf { it.isNotEmpty() }
            ?.let { if (myName.isEmpty()) { myName = it; nameSaved = true } }
    }

    Text("TIMA", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(8.dp))
    // «Имя +7999…»; пока имя не задано — только номер
    val me = listOf(myName.trim(), session.phone).filter { it.isNotEmpty() }.joinToString(" ")
    Text(
        "Вы вошли: " + me.ifEmpty { session.userId.take(8) + "…" },
        style = MaterialTheme.typography.titleMedium,
    )
    Spacer(Modifier.height(8.dp))
    // Своё публичное имя — собеседники увидят его вместо номера
    Row(
        modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = myName, onValueChange = { myName = it; nameSaved = false },
            label = { Text("Ваше имя") }, singleLine = true, modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Button(enabled = !busy && myName.isNotBlank() && client != null, onClick = {
            busy = true
            scope.launch {
                try { client!!.setMyName(myName.trim()); nameSaved = true } catch (e: Throwable) { error = e.message }
                finally { busy = false }
            }
        }) { Text(if (nameSaved) "✓" else "OK") }
    }
    Spacer(Modifier.height(16.dp))

    // Обновление приложения (ручная проверка) + отправка логов диагностики
    var updateInfo by remember { mutableStateOf<AppVersionDto?>(null) }
    var updateMsg by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(enabled = !checking, modifier = Modifier.weight(1f), onClick = {
            checking = true; updateMsg = null
            scope.launch {
                val latest = runCatching { TimaApi(session.serverUrl).appVersion() }.getOrNull()
                checking = false
                when {
                    latest == null -> updateMsg = "Обновление на сервере не настроено"
                    latest.versionCode > currentVersionCode() -> updateInfo = latest
                    else -> updateMsg = "Установлена последняя версия"
                }
            }
        }) { Text("Обновить приложение") }
        Button(modifier = Modifier.weight(1f), onClick = {
            scope.launch { runCatching { shareText("TIMA диагностика", AppDiagnostics.dump()) } }
        }) { Text("Отправить логи") }
    }
    updateMsg?.let { Text(it, style = MaterialTheme.typography.labelSmall) }

    if (backgroundSupported()) {
        Spacer(Modifier.height(8.dp))
        BackgroundCard()
    }
    var downloading by remember { mutableStateOf(false) }
    var percent by remember { mutableStateOf(0) }
    updateInfo?.let { u ->
        AlertDialog(
            onDismissRequest = { if (!downloading) updateInfo = null },
            title = { Text("Доступно обновление") },
            text = {
                Text(
                    "Версия ${u.versionName}." +
                        (if (u.notes.isNotEmpty()) "\n\n${u.notes}" else "") +
                        // Прогресс в приложении: чтобы не тянуться к уведомлению загрузки —
                        // по нему APK открывает система чужим обработчиком
                        (if (downloading) "\n\nСкачиваю… $percent%\nПо окончании откроется установщик." else ""),
                )
            },
            confirmButton = {
                Button(enabled = !downloading, onClick = {
                    downloading = true; percent = 0
                    scope.launch {
                        runCatching { installUpdate(u) { p -> percent = p } }
                            .onFailure { updateMsg = it.message ?: "Не удалось установить обновление" }
                        downloading = false
                        updateInfo = null
                    }
                }) { Text("Обновить") }
            },
            dismissButton = {
                Button(enabled = !downloading, onClick = { updateInfo = null }) { Text("Позже") }
            },
        )
    }
    Spacer(Modifier.height(16.dp))

    // Заметки — личный чат с самим собой (сообщения себе, бэкап под фразу, этап 4)
    Button(
        onClick = { onOpen(ChatEntry(title = "Заметки", peerUserId = session.userId, chatId = "")) },
        enabled = !busy,
        modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(vertical = 2.dp),
    ) { Text("📝 Заметки (сообщения себе)") }
    Spacer(Modifier.height(16.dp))

    if (chats.isNotEmpty()) {
        // Заголовки записей — снимок на момент создания (у незнакомца там id): показываем
        // резолв с сервера, а сохранённый title оставляем запасным вариантом
        val peers = rememberPeers(client, chats.map { it.peerUserId }.filter { it != session.userId })
        Text("Чаты", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        chats.forEach { entry ->
            val title = peers[entry.peerUserId]?.label ?: entry.title
            Button(
                onClick = { AppDiagnostics.add("действие: открыть чат «$title»"); onOpen(entry) },
                enabled = !busy,
                modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(vertical = 2.dp),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, modifier = Modifier.weight(1f))
                        if (entry.unread > 0) {
                            Text("● ${entry.unread}", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    if (entry.lastText.isNotEmpty()) {
                        Text(
                            entry.lastText,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }

    Row(
        modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Группы", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Button(enabled = !busy, onClick = { scope.launch { refreshGroups() } }) { Text("⟳") }
    }
    Spacer(Modifier.height(8.dp))
    if (groups.isEmpty()) {
        Text("Групп пока нет", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
    } else {
        groups.forEach { g ->
            Button(
                onClick = { onOpenGroup(g) },
                enabled = !busy,
                modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(vertical = 2.dp),
            ) { Text("👥 ${g.title}") }
        }
        Spacer(Modifier.height(16.dp))
    }

    if (showCreateGroup) {
        OutlinedTextField(
            value = groupTitle, onValueChange = { groupTitle = it },
            label = { Text("Название группы") }, singleLine = true,
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = groupPhones, onValueChange = { groupPhones = it },
            label = { Text("Телефоны участников через запятую") }, singleLine = true,
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Button(enabled = !busy && groupTitle.isNotBlank() && client != null, onClick = {
            busy = true; error = null
            scope.launch {
                try {
                    val g = client!!.createGroup(groupTitle.trim(), groupPhones.split(","))
                    groups = client.myGroups()
                    showCreateGroup = false; groupTitle = ""; groupPhones = ""
                    onOpenGroup(g)
                } catch (e: Throwable) {
                    error = e.message ?: e.toString()
                    try { groups = client!!.myGroups() } catch (_: Throwable) {}
                } finally {
                    busy = false
                }
            }
        }) { Text("Создать") }
        Spacer(Modifier.height(16.dp))
    } else {
        Button(onClick = { showCreateGroup = true }, enabled = !busy) { Text("Создать группу") }
        Spacer(Modifier.height(16.dp))
    }

    // Каналы (вещание): мои + каталог публичных
    Row(
        modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Каналы", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Button(enabled = !busy, onClick = { scope.launch { refreshChannels() } }) { Text("⟳") }
    }
    Spacer(Modifier.height(8.dp))
    myChannels.forEach { c ->
        Button(
            onClick = { onOpenChannel(c) }, enabled = !busy,
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(vertical = 2.dp),
        ) { Text((if (c.owner) "📢 " else "📣 ") + c.title) }
    }
    if (discover.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text("Каталог — подписаться:", style = MaterialTheme.typography.bodySmall)
        discover.forEach { c ->
            Row(
                modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(c.title, modifier = Modifier.weight(1f))
                Button(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        try { api.subscribeChannel(session.accessToken, c.channelId); refreshChannels() }
                        catch (e: Throwable) { error = e.message } finally { busy = false }
                    }
                }) { Text("Подписаться") }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    if (showCreateChannel) {
        OutlinedTextField(
            value = channelTitle, onValueChange = { channelTitle = it },
            label = { Text("Название канала") }, singleLine = true,
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Button(enabled = !busy && channelTitle.isNotBlank(), onClick = {
            busy = true; error = null
            scope.launch {
                try {
                    val id = api.createChannel(session.accessToken, channelTitle.trim(), "")
                    showCreateChannel = false
                    val title = channelTitle.trim(); channelTitle = ""
                    refreshChannels()
                    onOpenChannel(ChannelDto(channelId = id, title = title, ownerId = session.userId, subscribed = true, owner = true))
                } catch (e: Throwable) { error = e.message } finally { busy = false }
            }
        }) { Text("Создать") }
    } else {
        Button(onClick = { showCreateChannel = true }, enabled = !busy) { Text("Создать канал") }
    }
    Spacer(Modifier.height(16.dp))

    // Аудио-чаты (постоянные голосовые комнаты)
    Row(
        modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Аудио-чаты", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Button(enabled = !busy, onClick = { scope.launch { refreshVoice() } }) { Text("⟳") }
    }
    Spacer(Modifier.height(8.dp))
    voiceRooms.forEach { v ->
        Button(
            onClick = { onOpenVoice(v) }, enabled = !busy,
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(vertical = 2.dp),
        ) { Text("🔊 ${v.title}") }
    }
    if (showCreateVoice) {
        OutlinedTextField(
            value = voiceTitle, onValueChange = { voiceTitle = it },
            label = { Text("Название аудио-чата") }, singleLine = true,
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Button(enabled = !busy && voiceTitle.isNotBlank(), onClick = {
            busy = true; error = null
            scope.launch {
                try {
                    val id = api.createVoiceRoom(session.accessToken, voiceTitle.trim())
                    val title = voiceTitle.trim(); voiceTitle = ""; showCreateVoice = false
                    refreshVoice()
                    onOpenVoice(VoiceRoomDto(roomId = id, title = title, ownerId = session.userId))
                } catch (e: Throwable) { error = e.message } finally { busy = false }
            }
        }) { Text("Создать") }
    } else {
        Button(onClick = { showCreateVoice = true }, enabled = !busy) { Text("Создать аудио-чат") }
    }
    Spacer(Modifier.height(16.dp))

    OutlinedTextField(
        value = peerPhone, onValueChange = { peerPhone = it },
        label = { Text("Новый чат: телефон собеседника") }, singleLine = true,
        modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
    )
    Spacer(Modifier.height(16.dp))
    if (busy) {
        CircularProgressIndicator()
    } else {
        Button(onClick = {
            busy = true; error = null
            scope.launch {
                try {
                    // Нормализуем номер к E.164 (8XXX / +7XXX / с пробобелами → +7XXX), иначе сервер отдаёт 400
                    val phone = normalizePhone(peerPhone) ?: peerPhone.trim()
                    AppDiagnostics.add("действие: новый чат, поиск $phone")
                    val peer = TimaApi(session.serverUrl).lookupUser(session.accessToken, phone)
                    if (peer == null) {
                        error = "Пользователь не найден — он ещё не вошёл в TIMA (проверьте номер)"
                    } else if (client != null) {
                        val entry = ChatEntry(title = phone, peerUserId = peer, chatId = client.chatIdWith(peer))
                        onChatsChange(SessionCodec.rememberChat(entry))
                        onOpen(entry)
                    }
                } catch (e: Throwable) {
                    error = e.message ?: e.toString()
                } finally {
                    busy = false
                }
            }
        }) { Text("Открыть чат") }
    }
    if (contactsSupported() && client != null) {
        Spacer(Modifier.height(8.dp))
        Button(enabled = !busy, onClick = onOpenContacts) { Text("📇 Контакты") }
    }
    ErrorText(error)
    Spacer(Modifier.height(24.dp))
    Button(onClick = onLogout, enabled = !busy) { Text("Выйти") }
}

/**
 * Фоновая доставка: состояние, самопроверка и переключатель.
 *
 * Сервис может умереть молча — тогда входящие перестают приходить, а пользователь об
 * этом не узнает. Поэтому пока экран открыт, проверяем и поднимаем заново.
 */
@Composable
private fun BackgroundCard() {
    var enabled by remember { mutableStateOf(AppPrefs.backgroundEnabled) }
    var alive by remember { mutableStateOf(backgroundServiceRunning()) }
    var batteryOk by remember { mutableStateOf(true) }

    // Самопроверка, пока экран открыт: сервис убит системой — молча поднимаем обратно.
    // Раз в минуту: служба живёт часами, чаще дёргать незачем.
    LaunchedEffect(enabled) {
        while (true) {
            batteryOk = batteryOptimizationIgnored()
            alive = backgroundServiceRunning()
            if (enabled && !alive) {
                AppDiagnostics.add("сервис: не работал — поднимаю заново")
                startBackgroundService()
                delay(700)
                alive = backgroundServiceRunning()
            }
            delay(60_000)
        }
    }

    val problem = enabled && (!alive || !batteryOk)
    Surface(
        color = if (problem) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                when {
                    !enabled -> "🌙 Фоновый режим усыплён"
                    !alive -> "⚠️ Фоновая служба не работает"
                    !batteryOk -> "⚠️ Фоновый режим включён, но система может его усыпить"
                    else -> "✅ Фоновый режим работает"
                },
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    !enabled -> "Звонки и сообщения приходят, только пока приложение открыто."
                    !batteryOk -> "Отключите оптимизацию батареи, иначе входящие будут теряться."
                    else -> "Входящие приходят, даже когда приложение свёрнуто."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val next = !enabled
                    enabled = next
                    AppPrefs.backgroundEnabled = next
                    AppDiagnostics.add("действие: фоновый режим — ${if (next) "включён" else "усыплён"}")
                    if (next) startBackgroundService() else stopBackgroundService()
                    alive = backgroundServiceRunning()
                }) { Text(if (enabled) "🌙 Усыпить" else "▶️ Включить") }
                if (enabled && !batteryOk) {
                    Button(onClick = {
                        AppDiagnostics.add("действие: настройка батареи")
                        requestIgnoreBatteryOptimization()
                    }) { Text("Настроить батарею") }
                }
            }
        }
    }
}

/**
 * Контакты: те из телефонной книги, кто зарегистрирован в TIMA — каждому можно сразу
 * написать или позвонить. Незнакомец, написавший первым, сюда не попадает: контакты —
 * это своя записная книжка, а не список всех, кто тебя знает; он живёт в списке чатов.
 */
@Composable
private fun ContactsScreen(
    client: ChatClient,
    onOpenChat: (Contact) -> Unit,
    onCall: (Contact, String) -> Unit,
    onBack: () -> Unit,
) {
    var all by remember { mutableStateOf<List<Contact>>(emptyList()) }
    var busy by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(client) {
        AppDiagnostics.add("действие: открыть контакты")
        all = runCatching { client.phoneBook() }.getOrDefault(emptyList())
        AppDiagnostics.add("контакты: в TIMA найдено ${all.size}")
        busy = false
    }
    val shown = remember(all, query) {
        val q = query.trim()
        val digits = q.filter { it.isDigit() }
        all.filter { c ->
            q.isEmpty() || c.name.contains(q, ignoreCase = true) ||
                (digits.isNotEmpty() && c.phone.contains(digits))
        }.sortedBy { c -> c.name.ifBlank { c.phone }.lowercase() }
    }

    Column(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onBack) { Text("←") }
            Spacer(Modifier.width(8.dp))
            Text("Контакты", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            label = { Text("Найти: имя или номер") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        when {
            busy -> CircularProgressIndicator()
            all.isEmpty() -> Text("Никого из ваших контактов нет в TIMA — либо доступ к контактам не выдан.")
            shown.isEmpty() -> Text("Ничего не найдено")
            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(shown, key = { it.userId }) { c ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f).clickable { onOpenChat(c) }.padding(end = 8.dp),
                        ) {
                            Text(c.name.ifBlank { c.phone }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (c.name.isNotBlank()) {
                                Text(c.phone, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            }
                        }
                        Button(onClick = { onOpenChat(c) }, contentPadding = PaddingValues(10.dp)) { Text("💬") }
                        Spacer(Modifier.width(4.dp))
                        Button(onClick = { onCall(c, "audio") }, contentPadding = PaddingValues(10.dp)) { Text("📞") }
                        Spacer(Modifier.width(4.dp))
                        Button(onClick = { onCall(c, "video") }, contentPadding = PaddingValues(10.dp)) { Text("📹") }
                    }
                }
            }
        }
    }
}

/** Экран переписки: личный чат ([isGroup]=false, [targetId]=peerUserId) или группа ([targetId]=groupId). */
@Composable
private fun ChatScreen(
    client: ChatClient,
    title: String,
    targetId: String,
    isGroup: Boolean,
    onRead: (String) -> Unit,
    onBack: () -> Unit,
    onCall: (String) -> Unit = {}, // kind: audio|video; только личный чат
) {
    val chatId = remember(targetId) { if (isGroup) targetId else client.chatIdWith(targetId) }
    val messages = remember { mutableStateListOf<ChatMessage>() }
    val names = remember { mutableStateMapOf<String, String>() } // user_id → имя (группы)
    var draft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var replyingTo by remember { mutableStateOf<ChatMessage?>(null) }
    var peerTyping by remember { mutableStateOf(false) }
    var typingCooldown by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // Резолв авторов в группе: телефон придёт только для тех, с кем есть личная переписка,
    // остальные подписаны именем
    LaunchedEffect(messages.size) {
        if (isGroup) {
            val ids = messages.map { it.senderId }.distinct().filter { !names.containsKey(it) }
            if (ids.isNotEmpty()) {
                runCatching { client.resolvePeers(ids) }.getOrNull()
                    ?.forEach { (id, peer) -> names[id] = peer.label }
            }
        }
    }

    fun add(msg: ChatMessage) {
        if (msg.chatId == chatId && messages.none { it.messageId == msg.messageId }) {
            messages.add(msg)
            messages.sortBy { it.messageId }
        }
    }

    LaunchedEffect(chatId) {
        try {
            (if (isGroup) client.groupHistory(targetId) else client.history(targetId)).forEach(::add)
        } catch (e: Throwable) {
            error = e.message ?: e.toString()
        }
        onRead(chatId)
        client.messages.collect(::add) // WS общий — здесь только фильтр своего чата
    }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(0)
    }
    // ✓✓: отмечаем прочитанным до последнего сообщения собеседника (личные чаты)
    LaunchedEffect(messages.size) {
        if (!isGroup) {
            messages.filter { !it.mine }.maxOfOrNull { it.messageId }?.let { client.markRead(chatId, it) }
        }
    }
    // Квитанции от собеседника → отмечаем свои сообщения как прочитанные
    LaunchedEffect(chatId) {
        client.readReceipts.collect { r ->
            if (r.chatId == chatId) {
                for (i in messages.indices) {
                    val m = messages[i]
                    if (m.mine && !m.readByPeer && m.messageId <= r.messageId) messages[i] = m.copy(readByPeer = true)
                }
            }
        }
    }
    // «Печатает»: показываем, пока приходят события; гаснет через 4 с тишины
    LaunchedEffect(chatId) {
        client.typingEvents.collectLatest { ev ->
            if (ev.chatId == chatId) {
                peerTyping = true
                delay(4000)
                peerTyping = false
            }
        }
    }

    // safeDrawing + ime: не подлезать под статусбар и подниматься над клавиатурой (Android)
    Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onBack) { Text("←") }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (peerTyping && !isGroup) {
                    Text("печатает…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (!isGroup) { // звонки — в личных чатах
                Button(enabled = !busy, onClick = { onCall("audio") }) { Text("📞") }
                Spacer(Modifier.width(4.dp))
                Button(enabled = !busy, onClick = { onCall("video") }) { Text("📹") }
                Spacer(Modifier.width(4.dp))
            }
            // Восстановить историю: у своих устройств (авто) или собеседника/участников (согласие)
            Button(enabled = !busy, onClick = {
                busy = true; error = null
                scope.launch {
                    try {
                        val restored = if (isGroup) client.recoverGroupHistory(targetId)
                        else client.recoverChatHistory(targetId)
                        restored.forEach(::add)
                        if (restored.isEmpty()) error = "История не восстановлена — источник офлайн или недоступен"
                    } catch (e: Throwable) {
                        error = e.message ?: e.toString()
                    } finally {
                        busy = false
                    }
                }
            }) { Text("⟲") }
        }
        // reverseLayout: индекс 0 — низ; список сам держится за последнее сообщение
        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp),
        ) {
            items(messages.asReversed(), key = { it.messageId }) { msg ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = if (msg.mine) Arrangement.End else Arrangement.Start,
                ) {
                    Surface(
                        color = if (msg.mine) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = { if (!isGroup) replyingTo = msg }, // долгое нажатие — ответить
                        ),
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            if (isGroup && !msg.mine) {
                                Text(names[msg.senderId] ?: (msg.senderId.take(8) + "…"), style = MaterialTheme.typography.labelSmall)
                            }
                            if (msg.replyTo != 0L) {
                                val quoted = messages.firstOrNull { it.messageId == msg.replyTo }
                                Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.small) {
                                    Text(
                                        quoted?.let { (if (it.mine) "Вы: " else "↩ ") + it.preview() }?.take(70) ?: "↩ сообщение",
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp).widthIn(max = 240.dp),
                                    )
                                }
                                Spacer(Modifier.height(3.dp))
                            }
                            val m = msg.media
                            when {
                                m == null -> if (msg.text.isNotEmpty()) Text(msg.text)
                                m.isVoice() -> VoiceBubble(client, m)
                                m.isFile() -> FileBubble(client, m, msg.text) // имя файла в тексте
                                else -> {
                                    MediaImage(client, m)
                                    if (msg.text.isNotEmpty()) Text(msg.text)
                                }
                            }
                            if (msg.mine && !isGroup && msg.readByPeer) {
                                Text("✓✓", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
        ErrorText(error)
        if (recording) Text("● запись… нажмите ⏹ для отправки", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        replyingTo?.let { r ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(
                    "↩ " + (if (r.mine) "Вы: " else "") + r.preview(),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = { replyingTo = null }) { Text("✕") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(enabled = !busy && !recording, onClick = {
                busy = true; error = null
                scope.launch {
                    try {
                        val image = pickImage()
                        if (image != null) {
                            AppDiagnostics.add("действие: отправка фото (${image.bytes.size / 1024} КБ)")
                            val sent = if (isGroup) client.sendGroupImage(targetId, image.bytes, image.mime, draft.trim())
                            else client.sendImage(targetId, image.bytes, image.mime, draft.trim())
                            add(sent)
                            draft = ""
                        }
                    } catch (e: Throwable) {
                        error = e.message ?: e.toString()
                    } finally {
                        busy = false
                    }
                }
            }) { Text("📷") }
            Spacer(Modifier.width(8.dp))
            // Файл: выбор произвольного файла, шифрование и отправка (CK_FILE)
            Button(enabled = !busy && !recording, onClick = {
                busy = true; error = null
                scope.launch {
                    try {
                        val file = pickFile()
                        if (file != null) {
                            AppDiagnostics.add("действие: отправка файла ${file.name} (${file.bytes.size / 1024} КБ)")
                            val sent = if (isGroup) client.sendGroupFile(targetId, file.bytes, file.name, file.mime)
                            else client.sendFile(targetId, file.bytes, file.name, file.mime)
                            add(sent)
                        }
                    } catch (e: Throwable) {
                        error = e.message ?: e.toString()
                    } finally {
                        busy = false
                    }
                }
            }) { Text("📎") }
            Spacer(Modifier.width(8.dp))
            // Голосовое: тап — старт записи, повторный тап — стоп и отправка (только там, где есть микрофон)
            if (voiceRecordingSupported()) {
                Button(
                    enabled = !busy,
                    colors = if (recording) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    else ButtonDefaults.buttonColors(),
                    onClick = {
                        if (!recording) {
                            error = null
                            scope.launch {
                                if (startVoiceRecording()) recording = true
                                else error = "Нет доступа к микрофону"
                            }
                        } else {
                            recording = false
                            busy = true; error = null
                            scope.launch {
                                try {
                                    val rec = stopVoiceRecording()
                                    if (rec != null) {
                                        AppDiagnostics.add("действие: отправка голосового (${rec.durationMs / 1000}с, ${rec.bytes.size / 1024} КБ)")
                                        val sent = if (isGroup) client.sendGroupVoice(targetId, rec.bytes, rec.mime, rec.durationMs)
                                        else client.sendVoice(targetId, rec.bytes, rec.mime, rec.durationMs)
                                        add(sent)
                                    }
                                } catch (e: Throwable) {
                                    error = e.message ?: e.toString()
                                } finally {
                                    busy = false
                                }
                            }
                        }
                    },
                ) { Text(if (recording) "⏹" else "🎙") }
                Spacer(Modifier.width(8.dp))
            }
            OutlinedTextField(
                value = draft,
                onValueChange = {
                    draft = it
                    // «печатает» собеседнику — не чаще раза в 3 с
                    if (!isGroup && it.isNotBlank() && !typingCooldown) {
                        typingCooldown = true
                        scope.launch {
                            client.sendTyping(chatId)
                            delay(3000)
                            typingCooldown = false
                        }
                    }
                },
                label = { Text("Сообщение") },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(enabled = !busy && draft.isNotBlank(), onClick = {
                val text = draft.trim()
                busy = true; error = null
                AppDiagnostics.add("действие: отправка текста (${if (isGroup) "группа" else "чат"} ${targetId.take(8)}…)")
                scope.launch {
                    try {
                        add(if (isGroup) client.sendGroup(targetId, text) else client.send(targetId, text, replyingTo?.messageId ?: 0L))
                        draft = ""
                        replyingTo = null
                    } catch (e: Throwable) {
                        error = e.message ?: e.toString()
                    } finally {
                        busy = false
                    }
                }
            }) { Text("➤") }
        }
    }
}

private enum class CallDir { Outgoing, Incoming, Connected }

private data class CallUi(
    val callId: String,
    val peerLabel: String,
    val kind: String, // audio|video
    val direction: CallDir,
    val connection: CallConnection?,
)

/** Экран звонка: сигналинг + живое медиа LiveKit (Android). Видео на весь экран, аудио — карточка. */
@Composable
private fun CallOverlay(call: CallUi, onAccept: () -> Unit, onEnd: () -> Unit) {
    val engine = remember { CallEngine() }
    val mediaState by engine.state.collectAsState()
    val micOn by engine.micEnabled.collectAsState()
    val camOn by engine.cameraEnabled.collectAsState()
    val speakerOn by engine.speakerOn.collectAsState()
    val isVideo = call.kind == "video"
    // Есть данные комнаты → подключаемся: исходящий сразу, входящий после «Принять»
    val ringing = call.direction == CallDir.Incoming && call.connection == null

    LaunchedEffect(call.connection) {
        val conn = call.connection ?: return@LaunchedEffect
        val granted = ensureCallPermissions(isVideo)
        AppDiagnostics.add("звонок: разрешения (${if (isVideo) "микрофон+камера" else "микрофон"}) — ${if (granted) "выданы" else "ОТКАЗ"}")
        if (granted) {
            engine.connect(conn.url, conn.token, video = isVideo, publishMic = true)
            AppDiagnostics.add("звонок: медиа ${engine.state.value}")
        }
    }
    // Погасший экран = уход в фон = система глушит микрофон. Держим экран включённым,
    // пока идёт звонок (foreground-сервис страхует, если свернули окно вручную).
    DisposableEffect(Unit) { keepScreenOn(true); onDispose { keepScreenOn(false) } }
    // Гудки: рингтон на входящий, «ту-ту» на исходящий; смолкают, когда пошёл разговор
    val ringback = call.direction == CallDir.Outgoing && mediaState != CallMediaState.Connected
    LaunchedEffect(ringing, ringback) {
        if (ringing) startRinging(incoming = true)
        else if (ringback) startRinging(incoming = false)
        else stopRinging()
    }
    DisposableEffect(Unit) { onDispose { stopRinging(); engine.disconnect() } }

    val onVideo = isVideo && mediaState == CallMediaState.Connected
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceVariant) {
        Box(Modifier.fillMaxSize()) {
            if (onVideo) CallVideoView(engine, Modifier.fillMaxSize())
            Column(
                modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (!onVideo) {
                    Text(if (isVideo) "📹" else "📞", style = MaterialTheme.typography.displayMedium)
                    Spacer(Modifier.height(16.dp))
                    Text(call.peerLabel, style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        when {
                            ringing -> "Входящий ${if (isVideo) "видео" else "аудио"}звонок"
                            mediaState == CallMediaState.Connecting -> "Соединяем…"
                            mediaState == CallMediaState.Connected -> "В разговоре"
                            mediaState == CallMediaState.Failed -> "Медиа недоступно — проверьте разрешения микрофона/камеры"
                            call.direction == CallDir.Outgoing -> "Звоним…"
                            else -> "…"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.widthIn(max = 360.dp),
                    )
                    Spacer(Modifier.height(32.dp))
                }

                if (mediaState == CallMediaState.Connected) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { engine.setMic(!micOn) }) { Text(if (micOn) "🎤 вкл" else "🔇 выкл") }
                        // Громкий динамик vs разговорный (у уха) — без этой кнопки
                        // аудиозвонок «молчит», если держать телефон перед собой
                        Button(onClick = { engine.setSpeaker(!speakerOn) }) {
                            Text(if (speakerOn) "🔊 громко" else "📢 динамик")
                        }
                        if (isVideo) {
                            Button(onClick = { engine.setCamera(!camOn) }) { Text(if (camOn) "📹 вкл" else "📷 выкл") }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                }

                if (ringing) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Button(
                            onClick = onAccept,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        ) { Text("Принять") }
                        Button(
                            onClick = onEnd,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        ) { Text("Отклонить") }
                    }
                } else {
                    Button(
                        onClick = onEnd,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) { Text("Завершить") }
                }
            }
        }
    }
}

/** Экран аудио-чата с ролями: спикер говорит, слушатель поднимает руку, владелец выдаёт слово. */
@Composable
private fun VoiceRoomScreen(state: Screen.VoiceRoom, client: ChatClient?, onBack: () -> Unit) {
    val api = remember(state.roomId) { TimaApi(state.session.serverUrl) }
    var joined by remember { mutableStateOf(false) }
    var role by remember { mutableStateOf("listener") }
    var isOwner by remember { mutableStateOf(false) }
    var micOn by remember { mutableStateOf(true) }
    var handRaised by remember { mutableStateOf(false) }
    val hands = remember { mutableStateListOf<String>() } // user_id поднявших руку (владельцу)
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val engine = remember { CallEngine() }
    val mediaState by engine.state.collectAsState()
    val speakerOn by engine.speakerOn.collectAsState()

    suspend fun join() {
        val r = api.joinVoiceRoom(state.session.accessToken, state.roomId)
        role = r.role; isOwner = r.isOwner
        if (!joined) {
            joined = true
            // Слушатель подключается, но микрофон не публикует; спикер/владелец — публикует
            if (ensureCallPermissions(false)) {
                engine.connect(r.url, r.token, video = false, publishMic = r.role == "speaker")
                // Аудио-чат слушают как конференцию, а не прижав телефон к уху:
                // разговорный динамик (умолчание LiveKit) звучит как тишина
                engine.setSpeaker(true)
            }
        } else {
            // Роль изменилась (выдали/забрали слово) — переключаем публикацию микрофона без переподключения
            engine.setMic(r.role == "speaker")
        }
        micOn = r.role == "speaker"
    }
    // Экран не гасим: в фоне система отбирает микрофон и аудио-чат замолкает
    DisposableEffect(Unit) {
        keepScreenOn(true)
        onDispose { keepScreenOn(false); engine.disconnect() }
    }
    // События: поднятая рука (владельцу), выдача/отзыв слова (перезаходим за новой ролью)
    LaunchedEffect(client, state.roomId) {
        client?.voiceEvents?.collect { ev ->
            if (ev.roomId != state.roomId) return@collect
            when (ev.type) {
                "voice.hand" -> if (ev.userId !in hands) hands.add(ev.userId)
                "voice.granted", "voice.revoked" -> { handRaised = false; runCatching { join() } }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onBack) { Text("←") }
            Spacer(Modifier.width(12.dp))
            Text("🔊 ${state.title}", style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.weight(1f))
        Text(if (role == "speaker") "🎙️" else "🎧", style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(12.dp))
        Text(
            when {
                !joined -> "Аудио-чат"
                role == "speaker" -> if (isOwner) "Вы владелец (говорите)" else "Вы говорите"
                else -> "Вы слушаете"
            },
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(6.dp))
        if (joined) {
            Text(
                when (mediaState) {
                    CallMediaState.Connecting -> "Подключаем звук…"
                    CallMediaState.Connected -> "Звук подключён"
                    CallMediaState.Failed -> "Звук недоступен — проверьте разрешение микрофона"
                    CallMediaState.Idle -> "Звук отключён"
                },
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.widthIn(max = 360.dp),
            )
        }
        ErrorText(error)
        Spacer(Modifier.height(20.dp))

        if (!joined) {
            Button(enabled = !busy, onClick = {
                busy = true; error = null
                scope.launch {
                    try { join() } catch (e: Throwable) { error = e.message } finally { busy = false }
                }
            }) { Text("Присоединиться") }
        } else {
            // Владельцу — поднятые руки с кнопкой «Выдать слово»
            if (isOwner && hands.isNotEmpty()) {
                Text("Просят слово:", style = MaterialTheme.typography.titleSmall)
                hands.toList().forEach { uid ->
                    Row(
                        modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("✋ ${uid.take(8)}…", modifier = Modifier.weight(1f))
                        Button(enabled = !busy, onClick = {
                            scope.launch {
                                runCatching { api.grantSpeaker(state.session.accessToken, state.roomId, uid) }
                                hands.remove(uid)
                            }
                        }) { Text("Выдать слово") }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { engine.setSpeaker(!speakerOn) }) {
                    Text(if (speakerOn) "🔊 громко" else "📢 динамик")
                }
                if (role == "speaker") {
                    Button(onClick = { micOn = !micOn; engine.setMic(micOn) }) { Text(if (micOn) "🎤 вкл" else "🔇 выкл") }
                } else {
                    Button(enabled = !handRaised, onClick = {
                        scope.launch { runCatching { api.raiseHand(state.session.accessToken, state.roomId); handRaised = true } }
                    }) { Text(if (handRaised) "✋ рука поднята" else "✋ Поднять руку") }
                }
                Button(
                    onClick = { engine.disconnect(); joined = false; hands.clear() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Выйти") }
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun ChannelScreen(state: Screen.ChannelView, onBack: () -> Unit) {
    val api = remember(state.channelId) { TimaApi(state.session.serverUrl) }
    val posts = remember { mutableStateListOf<ChannelPostDto>() }
    var draft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        try {
            val fresh = api.channelPosts(state.session.accessToken, state.channelId)
            posts.clear(); posts.addAll(fresh.sortedBy { it.postId })
        } catch (e: Throwable) {
            error = e.message ?: e.toString()
        }
    }
    LaunchedEffect(state.channelId) { reload() }

    Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onBack) { Text("←") }
            Spacer(Modifier.width(12.dp))
            Text((if (state.owner) "📢 " else "📣 ") + state.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Button(enabled = !busy, onClick = { scope.launch { reload() } }) { Text("⟳") }
        }
        LazyColumn(reverseLayout = true, modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp)) {
            items(posts.asReversed(), key = { it.postId }) { post ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                ) {
                    Text(post.text, modifier = Modifier.padding(12.dp))
                }
            }
        }
        ErrorText(error)
        if (state.owner) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft, onValueChange = { draft = it },
                    label = { Text("Новый пост") }, modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(enabled = !busy && draft.isNotBlank(), onClick = {
                    busy = true; error = null
                    scope.launch {
                        try {
                            api.postToChannel(state.session.accessToken, state.channelId, draft.trim())
                            draft = ""; reload()
                        } catch (e: Throwable) { error = e.message } finally { busy = false }
                    }
                }) { Text("➤") }
            }
        } else {
            Text("Читаете канал. Публикует владелец.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun MediaImage(client: ChatClient, media: MediaAttachment) {
    var bitmap by remember(media.mediaId) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(media.mediaId) { mutableStateOf(false) }
    LaunchedEffect(media.mediaId) {
        try {
            bitmap = decodeImage(client.loadMedia(media))
            failed = bitmap == null
        } catch (_: Throwable) {
            failed = true
        }
    }
    when {
        bitmap != null -> Image(
            bitmap = bitmap!!,
            contentDescription = null,
            modifier = Modifier.widthIn(max = 260.dp).heightIn(max = 320.dp).padding(bottom = 4.dp),
        )
        failed -> Text("⚠ фото не загрузилось", style = MaterialTheme.typography.bodySmall)
        else -> Text("📷 загрузка…", style = MaterialTheme.typography.bodySmall)
    }
}

/** Голосовое сообщение: ▶/⏹ + длительность; байты грузятся и расшифровываются по нажатию. */
@Composable
private fun VoiceBubble(client: ChatClient, media: MediaAttachment) {
    var playing by remember(media.mediaId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val secs = (media.durationMs / 1000).coerceAtLeast(1)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 2.dp)) {
        Button(onClick = {
            if (playing) {
                stopVoice()
                playing = false
            } else {
                scope.launch {
                    playing = true
                    try { playVoice(client.loadMedia(media), media.mime) } catch (_: Throwable) {}
                    playing = false
                }
            }
        }) { Text(if (playing) "⏹" else "▶") }
        Spacer(Modifier.width(8.dp))
        Text("🎤 ${secs}с")
    }
}

/** Файл-вложение: 📎 имя + размер + кнопка открыть (скачивает и расшифровывает по нажатию). */
@Composable
private fun FileBubble(client: ChatClient, media: MediaAttachment, name: String) {
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val kb = (media.sizeBytes / 1024).coerceAtLeast(1)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 2.dp).widthIn(max = 280.dp)) {
        Text("📎", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(name.ifBlank { "файл" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$kb КБ", style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.width(6.dp))
        Button(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                try { openFile(client.loadMedia(media), name.ifBlank { "file" }, media.mime) }
                catch (_: Throwable) {}
                finally { busy = false }
            }
        }) { Text("⬇") }
    }
}

@Composable
private fun ErrorText(error: String?) {
    if (error != null) {
        Spacer(Modifier.height(12.dp))
        Text(error, color = MaterialTheme.colorScheme.error)
    }
}
