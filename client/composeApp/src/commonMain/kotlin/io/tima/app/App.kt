@file:OptIn(ExperimentalEncodingApi::class)

package io.tima.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.kodium.Kodium
import io.tima.app.api.TimaApi
import io.tima.app.chat.ChatClient
import io.tima.app.chat.ChatMessage
import io.tima.app.chat.createChatClient
import io.tima.app.session.ChatEntry
import io.tima.app.session.Session
import io.tima.app.session.SessionCodec
import io.tima.app.session.defaultServerUrl
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.launch

private sealed interface Screen {
    data object Phone : Screen
    data class Code(val serverUrl: String, val phone: String, val requestId: String, val devCode: String?) : Screen
    data class Home(val session: Session) : Screen
    data class Chat(val session: Session, val peerUserId: String, val peerPhone: String) : Screen
}

private val b64url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

@Composable
fun App() {
    var screen by remember { mutableStateOf(SessionCodec.load()?.let { Screen.Home(it) } ?: Screen.Phone) }
    var chats by remember { mutableStateOf(SessionCodec.loadChats()) }

    val session = when (val s = screen) {
        is Screen.Home -> s.session
        is Screen.Chat -> s.session
        else -> null
    }
    // Один ChatClient (одно WS) на сессию — живёт, пока пользователь вошёл
    val client = remember(session?.deviceId) { session?.let { createChatClient(it) } }
    DisposableEffect(client) {
        onDispose { client?.close() }
    }
    LaunchedEffect(client) {
        val c = client ?: return@LaunchedEffect
        c.start()
        c.messages.collect { msg ->
            val known = SessionCodec.loadChats().any { it.chatId == msg.chatId }
            // Своё эхо с неизвестным чатом не создаёт запись: peer из senderId не извлечь
            if (!known && msg.mine) return@collect
            val isOpen = msg.mine ||
                (screen as? Screen.Chat)?.let { c.chatIdWith(it.peerUserId) == msg.chatId } == true
            chats = SessionCodec.noteMessage(msg.chatId, msg.senderId, msg.text, msg.createdAtMs, isOpen)
        }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            when (val s = screen) {
                // Чат сам управляет раскладкой (LazyColumn несовместим с внешним verticalScroll)
                is Screen.Chat -> ChatScreen(
                    s,
                    client = client ?: return@Surface,
                    onRead = { chatId -> chats = SessionCodec.markRead(chatId) },
                    onBack = { screen = Screen.Home(s.session) },
                )
                else -> Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    when (s) {
                        is Screen.Phone -> PhoneScreen(onCode = { screen = it })
                        is Screen.Code -> CodeScreen(s, onHome = { screen = it }, onBack = { screen = Screen.Phone })
                        is Screen.Home -> HomeScreen(
                            s.session,
                            chats = chats,
                            client = client,
                            onOpen = { entry ->
                                chats = SessionCodec.markRead(entry.chatId)
                                screen = Screen.Chat(s.session, entry.peerUserId, entry.title)
                            },
                            onChatsChange = { chats = it },
                            onLogout = {
                                SessionCodec.clear()
                                chats = emptyList()
                                screen = Screen.Phone
                            },
                        )
                        is Screen.Chat -> Unit // обработан выше
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
                    val resp = TimaApi(serverUrl).smsRequest(phone.trim())
                    onCode(Screen.Code(serverUrl, phone.trim(), resp.requestId, resp.devCode))
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
private fun CodeScreen(state: Screen.Code, onHome: (Screen.Home) -> Unit, onBack: () -> Unit) {
    var code by remember { mutableStateOf(state.devCode ?: "") }
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
    Spacer(Modifier.height(16.dp))
    if (busy) {
        CircularProgressIndicator()
    } else {
        Button(onClick = {
            busy = true; error = null
            scope.launch {
                try {
                    val api = TimaApi(state.serverUrl)
                    val token = api.smsVerify(state.requestId, code.trim()).registrationToken
                    // Ключи устройства: один seed → X25519 (конверты) + Ed25519 (подпись)
                    val deviceKey = Kodium.generateKeyPair()
                    val pub = deviceKey.getPublicKey()
                    val reg = api.register(
                        registrationToken = token,
                        encryptionPub = b64url.encode(pub.encryptionKey),
                        signingPub = b64url.encode(pub.signingKey),
                    )
                    val session = Session(
                        serverUrl = state.serverUrl,
                        phone = state.phone,
                        userId = reg.userId,
                        deviceId = reg.deviceId,
                        accessToken = reg.accessToken,
                        deviceSecretB64 = b64url.encode(deviceKey.secretKey),
                    )
                    SessionCodec.save(session)
                    onHome(Screen.Home(session))
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
private fun HomeScreen(
    session: Session,
    chats: List<ChatEntry>,
    client: ChatClient?,
    onOpen: (ChatEntry) -> Unit,
    onChatsChange: (List<ChatEntry>) -> Unit,
    onLogout: () -> Unit,
) {
    var peerPhone by remember { mutableStateOf("+7") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Text("TIMA", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(8.dp))
    if (session.phone.isNotEmpty()) {
        Text(session.phone, style = MaterialTheme.typography.titleMedium)
    }
    Text("Вы вошли: ${session.userId.take(8)}…", style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(16.dp))

    if (chats.isNotEmpty()) {
        Text("Чаты", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        chats.forEach { entry ->
            Button(
                onClick = { onOpen(entry) },
                enabled = !busy,
                modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(vertical = 2.dp),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(entry.title, modifier = Modifier.weight(1f))
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
                    val phone = peerPhone.trim()
                    val peer = TimaApi(session.serverUrl).lookupUser(session.accessToken, phone)
                    if (peer == null) {
                        error = "Пользователь не найден — он ещё не вошёл в TIMA"
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
    ErrorText(error)
    Spacer(Modifier.height(24.dp))
    Button(onClick = onLogout, enabled = !busy) { Text("Выйти") }
}

@Composable
private fun ChatScreen(state: Screen.Chat, client: ChatClient, onRead: (String) -> Unit, onBack: () -> Unit) {
    val chatId = remember(state.peerUserId) { client.chatIdWith(state.peerUserId) }
    val messages = remember { mutableStateListOf<ChatMessage>() }
    var draft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    fun add(msg: ChatMessage) {
        if (msg.chatId == chatId && messages.none { it.messageId == msg.messageId }) {
            messages.add(msg)
            messages.sortBy { it.messageId }
        }
    }

    LaunchedEffect(chatId) {
        try {
            client.history(state.peerUserId).forEach(::add)
        } catch (e: Throwable) {
            error = e.message ?: e.toString()
        }
        onRead(chatId)
        client.messages.collect(::add) // WS общий — здесь только фильтр своего чата
    }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(0)
    }

    // safeDrawing + ime: не подлезать под статусбар и подниматься над клавиатурой (Android)
    Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onBack) { Text("←") }
            Spacer(Modifier.width(12.dp))
            Text("Чат с ${state.peerPhone}", style = MaterialTheme.typography.titleLarge)
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
                    ) {
                        Text(msg.text, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                }
            }
        }
        ErrorText(error)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft, onValueChange = { draft = it },
                label = { Text("Сообщение") },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(enabled = !busy && draft.isNotBlank(), onClick = {
                val text = draft.trim()
                busy = true; error = null
                scope.launch {
                    try {
                        add(client.send(state.peerUserId, text))
                        draft = ""
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

@Composable
private fun ErrorText(error: String?) {
    if (error != null) {
        Spacer(Modifier.height(12.dp))
        Text(error, color = MaterialTheme.colorScheme.error)
    }
}
