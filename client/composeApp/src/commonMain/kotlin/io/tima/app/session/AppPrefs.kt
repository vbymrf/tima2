package io.tima.app.session

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class Prefs(
    // Фоновая доставка входящих. По умолчанию включена: без неё звонок не дойдёт
    // до свёрнутого приложения.
    @SerialName("background") val background: Boolean = true,
)

/**
 * Настройки приложения. Живут отдельно от сессии: выход из аккаунта их не стирает.
 */
object AppPrefs {
    private val json = Json { ignoreUnknownKeys = true }
    private const val FILE = "prefs.json"

    private fun load(): Prefs = SessionStorage.read(FILE)
        ?.let { runCatching { json.decodeFromString<Prefs>(it) }.getOrNull() }
        ?: Prefs()

    /** Держать ли соединение, когда приложение свёрнуто. */
    var backgroundEnabled: Boolean
        get() = load().background
        set(value) = SessionStorage.write(FILE, json.encodeToString(Prefs.serializer(), Prefs(value)))
}
