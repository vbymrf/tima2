package io.tima.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.tima.core.network.DeviceTokenApi
import io.tima.core.network.RouteConfig
import io.tima.core.network.ServerClock
import io.tima.core.network.ServerRoute
import io.tima.core.network.timaDefaults
import io.tima.domain.account.Session
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Обновление токена без шквала и по часам сервера (ПЛАН-ВЫХОДА-ИЗ-АККАУНТА.md, А1–А3).
 *
 * 2026-09-30 на ПК часы спешили на 3 мин 10 с: сервер отвечал `401 stale_signature` на
 * каждую подпись, приложение повторяло без паузы — 12 231 попытка за 17 минут, — а экран
 * устройств висел на «Смотрим…».
 */
class DeviceTokensRenewTest {

    @AfterTest
    fun сбросить() = ServerClock.reset()

    private var clock = 1_790_776_089_000L

    private fun tokens(handler: MockRequestHandler): Pair<DeviceTokens, MockEngine> {
        val engine = MockEngine(handler)
        val client = HttpClient(engine) { timaDefaults() }
        val api = DeviceTokenApi(ServerRoute.from(RouteConfig(host = "example.com")), client)
        val tokens = DeviceTokens(
            api = api,
            session = Session("u-1", "d-1", ""),
            sign = { ByteArray(64) },
            now = { clock },
            remember = {},
        )
        return tokens to engine
    }

    private val json = headersOf("Content-Type", "application/json")

    @Test
    fun одно_обновление_на_всех() = runTest {
        val (tokens, engine) = tokens { respond("""{"access_token":"новый"}""", HttpStatusCode.OK, json) }

        val results = List(5) { async { tokens.renew() } }.awaitAll()

        assertTrue(results.all { it })
        assertEquals(1, engine.requestHistory.size, "пять ручек с 401 — одно обновление, а не пять")
    }

    @Test
    fun после_отказа_пауза_а_не_шквал() = runTest {
        val (tokens, engine) = tokens {
            respond("""{"code":"bad_signature"}""", HttpStatusCode.Unauthorized, json)
        }

        assertFalse(tokens.renew())
        repeat(10) { assertFalse(tokens.renew()) }
        assertEquals(1, engine.requestHistory.size, "в паузе на сервер не ходим")

        clock += 31_000
        assertFalse(tokens.renew())
        assertEquals(2, engine.requestHistory.size, "пауза кончилась — одна новая попытка")
    }

    @Test
    fun часы_спешат_подпись_ставится_временем_сервера() = runTest {
        // Часы устройства — настоящие (поправку считает сетевой клиент по ним же), а сервер
        // «отстаёт» на 3 мин 10 с — как было с ПК 2026-09-30.
        clock = System.currentTimeMillis()
        val server = clock - 190_000
        val date = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
            .format(java.time.Instant.ofEpochMilli(server).atZone(java.time.ZoneOffset.UTC))
        val issued = mutableListOf<Long>()
        val (tokens, engine) = tokens { request ->
            val body = (request.body as TextContent).text
            issued += Regex(""""issued_at":(\d+)""").find(body)!!.groupValues[1].toLong()
            val headers = headersOf(
                "Content-Type" to listOf("application/json"),
                "Date" to listOf(date),
            )
            if (issued.size == 1) respond("""{"code":"stale_signature"}""", HttpStatusCode.Unauthorized, headers)
            else respond("""{"access_token":"новый"}""", HttpStatusCode.OK, headers)
        }

        assertTrue(tokens.renew(), "после поправки по Date токен выдан")
        assertEquals(2, engine.requestHistory.size, "отказ за сбитые часы — одна переподпись")
        val skew = kotlin.math.abs(issued[1] - server / 1000)
        assertTrue(skew <= 2, "вторая подпись — временем сервера, а разошлась на $skew с")
        assertEquals("новый", tokens.access)
    }

    @Test
    fun отключённое_устройство_помечается() = runTest {
        val (tokens, _) = tokens {
            respond("""{"code":"device_revoked"}""", HttpStatusCode.Unauthorized, json)
        }

        assertFalse(tokens.renew())
        assertTrue(tokens.revoked.value, "экран должен узнать, что устройство отключено")
    }
}
