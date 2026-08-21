package io.tima.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Как обращаться к серверу: конфигурация, а не константы в коде.
 *
 * **Требование этапа К3 звучит так: «смена прокси — конфигом, без правки кода».**
 * Отсюда и форма: всё, что определяет адрес, лежит в [RouteConfig], а код умеет
 * только собрать из него адреса. Ни одного имени хоста в исходниках нет.
 */
@Serializable
data class RouteConfig(
    /**
     * Имя, введённое человеком. Может быть кириллическим (`пацак.рф`) — перевод в
     * punycode делает [ServerRoute], а не пользователь.
     */
    val host: String,
    /**
     * Поддомен, на котором живёт API. У стенда это `api` — **корневой домен отдаёт
     * 404**, и это измерено, а не предположено (`arhiv/СТЕНД.md`). `null` — API
     * прямо на [host].
     */
    @SerialName("api_subdomain") val apiSubdomain: String? = "api",
    /** `false` только для локальной отладки: боевой стенд без TLS не отвечает. */
    val secure: Boolean = true,
    /** Явный порт; `null` — по схеме (443/80). */
    val port: Int? = null,
    /**
     * Прокси. Смена — правка конфигурации, и **никакой правки кода**: транспорт
     * получает готовый адрес отсюда.
     */
    val proxy: ProxyConfig? = null,
)

/**
 * Прокси между приложением и сервером.
 *
 * Заголовок `Host` при этом остаётся **прежним** — именем сервера, а не прокси:
 * иначе Caddy на стенде не найдёт сайт и отдаст 404, а выглядеть это будет как
 * «сервер потерял API».
 */
@Serializable
data class ProxyConfig(
    val host: String,
    val port: Int,
    val secure: Boolean = true,
)

/**
 * Собранные адреса. Единственное место, где имя хоста превращается в URL.
 *
 * Что здесь НЕ делается: соединений, повторов, заголовков авторизации. Это
 * транспорт, и он появится следующим срезом К3 — маршрут обязан быть проверяемым
 * без сети.
 */
class ServerRoute private constructor(
    /** Имя сервера в ASCII — то, что едет в TLS SNI и в заголовке `Host`. */
    val serverHost: String,
    /** Куда фактически открывать соединение: прокси, если он задан, иначе сервер. */
    val connectHost: String,
    val connectPort: Int,
    private val secure: Boolean,
) {

    /** Схема для REST: `https` или `http`. */
    val scheme: String get() = if (secure) "https" else "http"

    /** Схема для WebSocket: `wss` или `ws`. */
    val wsScheme: String get() = if (secure) "wss" else "ws"

    /** Базовый адрес REST: `https://api.<host>` без завершающей косой черты. */
    val apiBase: String get() = "$scheme://$serverHost${portSuffix()}"

    /** Адрес WebSocket — один на устройство (`websocket-events.md`). */
    val wsUrl: String get() = "$wsScheme://$serverHost${portSuffix()}/ws"

    /** Полный адрес ручки: `path` начинается с косой черты. */
    fun api(path: String): String {
        require(path.startsWith("/")) { "путь обязан начинаться с /: $path" }
        return apiBase + path
    }

    /** Идёт ли соединение через прокси — для диагностики, не для логики. */
    val throughProxy: Boolean get() = connectHost != serverHost

    private fun portSuffix(): String {
        val default = if (secure) 443 else 80
        return if (connectPort == default && !throughProxy) "" else ":$connectPort"
    }

    override fun toString(): String =
        "ServerRoute(server=$serverHost, connect=$connectHost:$connectPort, proxy=$throughProxy)"

    companion object {
        /**
         * Собирает маршрут из конфигурации.
         *
         * Два перевода, которые легко забыть и которые оба измерены на живом стенде:
         *
         * 1. **punycode.** Кириллическое имя в TLS и в `Host` не работает — сервер
         *    отвечает только на `xn--…`. Перевод делается здесь, один раз.
         * 2. **поддомен `api.`** Корневой домен стенда отдаёт 404: API живёт на
         *    поддомене. Забыть его — получить 404 и искать поломку в сервере.
         */
        fun from(config: RouteConfig): ServerRoute {
            require(config.host.isNotBlank()) { "имя хоста пустое" }
            require(!config.host.contains("/")) {
                "ожидается имя хоста, а не URL: ${config.host}"
            }

            val ascii = Punycode.encodeHost(config.host.trim().trimEnd('.').lowercase())
            val serverHost = config.apiSubdomain
                ?.takeIf { it.isNotBlank() }
                ?.let { "${Punycode.encodeHost(it)}.$ascii" }
                ?: ascii

            val proxy = config.proxy
            val connectHost = proxy?.let { Punycode.encodeHost(it.host.lowercase()) } ?: serverHost
            val connectPort = proxy?.port
                ?: config.port
                ?: if (config.secure) 443 else 80

            return ServerRoute(
                serverHost = serverHost,
                connectHost = connectHost,
                connectPort = connectPort,
                secure = proxy?.secure ?: config.secure,
            )
        }
    }
}
