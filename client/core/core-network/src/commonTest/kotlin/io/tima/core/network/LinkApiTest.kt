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

    private fun start(responds: MockRequestHandler): LinkStartApi {
        engine = MockEngine(responds)
        return LinkStartApi(route, HttpClient(engine) { timaDefaults() })
    }

    private fun confirmation(responds: MockRequestHandler): LinkConfirmApi {
        engine = MockEngine(responds)
        return LinkConfirmApi(route, HttpClient(engine) { timaDefaults() }, token = { "t-1" })
    }

    private fun json(body: String, status: HttpStatusCode = HttpStatusCode.OK): MockRequestHandler =
        { respond(body, status, headersOf("Content-Type", "application/json")) }

    private fun body(request: HttpRequestData): String = (request.body as TextContent).text

    private val key = ByteArray(32) { it.toByte() }

    // ── start ───────────────────────────────────────────────────────────────

    @Test
    fun старт_идёт_без_авторизации_и_приносит_код() = runTest {
        val api = start(
            json(
                """{"session_id":"s-1","qr_payload":"tima://link/v1?session_id=s-1",""" +
                    """"claim_token":"c-1","expires_at":"2026-08-23T10:00:00Z"}""",
            ),
        )

        val outcome = api.start(key, key, "Компьютер")

        val request = engine.requestHistory.single()
        assertTrue(request.url.encodedPath.endsWith("/api/v1/link/start"), "не тот путь: ${request.url}")
        assertNull(
            request.headers["Authorization"],
            "у нового устройства токена нет: посылать его значило бы врать серверу",
        )
        assertTrue(body(request).contains(""""device_name":"Компьютер""""), "not that body: ${body(request)}")

        assertIs<LinkStartResult.Started>(outcome)
        assertEquals("s-1", outcome.sessionId)
        assertEquals("c-1", outcome.claimToken)
        assertEquals("tima://link/v1?session_id=s-1", outcome.qrPayload)
    }

    /** Ответ без обязательного поля — отказ, а не молчаливая пустая строка в QR. */
    @Test
    fun answer_without_code_this_refusal() = runTest {
        val api = start(json("""{"session_id":"s-1"}"""))

        assertIs<LinkStartResult.Refused>(api.start(key, key, "Компьютер"))
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
        val api = start(
            json(
                """{"code":"not_ready","message":"устройство ещё не подтверждено"}""",
                HttpStatusCode.Forbidden,
            ),
        )

        assertEquals(LinkClaimResult.NotReady, api.claim("s-1", "c-1"))
    }

    @Test
    fun подтверждение_приносит_сессию() = runTest {
        val api = start(json("""{"user_id":"u-1","device_id":"d-2","access_token":"a-2"}"""))

        val outcome = api.claim("s-1", "c-1")

        assertIs<LinkClaimResult.Claimed>(outcome)
        assertEquals("u-1", outcome.userId)
        assertEquals("d-2", outcome.deviceId)
        assertEquals("a-2", outcome.accessToken)
    }

    // ── confirm ─────────────────────────────────────────────────────────────

    @Test
    fun подтверждение_идёт_с_токеном_и_подписью() = runTest {
        val api = confirmation(json("""{"status":"confirmed","device_id":"d-2"}"""))

        val outcome = api.confirm("s-1", "секрет", ByteArray(64) { 7 })

        val request = engine.requestHistory.single()
        assertTrue(request.url.encodedPath.endsWith("/api/v1/link/confirm"))
        assertEquals("Bearer t-1", request.headers["Authorization"], "ручка авторизованная")
        assertTrue(body(request).contains(""""secret":"секрет""""), "not that body: ${body(request)}")

        assertIs<LinkConfirmResult.Confirmed>(outcome)
        assertEquals(
            "d-2",
            outcome.deviceId,
            "address new devices needed, so rewrap on itValue keys stories",
        )
    }

    /** Три отказа сервера различаются: за каждым своё действие человека. */
    @Test
    fun отказы_подтверждения_не_слипаются() = runTest {
        assertEquals(
            LinkConfirmResult.NotAPhone,
            confirmation(json("""{"code":"not_a_phone"}""", HttpStatusCode.Forbidden))
                .confirm("s-1", "s", ByteArray(64)),
        )
        assertEquals(
            LinkConfirmResult.SessionGone,
            confirmation(json("""{"code":"bad_session"}""", HttpStatusCode.Forbidden))
                .confirm("s-1", "s", ByteArray(64)),
        )
        assertEquals(
            LinkConfirmResult.BadSignature,
            confirmation(json("""{"code":"bad_signature"}""", HttpStatusCode.Forbidden))
                .confirm("s-1", "s", ByteArray(64)),
        )
    }

    @Test
    fun отказ_связи_отличается_от_отказа_сервера() = runTest {
        val api = confirmation { throw IOException("сеть отвалилась") }

        assertIs<LinkConfirmResult.NoConnection>(api.confirm("s-1", "s", ByteArray(64)))
    }
}
