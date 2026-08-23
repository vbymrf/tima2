package io.tima.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.utils.io.errors.IOException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Три ручки привязки. Форма ответов — из `internal/api/device_link.go` дословно.
 *
 * Проверяется главным образом **различение исходов**. Сервер отвечает на `confirm` тремя
 * разными отказами, и за каждым стоит своё действие человека: «подтвердите с телефона»,
 * «покажите код заново», «код не тот». Слипнись они в «не получилось» — человек будет
 * пробовать одно и то же.
 */
class LinkApiTest {

    private val route = ServerRoute.from(RouteConfig(host = "example.com"))
    private lateinit var engine: MockEngine

    private fun старт(отвечает: MockRequestHandler): LinkStartApi {
        engine = MockEngine(отвечает)
        return LinkStartApi(route, HttpClient(engine) { timaDefaults() })
    }

    private fun подтверждение(отвечает: MockRequestHandler): LinkConfirmApi {
        engine = MockEngine(отвечает)
        return LinkConfirmApi(route, HttpClient(engine) { timaDefaults() }, token = { "t-1" })
    }

    private fun json(тело: String, статус: HttpStatusCode = HttpStatusCode.OK): MockRequestHandler =
        { respond(тело, статус, headersOf("Content-Type", "application/json")) }

    private fun тело(запрос: HttpRequestData): String = (запрос.body as TextContent).text

    private val ключ = ByteArray(32) { it.toByte() }

    // ── start ───────────────────────────────────────────────────────────────

    @Test
    fun старт_идёт_без_авторизации_и_приносит_код() = runTest {
        val api = старт(
            json(
                """{"session_id":"s-1","qr_payload":"tima://link/v1?session_id=s-1",""" +
                    """"claim_token":"c-1","expires_at":"2026-08-23T10:00:00Z"}""",
            ),
        )

        val исход = api.start(ключ, ключ, "Компьютер")

        val запрос = engine.requestHistory.single()
        assertTrue(запрос.url.encodedPath.endsWith("/api/v1/link/start"), "не тот путь: ${запрос.url}")
        assertNull(
            запрос.headers["Authorization"],
            "у нового устройства токена нет: посылать его значило бы врать серверу",
        )
        assertTrue(тело(запрос).contains(""""device_name":"Компьютер""""), "не то тело: ${тело(запрос)}")

        assertIs<LinkStartResult.Started>(исход)
        assertEquals("s-1", исход.sessionId)
        assertEquals("c-1", исход.claimToken)
        assertEquals("tima://link/v1?session_id=s-1", исход.qrPayload)
    }

    /** Ответ без обязательного поля — отказ, а не молчаливая пустая строка в QR. */
    @Test
    fun ответ_без_кода_это_отказ() = runTest {
        val api = старт(json("""{"session_id":"s-1"}"""))

        assertIs<LinkStartResult.Refused>(api.start(ключ, ключ, "Компьютер"))
    }

    // ── claim ───────────────────────────────────────────────────────────────

    /**
     * «Ещё не подтвердили» — **не отказ**.
     *
     * Это нормальное состояние ожидания: человек ещё не отсканировал код. Показать здесь
     * беду значит научить его не верить сообщениям о бедах.
     */
    @Test
    fun ещё_не_подтвердили_это_ожидание_а_не_беда() = runTest {
        val api = старт(
            json(
                """{"code":"not_ready","message":"устройство ещё не подтверждено"}""",
                HttpStatusCode.Forbidden,
            ),
        )

        assertEquals(LinkClaimResult.NotReady, api.claim("s-1", "c-1"))
    }

    @Test
    fun подтверждение_приносит_сессию() = runTest {
        val api = старт(json("""{"user_id":"u-1","device_id":"d-2","access_token":"a-2"}"""))

        val исход = api.claim("s-1", "c-1")

        assertIs<LinkClaimResult.Claimed>(исход)
        assertEquals("u-1", исход.userId)
        assertEquals("d-2", исход.deviceId)
        assertEquals("a-2", исход.accessToken)
    }

    // ── confirm ─────────────────────────────────────────────────────────────

    @Test
    fun подтверждение_идёт_с_токеном_и_подписью() = runTest {
        val api = подтверждение(json("""{"status":"confirmed","device_id":"d-2"}"""))

        val исход = api.confirm("s-1", "секрет", ByteArray(64) { 7 })

        val запрос = engine.requestHistory.single()
        assertTrue(запрос.url.encodedPath.endsWith("/api/v1/link/confirm"))
        assertEquals("Bearer t-1", запрос.headers["Authorization"], "ручка авторизованная")
        assertTrue(тело(запрос).contains(""""secret":"секрет""""), "не то тело: ${тело(запрос)}")

        assertIs<LinkConfirmResult.Confirmed>(исход)
        assertEquals(
            "d-2",
            исход.deviceId,
            "адрес нового устройства нужен, чтобы перезавернуть на него ключи истории",
        )
    }

    /** Три отказа сервера различаются: за каждым своё действие человека. */
    @Test
    fun отказы_подтверждения_не_слипаются() = runTest {
        assertEquals(
            LinkConfirmResult.NotAPhone,
            подтверждение(json("""{"code":"not_a_phone"}""", HttpStatusCode.Forbidden))
                .confirm("s-1", "s", ByteArray(64)),
        )
        assertEquals(
            LinkConfirmResult.SessionGone,
            подтверждение(json("""{"code":"bad_session"}""", HttpStatusCode.Forbidden))
                .confirm("s-1", "s", ByteArray(64)),
        )
        assertEquals(
            LinkConfirmResult.BadSignature,
            подтверждение(json("""{"code":"bad_signature"}""", HttpStatusCode.Forbidden))
                .confirm("s-1", "s", ByteArray(64)),
        )
    }

    @Test
    fun отказ_связи_отличается_от_отказа_сервера() = runTest {
        val api = подтверждение { throw IOException("сеть отвалилась") }

        assertIs<LinkConfirmResult.NoConnection>(api.confirm("s-1", "s", ByteArray(64)))
    }
}
