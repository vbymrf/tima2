@file:OptIn(ExperimentalEncodingApi::class)

package io.tima.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.kodium.Kodium
import io.tima.app.api.TimaApi
import io.tima.app.session.Session
import io.tima.app.session.SessionCodec
import io.tima.app.session.defaultServerUrl
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.launch

private sealed interface Screen {
    data object Phone : Screen
    data class Code(val serverUrl: String, val requestId: String, val devCode: String?) : Screen
    data class Home(val session: Session) : Screen
}

private val b64url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

@Composable
fun App() {
    var screen by remember { mutableStateOf(SessionCodec.load()?.let { Screen.Home(it) } ?: Screen.Phone) }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                when (val s = screen) {
                    is Screen.Phone -> PhoneScreen(onCode = { screen = it })
                    is Screen.Code -> CodeScreen(s, onHome = { screen = it }, onBack = { screen = Screen.Phone })
                    is Screen.Home -> HomeScreen(s.session, onLogout = {
                        SessionCodec.clear()
                        screen = Screen.Phone
                    })
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
                    onCode(Screen.Code(serverUrl, resp.requestId, resp.devCode))
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
private fun HomeScreen(session: Session, onLogout: () -> Unit) {
    Text("Вы вошли в TIMA", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(16.dp))
    Text("Сервер: ${session.serverUrl}")
    Text("Пользователь: ${session.userId}")
    Text("Устройство: ${session.deviceId}")
    Spacer(Modifier.height(8.dp))
    Text("Ключи устройства созданы, устройство зарегистрировано.", style = MaterialTheme.typography.bodyMedium)
    Text("Чаты — следующая итерация.", style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(16.dp))
    Button(onClick = onLogout) { Text("Выйти") }
}

@Composable
private fun ErrorText(error: String?) {
    if (error != null) {
        Spacer(Modifier.height(12.dp))
        Text(error, color = MaterialTheme.colorScheme.error)
    }
}
