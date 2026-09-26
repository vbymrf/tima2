package io.tima.core.call

import kotlinx.coroutines.flow.StateFlow

/**
 * Микрофон, колонки и камера — **выбор и проверка** (заказчик 2026-09-26, ПЛАН-ЗВОНКОВ-ПК).
 *
 * Есть там, где устройств бывает несколько и система их за человека не выбирает, — на ПК.
 * На телефоне микрофон один, камеры переключаются в разговоре, а маршрут звука решает
 * система: там этого интерфейса нет, и пункта настроек тоже.
 *
 * Проверка — **без звонка**: человек видит полосу уровня микрофона, себя в камере и
 * слышит пробный звук. Узнавать, что тебя не слышно, от собеседника — поздно.
 */
interface CallDevices {

    /** Устройство: `id` — как его знает система (стабилен при переподключении), `name` — для человека. */
    data class Device(val id: String, val name: String)

    fun microphones(): List<Device>
    fun speakers(): List<Device>
    fun cameras(): List<Device>

    /** С чем звонить. Читается при каждом подключении — сменённое действует со следующего звонка. */
    var setup: CallSetup

    /** Уровень микрофона 0…1, пока идёт проверка. */
    val micLevel: StateFlow<Float>

    /** Картинка с камеры, пока идёт проверка. `null` — камеры нет или она не открылась. */
    val preview: StateFlow<VideoHandle?>

    /** Что не получилось при проверке — словами для экрана. `null` — всё в порядке. */
    val checkTrouble: StateFlow<String?>

    /** Начать проверку с [setup]: открыть микрофон и камеру. Повторный вызов — перезапуск. */
    suspend fun startCheck()

    /** Закрыть устройства: пока они открыты, Windows показывает, что микрофон и камера заняты. */
    suspend fun stopCheck()

    /** Громкость микрофона в системе, 0…1. `null` — не узнали. */
    fun micVolume(): Float?

    /** Поставить громкость микрофона в системе. Это та же ручка, что в «Параметрах» Windows. */
    fun setMicVolume(value: Float)

    /** Пробный звук в выбранные колонки. */
    fun playTest()

    /**
     * Слушать себя: во время проверки микрофон идёт прямо в выбранные колонки. Лучше в
     * наушниках — из колонок звук вернётся в микрофон свистом.
     */
    fun listen(on: Boolean)
}

/**
 * Выбор человека. `null` у устройства — «как в системе» (устройство по умолчанию).
 *
 * Обработка звука включена по умолчанию: эхо и шум без неё — первое, на что жалуются. Ручки
 * есть для случаев, когда обработка мешает: гарнитура со своим шумодавом, музыка.
 */
data class CallSetup(
    val microphone: String? = null,
    val speaker: String? = null,
    val camera: String? = null,
    val echoCancellation: Boolean = true,
    val noiseSuppression: Boolean = true,
    val autoGain: Boolean = true,
)

/** Ключи выбора в настройках устройства — не синхронизируются: у каждого ПК свои устройства. */
object CallSetupKeys {
    const val MICROPHONE = "call.microphone"
    const val SPEAKER = "call.speaker"
    const val CAMERA = "call.camera"
    const val ECHO = "call.echo"
    const val NOISE = "call.noise"
    const val GAIN = "call.gain"

    fun read(all: Map<String, String>): CallSetup = CallSetup(
        microphone = all[MICROPHONE]?.takeIf { it.isNotBlank() },
        speaker = all[SPEAKER]?.takeIf { it.isNotBlank() },
        camera = all[CAMERA]?.takeIf { it.isNotBlank() },
        echoCancellation = all[ECHO] != "0",
        noiseSuppression = all[NOISE] != "0",
        autoGain = all[GAIN] != "0",
    )
}
