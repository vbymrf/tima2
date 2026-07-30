package io.tima.app.diag

/** Текущее время ЧЧ:ММ:СС для записей журнала (платформенно). */
expect fun diagNow(): String

/**
 * Модель устройства для шапки журнала («Samsung SM-A536E», «Windows 10, ПК»).
 *
 * Логи двух телефонов выглядят одинаково, и без модели их легко перепутать —
 * особенно когда причина поломки на них разная.
 */
expect fun deviceModel(): String

/**
 * Кольцевой журнал диагностики (последние события/ошибки/действия) для кнопки «Отправить логи».
 * Пишется из сетевого слоя (TimaApi/WS) и из UI (действия пользователя).
 */
object AppDiagnostics {
    private const val MAX = 300
    private val lines = ArrayDeque<String>()

    var serverUrl: String = ""
    var appVersion: Int = 0
    var appVersionName: String = ""
    var platform: String = ""

    /** Идентификатор устройства из сессии — по нему видно, чьи это логи на сервере. */
    var deviceId: String = ""

    /** Аккаунт, под которым работает приложение. */
    var userId: String = ""

    @Synchronized
    fun add(msg: String) {
        lines.addLast("${diagNow()}  $msg")
        while (lines.size > MAX) lines.removeFirst()
    }

    @Synchronized
    fun dump(): String = buildString {
        appendLine("TIMA — диагностика")
        val ver = if (appVersionName.isNotEmpty()) "$appVersionName ($appVersion)" else "$appVersion"
        appendLine("версия: $ver   платформа: $platform")
        appendLine("устройство: ${deviceModel()}")
        if (deviceId.isNotEmpty()) appendLine("device_id: $deviceId")
        if (userId.isNotEmpty()) appendLine("user_id: $userId")
        appendLine("сервер: $serverUrl")
        appendLine("событий: ${lines.size}")
        appendLine("----------------------------------------")
        lines.forEach { appendLine(it) }
    }

    @Synchronized
    fun recent(n: Int = 20): List<String> = lines.toList().takeLast(n)

    @Synchronized
    fun clear() = lines.clear()
}
