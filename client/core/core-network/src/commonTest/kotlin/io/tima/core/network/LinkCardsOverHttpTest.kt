package io.tima.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Карточка ссылки (Решения §4): служебные строки страницы, картинка с сайта, переход во внутреннюю
 * сеть запрещён так же, как ссылка, большая картинка не берётся.
 */
class LinkCardsOverHttpTest {

    private val asked = mutableListOf<String>()

    private fun cards(pages: Map<String, Triple<HttpStatusCode, String, ByteArray>>) = LinkCardsOverHttp(
        HttpClient(
            MockEngine { req ->
                val url = req.url.toString()
                asked += url
                val (status, location, body) = pages[url] ?: Triple(HttpStatusCode.NotFound, "", ByteArray(0))
                respond(body, status, if (location.isEmpty()) headersOf() else headersOf(HttpHeaders.Location, location))
            },
        ) { followRedirects = false },
    )

    private fun page(html: String) = Triple(HttpStatusCode.OK, "", html.encodeToByteArray())

    @Test
    fun служебные_строки_и_картинка() = runTest {
        val html = """<html><head><title>Запас</title>
            <meta property="og:title" content="Сбор &amp; поход">
            <meta content="Описание" name="description">
            <meta property="og:image" content="/img/a.jpg"></head><body>много</body></html>"""
        val card = cards(
            mapOf(
                "https://tima.chat/p" to page(html),
                "https://tima.chat/img/a.jpg" to Triple(HttpStatusCode.OK, "", ByteArray(10) { 1 }),
            ),
        ).card("https://tima.chat/p")!!
        assertEquals("Сбор & поход", card.title)
        assertEquals("Описание", card.description)
        assertEquals(10, card.image!!.size)
    }

    @Test
    fun запасное_название_из_title() = runTest {
        val card = cards(mapOf("https://a.example/" to page("<title> Просто\n страница </title>"))).card("https://a.example/")!!
        assertEquals("Просто страница", card.title)
        assertNull(card.image)
    }

    @Test
    fun переход_во_внутреннюю_сеть_не_выполняется() = runTest {
        val c = cards(
            mapOf(
                "https://a.example/go" to Triple(HttpStatusCode.Found, "http://192.168.1.1/admin", ByteArray(0)),
                "http://192.168.1.1/admin" to page("<title>роутер</title>"),
            ),
        )
        assertNull(c.card("https://a.example/go"))
        assertTrue(asked.none { "192.168" in it }, "к частному адресу запроса не было")
        assertNull(c.card("http://localhost/x"))
        assertNull(c.card("file:///etc/passwd"))
        assertEquals(1, asked.size)
    }

    @Test
    fun большая_картинка_не_берётся() = runTest {
        val html = """<meta property="og:title" content="Т"><meta property="og:image" content="https://a.example/big.jpg">"""
        val card = cards(
            mapOf(
                "https://a.example/" to page(html),
                "https://a.example/big.jpg" to Triple(HttpStatusCode.OK, "", ByteArray(LinkCardsOverHttp.MAX_IMAGE + 1)),
            ),
        ).card("https://a.example/")!!
        assertEquals("Т", card.title)
        assertNull(card.image)
    }

    @Test
    fun относительные_адреса() {
        assertEquals("https://a.example/x.png", LinkCardsOverHttp.resolve("https://a.example/p/q", "/x.png"))
        assertEquals("https://a.example/p/x.png", LinkCardsOverHttp.resolve("https://a.example/p/q", "x.png"))
        assertEquals("https://cdn.example/x.png", LinkCardsOverHttp.resolve("https://a.example/p", "//cdn.example/x.png"))
    }
}
