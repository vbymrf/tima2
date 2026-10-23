package io.tima.core.network

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess

/**
 * Поток сайта для своего движка окна проигрывания (ПЛАН-(ВМ) §3.3, Р6): Rutube — их открытый запрос
 * `api/play/options`, VK Видео — страница встраивания, запрошенная **этим же устройством** (ссылки на
 * поток VK привязаны к адресу запросившего). Не вышло — `null`: окно берёт браузерный движок.
 *
 * Свой клиент без наших заголовков и токена — сайту незачем знать, что пришёл TIMA. Куки — только на
 * время одного запроса VK (проверка на робота ставит их переадресацией).
 */
class MediaResolveOverHttp(
    private val client: () -> HttpClient = {
        HttpClient(httpEngine()) {
            install(HttpCookies)
            install(HttpTimeout) {
                requestTimeoutMillis = TIMEOUT_MS
                connectTimeoutMillis = TIMEOUT_MS
            }
        }
    },
) {
    /** Поток HLS ролика Rutube. */
    suspend fun rutube(id: String): String? = runCatching {
        val c = client()
        try {
            val r = c.get("https://rutube.ru/api/play/options/$id/?no_404=true&referer=https%3A%2F%2Frutube.ru") {
                header(HttpHeaders.UserAgent, AGENT)
            }
            if (!r.status.isSuccess()) return null
            val body = r.bodyAsText()
            Regex("\"m3u8\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)?.let(::unescape)
        } finally {
            c.close()
        }
    }.getOrNull()

    /** Поток HLS ролика VK со страницы встраивания. */
    suspend fun vk(oid: String, id: String, hash: String): String? = runCatching {
        val c = client()
        try {
            val r = c.get("https://vkvideo.ru/video_ext.php?oid=$oid&id=$id&hash=$hash") {
                header(HttpHeaders.UserAgent, AGENT)
                header(HttpHeaders.AcceptLanguage, "ru")
            }
            if (!r.status.isSuccess()) return null
            val body = r.bodyAsText()
            Regex("\"hls\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)?.let(::unescape)
        } finally {
            c.close()
        }
    }.getOrNull()

    /**
     * Главный список потока, который отдал сам сайт ([rutube], [vk]), — для выбора качества (8а). Тем же
     * заголовком браузера: ссылка VK чужому браузеру список не отдаёт. Ссылку из сообщения так не читать —
     * для неё безопасный загрузчик карточек.
     */
    suspend fun playlist(url: String): String? = runCatching {
        val c = client()
        try {
            val r = c.get(url) { header(HttpHeaders.UserAgent, AGENT) }
            if (r.status.isSuccess()) r.bodyAsText().take(256 * 1024) else null
        } finally {
            c.close()
        }
    }.getOrNull()

    /** Строка JSON как адрес: `\/` и `&` и прочие `\uXXXX`. */
    private fun unescape(s: String): String =
        s.replace("\\/", "/").replace(Regex("""\\u([0-9a-fA-F]{4})""")) { it.groupValues[1].toInt(16).toChar().toString() }

    private companion object {
        const val TIMEOUT_MS = 15_000L
        const val AGENT = io.tima.core.media.MEDIA_USER_AGENT
    }
}
