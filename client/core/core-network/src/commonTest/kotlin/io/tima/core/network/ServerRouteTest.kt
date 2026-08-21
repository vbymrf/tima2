package io.tima.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Маршрут собирается из конфигурации — и собирается правильно на двух вещах,
 * которые уже стоили времени на живом стенде: punycode и поддомен `api.`
 *
 * Ожидаемые значения здесь **не вычислены мной**, а взяты с работающего сервера
 * (`doc_mig/arhiv/СТЕНД.md`). Это и делает их проверкой, а не переписыванием
 * реализации в другой форме.
 */
class ServerRouteTest {

    @Test
    fun кириллический_домен_переводится_в_punycode_как_на_стенде() {
        // Домен стенда — пацак.рф. Punycode взят из фактического адреса API, по
        // которому сервер отвечает 200; по кириллическому имени TLS не встаёт.
        val route = ServerRoute.from(RouteConfig(host = "пацак.рф"))

        assertEquals("api.xn--80aa4ar0b.xn--p1ai", route.serverHost)
        assertEquals("https://api.xn--80aa4ar0b.xn--p1ai", route.apiBase)
        assertEquals("wss://api.xn--80aa4ar0b.xn--p1ai/ws", route.wsUrl)
    }

    @Test
    fun ascii_домен_остаётся_как_есть_без_префикса() {
        // Если бы метки без не-ASCII тоже получали xn--, example.com превратился бы
        // в xn--example.xn--com — то есть в не-адрес.
        val route = ServerRoute.from(RouteConfig(host = "example.com"))
        assertEquals("api.example.com", route.serverHost)
    }

    @Test
    fun поддомен_api_обязателен_по_умолчанию_и_отключается_явно() {
        // Корневой домен стенда отдаёт 404: API живёт на поддомене. Значение по
        // умолчанию выбрано так, чтобы забыть его было нельзя.
        assertEquals(
            "api.example.com",
            ServerRoute.from(RouteConfig(host = "example.com")).serverHost,
        )
        assertEquals(
            "example.com",
            ServerRoute.from(RouteConfig(host = "example.com", apiSubdomain = null)).serverHost,
        )
    }

    @Test
    fun смена_прокси_меняет_только_адрес_соединения() {
        // Выход этапа К3: «смена прокси — конфигом». Значит имя сервера обязано
        // остаться прежним: оно едет в TLS SNI и в заголовке Host, и подмена его на
        // имя прокси даст 404 от Caddy — то есть «сервер потерял API».
        val прямо = ServerRoute.from(RouteConfig(host = "пацак.рф"))
        val через = ServerRoute.from(
            RouteConfig(host = "пацак.рф", proxy = ProxyConfig("proxy.local", 8443)),
        )

        assertEquals(прямо.serverHost, через.serverHost)
        assertEquals("proxy.local", через.connectHost)
        assertEquals(8443, через.connectPort)
        assertTrue(через.throughProxy)
        assertFalse(прямо.throughProxy)
    }

    @Test
    fun порт_по_умолчанию_в_адрес_не_попадает() {
        val обычный = ServerRoute.from(RouteConfig(host = "example.com"))
        assertEquals("https://api.example.com", обычный.apiBase)

        val нестандартный = ServerRoute.from(RouteConfig(host = "example.com", port = 8443))
        assertEquals("https://api.example.com:8443", нестандартный.apiBase)
    }

    @Test
    fun без_tls_схема_меняется_вместе_с_портом() {
        val route = ServerRoute.from(RouteConfig(host = "localhost", apiSubdomain = null, secure = false))
        assertEquals("http://localhost", route.apiBase)
        assertEquals("ws://localhost/ws", route.wsUrl)
    }

    @Test
    fun путь_ручки_склеивается_с_базой() {
        val route = ServerRoute.from(RouteConfig(host = "example.com"))
        assertEquals("https://api.example.com/api/v1/app/version", route.api("/api/v1/app/version"))
        // Путь без ведущей косой черты — это склейка вида «…comapi/v1», и молча
        // такое пропускать нельзя.
        assertFailsWith<IllegalArgumentException> { route.api("api/v1/app/version") }
    }

    @Test
    fun url_вместо_имени_хоста_отвергается() {
        // Человек вводит имя, а не адрес. «https://пацак.рф» превратилось бы в
        // «api.https://пацак.рф» — и падение случилось бы у транспорта, далеко от
        // причины.
        assertFailsWith<IllegalArgumentException> {
            ServerRoute.from(RouteConfig(host = "https://пацак.рф"))
        }
        assertFailsWith<IllegalArgumentException> { ServerRoute.from(RouteConfig(host = "  ")) }
    }

    @Test
    fun регистр_и_точка_на_конце_не_меняют_адрес() {
        // FQDN с точкой на конце — законная форма, и она приходит из настроек DNS.
        val a = ServerRoute.from(RouteConfig(host = "ПАЦАК.РФ"))
        val b = ServerRoute.from(RouteConfig(host = "пацак.рф."))
        assertEquals("api.xn--80aa4ar0b.xn--p1ai", a.serverHost)
        assertEquals(a.serverHost, b.serverHost)
    }
}
