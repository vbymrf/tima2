package io.tima.app.diag

/**
 * Кольцевой журнал диагностики (последние события/ошибки) для кнопки «Отправить логи».
 * Пишется из сетевого слоя (TimaApi/WS); читается на экране диагностики.
 */
object AppDiagnostics {
    private const val MAX = 200
    private val lines = ArrayDeque<String>()

    var serverUrl: String = ""
    var appVersion: Int = 0
    var platform: String = ""

    @Synchronized
    fun add(msg: String) {
        lines.addLast(msg)
        while (lines.size > MAX) lines.removeFirst()
    }

    @Synchronized
    fun dump(): String = buildString {
        appendLine("TIMA — диагностика")
        appendLine("версия сборки: $appVersion   платформа: $platform")
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
