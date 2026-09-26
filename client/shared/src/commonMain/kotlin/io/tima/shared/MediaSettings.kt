package io.tima.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.tima.core.call.CallDevices
import io.tima.core.call.CallSetupKeys
import io.tima.domain.chat.Settings
import io.tima.feature.call.CallVideo
import io.tima.feature.shell.MediaChoice
import io.tima.feature.shell.MediaDevice
import io.tima.feature.shell.MediaScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * «Микрофон и камера» — экран плюс проверка (заказчик 2026-09-26, ПЛАН-ЗВОНКОВ-ПК).
 *
 * Проверка идёт, **пока экран открыт**: ушёл — устройства закрыты, и Windows перестаёт
 * показывать, что микрофон и камера заняты. Сменил микрофон или камеру — проверка
 * перезапускается с новыми, чтобы полоса и картинка были от выбранного.
 *
 * Выбор ложится в настройки устройства и не синхронизируется: устройства у каждого ПК свои.
 * В движок он попадает не отсюда, а из [followCallSetup] — выбор действует и тогда, когда
 * экран ни разу не открывали.
 */
@Composable
internal fun MediaSettings(devices: CallDevices, settings: Settings) {
    val scope = rememberCoroutineScope()
    var all by remember(settings) { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(settings) { settings.all().collect { all = it } }
    val setup = CallSetupKeys.read(all)

    // Перечень — из нативных библиотек: не на потоке экрана.
    var microphones by remember { mutableStateOf(emptyList<MediaDevice>()) }
    var speakers by remember { mutableStateOf(emptyList<MediaDevice>()) }
    var cameras by remember { mutableStateOf(emptyList<MediaDevice>()) }
    var volume by remember { mutableStateOf<Float?>(null) }
    // Слушать себя — только пока открыт экран: вернулся — снова тихо, и засвистевшие
    // колонки не остаются свистеть за спиной.
    var listen by remember { mutableStateOf(false) }
    LaunchedEffect(devices) {
        withContext(Dispatchers.IO) {
            microphones = devices.microphones().map { MediaDevice(it.id, it.name) }
            speakers = devices.speakers().map { MediaDevice(it.id, it.name) }
            cameras = devices.cameras().map { MediaDevice(it.id, it.name) }
        }
    }

    // Проверка — с выбранными микрофоном и камерой; смена любого из них перезапускает её.
    LaunchedEffect(setup.microphone, setup.camera, setup.echoCancellation, setup.noiseSuppression, setup.autoGain) {
        devices.setup = setup
        // Громкость — первой: она читается мгновенно, а проверка открывает устройства.
        volume = withContext(Dispatchers.IO) { devices.micVolume() }
        devices.startCheck()
    }
    DisposableEffect(devices) {
        onDispose {
            devices.listen(false)
            scope.launch { devices.stopCheck() }
        }
    }

    val level by devices.micLevel.collectAsState()
    val preview by devices.preview.collectAsState()
    val trouble by devices.checkTrouble.collectAsState()

    MediaScreen(
        microphones = microphones,
        speakers = speakers,
        cameras = cameras,
        choice = MediaChoice(
            microphone = setup.microphone,
            speaker = setup.speaker,
            camera = setup.camera,
            echo = setup.echoCancellation,
            noise = setup.noiseSuppression,
            gain = setup.autoGain,
        ),
        onChoice = { picked ->
            scope.launch {
                settings.put(CallSetupKeys.MICROPHONE, picked.microphone.orEmpty())
                settings.put(CallSetupKeys.SPEAKER, picked.speaker.orEmpty())
                settings.put(CallSetupKeys.CAMERA, picked.camera.orEmpty())
                settings.put(CallSetupKeys.ECHO, if (picked.echo) "1" else "0")
                settings.put(CallSetupKeys.NOISE, if (picked.noise) "1" else "0")
                settings.put(CallSetupKeys.GAIN, if (picked.gain) "1" else "0")
            }
        },
        level = level,
        volume = volume,
        onVolume = { value ->
            volume = value
            scope.launch(Dispatchers.IO) { devices.setMicVolume(value) }
        },
        onTestSound = { devices.setup = setup; devices.playTest() },
        listen = listen,
        onListen = { on ->
            listen = on
            devices.listen(on)
        },
        preview = preview?.let { handle -> { modifier -> CallVideo(handle, modifier) } },
        trouble = trouble,
    )
}

/**
 * Держать выбор человека в движке — с запуска и при каждой смене настроек. Иначе выбранный
 * микрофон действовал бы только после захода в «Микрофон и камера».
 */
@Composable
internal fun followCallSetup(devices: CallDevices?, settings: Settings) {
    if (devices == null) return
    LaunchedEffect(devices, settings) {
        settings.all().collect { devices.setup = CallSetupKeys.read(it) }
    }
}
