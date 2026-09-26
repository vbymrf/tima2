package io.tima.core.call

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg

/**
 * ПК: доступ к микрофону и камере даёт **Windows**, а не приложение (ПЛАН-ЗВОНКОВ-ПК, ПК3).
 *
 * Спросить, как на Android, нечем: у Windows нет диалога «разрешить TIMA микрофон» для
 * классических программ. Есть переключатели в «Параметры → Конфиденциальность», и когда
 * они выключены, микрофон **молча отдаёт тишину** — звонок соединится, а слышно не будет.
 * Поэтому читаем переключатели сами и говорим заранее.
 *
 * Переключателей три на каждое устройство (Windows 10 1903+), и запрещает любой:
 *
 * | Ключ | Что это в «Параметрах» |
 * |---|---|
 * | `HKLM\…\ConsentStore\microphone` | «Разрешить доступ к микрофону на этом устройстве» |
 * | `HKCU\…\ConsentStore\microphone` | «Разрешить приложениям доступ к микрофону» |
 * | `HKCU\…\ConsentStore\microphone\NonPackaged` | «Разрешить классическим приложениям…» — это мы |
 *
 * Ключа нет — значит, запрета нет: на старых сборках Windows переключателей не было.
 */
actual fun askCallAccess(video: Boolean, onResult: (Boolean) -> Unit) {
    val state = callAccessState()
    onResult(state.microphone != false && (!video || state.camera != false))
}

/**
 * Открыть в «Параметрах» ту страницу, где запрет: сначала микрофон — без него звонка нет,
 * камера важна только видеозвонку.
 */
actual fun openCallSettings() {
    if (!windows) return
    val page = if (allowed(MICROPHONE) == false) "ms-settings:privacy-microphone" else "ms-settings:privacy-webcam"
    runCatching { ProcessBuilder("cmd", "/c", "start", "", page).start() }
}

actual fun callAccessState(): CallAccessState =
    if (!windows) CallAccessState(null, null) else CallAccessState(allowed(MICROPHONE), allowed(WEBCAM))

private val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")

private const val STORE = "Software\\Microsoft\\Windows\\CurrentVersion\\CapabilityAccessManager\\ConsentStore\\"
private const val MICROPHONE = "microphone"
private const val WEBCAM = "webcam"

/** `false` — хоть один переключатель запрещает; иначе `true`. */
private fun allowed(device: String): Boolean {
    val places = listOf(
        WinReg.HKEY_LOCAL_MACHINE to STORE + device,
        WinReg.HKEY_CURRENT_USER to STORE + device,
        WinReg.HKEY_CURRENT_USER to STORE + device + "\\NonPackaged",
    )
    return places.none { (root, key) -> value(root, key) == "Deny" }
}

private fun value(root: WinReg.HKEY, key: String): String? = runCatching {
    if (Advapi32Util.registryValueExists(root, key, "Value")) Advapi32Util.registryGetStringValue(root, key, "Value") else null
}.getOrNull()
