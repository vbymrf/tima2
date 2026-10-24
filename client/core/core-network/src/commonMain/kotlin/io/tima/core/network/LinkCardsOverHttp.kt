package io.tima.core.network

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import io.tima.domain.chat.HtmlToMarks
import io.tima.domain.chat.LinkCard
import io.tima.domain.chat.LinkCards
import io.tima.domain.chat.LinkSafety

/**
 * Карточка ссылки со страницы (Решения §4). Читаются только служебные строки — `og:title`,
 * `og:description`, `og:image`, запасные `<title>` и `<meta name="description">`; страница не
 * исполняется. Пределы: HTML до 512 КБ, картинка до 5 МБ, 10 с на запрос, переходы — не больше
 * трёх и каждый проверяется заново: переход во внутреннюю сеть так же запрещён, как и ссылка.
 *
 * Свой клиент, без наших заголовков и токена: сайту незачем знать, что за ним пришёл TIMA.
 */
class LinkCardsOverHttp(
    private val client: HttpClient = HttpClient(httpEngine()) {
        followRedirects = false
        install(HttpTimeout) {
            requestTimeoutMillis = TIMEOUT_MS
            connectTimeoutMillis = TIMEOUT_MS
        }
    },
    /** Во внутренний ли адрес превращается имя — DNS платформы; проверки подставляют свой ответ. */
    private val inside: suspend (String) -> Boolean = ::resolvesInside,
) : LinkCards {

    override suspend fun card(url: String): LinkCard? = runCatching {
        val page = fetch(url, MAX_HTML) ?: return null
        val html = page.second.decodeToString()
        val title = meta(html, "og:title") ?: tag(html, "title")
        val description = meta(html, "og:description") ?: meta(html, "description")
        val imageUrl = meta(html, "og:image")?.let { resolve(page.first, it) }
        val image = imageUrl?.let { fetch(it, MAX_IMAGE)?.second }
        if (title.isNullOrBlank() && description.isNullOrBlank() && image == null) null
        else LinkCard(title.orEmpty().take(MAX_TITLE), description.orEmpty().take(MAX_DESCRIPTION), image)
    }.getOrNull()

    /**
     * Текст по адресу — список m3u8 при вставке (ПЛАН-(ВМ) Р34–Р36): те же пределы и запреты, что у
     * карточки; адрес после переходов и текст до [limit]; нельзя или не вышло — `null`.
     */
    suspend fun text(url: String, limit: Int = MAX_HTML): Pair<String, String>? = runCatching {
        fetch(url, limit)?.let { it.first to it.second.decodeToString() }
    }.getOrNull()

    /**
     * Начало файла по ссылке «Облака» (заказчик 2026-10-24): первые [limit] байт куском (`Range`), тип,
     * размер и имя от сервера — для окна файла у получателя: что за файл и не программа ли под видом
     * картинки. Те же запреты, что у карточки. Нельзя или не вышло — `null`.
     */
    suspend fun peek(url: String, limit: Int = PEEK): io.tima.domain.chat.CloudPeek? = runCatching {
        got(url, limit, range = true, whole = false)?.let { io.tima.domain.chat.CloudPeek(it.url, it.type, it.size, it.name, it.bytes) }
    }.getOrNull()

    /** Файл по ссылке целиком — «Сохранить как»; больше [limit] или нельзя — `null`. */
    suspend fun whole(url: String, limit: Int): io.tima.domain.chat.CloudPeek? = runCatching {
        got(url, limit, range = false, whole = true)?.let { io.tima.domain.chat.CloudPeek(it.url, it.type, it.size, it.name, it.bytes) }
    }.getOrNull()

    /** Адрес после переходов и байты до предела; запрещённый адрес или ошибка — `null`. */
    private suspend fun fetch(start: String, limit: Int): Pair<String, ByteArray>? =
        got(start, limit, range = false, whole = limit == MAX_IMAGE)?.let { it.url to it.bytes }

    /** Ответ: адрес после переходов, байты, тип, полный размер и имя из заголовков. */
    private class Got(val url: String, val bytes: ByteArray, val type: String?, val size: Long?, val name: String?)

    /**
     * Запрос с переходами — каждый проверяется заново. [range] — просить только начало; [whole] — больше
     * [limit] не берётся вовсе (картинка, файл целиком), иначе обрезается (страница, начало файла).
     */
    private suspend fun got(start: String, limit: Int, range: Boolean, whole: Boolean): Got? {
        var url = start
        repeat(MAX_REDIRECTS + 1) {
            if (!LinkSafety.allowed(url)) return null
            // Имя, которое DNS превращает во внутренний адрес, — тоже внутренняя сеть (Решения §4).
            if (inside(LinkSafety.hostOf(url) ?: return null)) return null
            val here = url
            val result = client.prepareGet(url) {
                header(HttpHeaders.Accept, "text/html,image/*;q=0.9,*/*;q=0.5")
                if (range) header(HttpHeaders.Range, "bytes=0-${limit - 1}")
            }.execute { resp ->
                val location = resp.headers[HttpHeaders.Location]
                when {
                    resp.status.value in 300..399 && location != null -> Redirect(resolve(here, location))
                    resp.status.isSuccess() -> Body(
                        read(resp, limit, whole),
                        resp.headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim(),
                        sizeOf(resp.headers[HttpHeaders.ContentRange], resp.headers[HttpHeaders.ContentLength], resp.status.value),
                        fileNameOf(resp.headers[HttpHeaders.ContentDisposition]),
                    )
                    else -> null
                }
            } ?: return null
            when (result) {
                is Redirect -> url = result.to
                is Body -> return Got(url, result.bytes ?: return null, result.type, result.size, result.name)
            }
        }
        return null
    }

    private sealed interface Step
    private class Redirect(val to: String) : Step
    private class Body(val bytes: ByteArray?, val type: String? = null, val size: Long? = null, val name: String? = null) : Step

    /** Не больше [limit] байт: больше — обрезается, а с [whole] не берётся вовсе. */
    private suspend fun read(resp: HttpResponse, limit: Int, whole: Boolean): ByteArray? {
        val channel = resp.bodyAsChannel()
        val out = ByteArray(limit)
        var size = 0
        while (size < limit) {
            val n = channel.readAvailable(out, size, limit - size)
            if (n <= 0) break
            size += n
        }
        val more = size == limit && channel.readAvailable(ByteArray(1), 0, 1) > 0
        if (more && whole) return null
        return out.copyOf(size)
    }

    /** Полный размер: из `Content-Range` (ответ куском), иначе `Content-Length` целого ответа. */
    private fun sizeOf(range: String?, length: String?, status: Int): Long? =
        range?.substringAfterLast('/')?.trim()?.toLongOrNull() ?: length?.toLongOrNull()?.takeIf { status == 200 }

    /** Имя из `Content-Disposition`: `filename*=UTF-8''…` или `filename="…"`. */
    private fun fileNameOf(header: String?): String? {
        if (header == null) return null
        Regex("filename\\*\\s*=\\s*[^']*''([^;]+)", RegexOption.IGNORE_CASE).find(header)?.let { m ->
            return io.tima.domain.chat.FileKinds.nameOf("x://h/" + m.groupValues[1].trim()).takeIf { it.isNotBlank() }
        }
        return Regex("filename\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(header)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun meta(html: String, name: String): String? {
        for (m in META.findAll(html)) {
            val attrs = m.value
            val key = ATTR_KEY.find(attrs)?.groupValues?.get(2)?.lowercase() ?: continue
            if (key != name) continue
            val content = ATTR_CONTENT.find(attrs)?.groupValues?.get(2) ?: continue
            return HtmlToMarks.decode(content).trim().takeIf { it.isNotEmpty() }
        }
        return null
    }

    private fun tag(html: String, name: String): String? =
        Regex("<$name[^>]*>(.*?)</$name>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(html)
            ?.groupValues?.get(1)?.let { HtmlToMarks.decode(it).replace(Regex("\\s+"), " ").trim() }?.takeIf { it.isNotEmpty() }

    companion object {
        const val MAX_HTML = 512 * 1024
        const val MAX_IMAGE = 5 * 1024 * 1024
        const val TIMEOUT_MS = 10_000L
        const val MAX_REDIRECTS = 3

        /** Начало файла для окна облачного файла: подписи форматов и имена внутри архива — в первых 4 КБ. */
        const val PEEK = 4 * 1024
        private const val MAX_TITLE = 200
        private const val MAX_DESCRIPTION = 400

        private val META = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE)
        private val ATTR_KEY = Regex("(property|name)\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
        private val ATTR_CONTENT = Regex("(content)\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)

        /** Относительный адрес страницы — от адреса, где она лежит. */
        fun resolve(base: String, ref: String): String {
            val r = ref.trim()
            if ("://" in r) return r
            val scheme = base.substringBefore("://")
            val rest = base.substringAfter("://")
            val origin = "$scheme://" + rest.substringBefore('/')
            return when {
                r.startsWith("//") -> "$scheme:$r"
                r.startsWith("/") -> origin + r
                else -> base.substringBeforeLast('/').let { if (it.length < origin.length) origin else it } + "/" + r
            }
        }
    }
}
