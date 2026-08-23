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
import kotlin.test.assertTrue

/**
 * Самообъявление платформы.
 *
 * Ручка выглядит служебной, а решает продуктовое: **подтвердить привязку нового
 * устройства по QR вправе только телефон**, и знает об этом сервер по колонке `platform`.
 * Устройство, объявившее себя не тем, получит отказ в привязке — по симптому неотличимый
 * от поломки самого QR. Поэтому здесь проверяется не «код 200», а что уходит и что
 * возвращается.
 *
 * Форма ответов взята из `internal/api/devices.go` дословно.
 */
class DevicesApiTest {

    private val route = ServerRoute.from(RouteConfig(host = "example.com"))
    private lateinit var engine: MockEngine

    private fun api(отвечает: MockRequestHandler): DevicesApi {
        engine = MockEngine(отвечает)
        return DevicesApi(route, HttpClient(engine) { timaDefaults() }, token = { "t-1" })
    }

    private fun json(тело: String, статус: HttpStatusCode = HttpStatusCode.OK): MockRequestHandler =
        { respond(тело, статус, headersOf("Content-Type", "application/json")) }

    private fun тело(запрос: HttpRequestData): String = (запрос.body as TextContent).text

    @Test
    fun объявление_уходит_путом_с_токеном_и_платформой() = runTest {
        val api = api(json("""{"platform":"android"}"""))

        api.declarePlatform("android")

        val запрос = engine.requestHistory.single()
        assertEquals("PUT", запрос.method.value, "объявление идёт PUT: оно идемпотентно")
        assertTrue(
            запрос.url.encodedPath.endsWith("/api/v1/devices/me/platform"),
            "не тот путь: ${запрос.url}",
        )
        assertEquals("Bearer t-1", запрос.headers["Authorization"], "ручка авторизованная")
        assertTrue(тело(запрос).contains(""""platform":"android""""), "не то тело: ${тело(запрос)}")
    }

    /**
     * Возвращается то, **как понял сервер**, а не то, что мы послали.
     *
     * Сервер приводит объявленное к своему списку, и всё незнакомое становится пустой
     * строкой. Отдай наружу своё же значение — и опечатка выглядела бы как успех.
     */
    @Test
    fun ответ_сервера_а_не_своё_же_значение() = runTest {
        val api = api(json("""{"platform":"desktop"}"""))

        val исход = api.declarePlatform("Desktop")

        assertIs<PlatformResult.Declared>(исход)
        assertEquals("desktop", исход.platform)
    }

    /** Неизвестная платформа — отказ сервера, а не тихое «ну ладно». */
    @Test
    fun незнакомая_платформа_это_отказ() = runTest {
        val api = api(
            json(
                """{"code":"bad_platform","message":"platform: android, ios или desktop"}""",
                HttpStatusCode.BadRequest,
            ),
        )

        val исход = api.declarePlatform("windows-phone")

        assertIs<PlatformResult.Refused>(исход)
        assertEquals(400, исход.status)
        assertEquals("bad_platform", исход.code)
    }

    /** Сети нет — это отдельный исход: его повторяют, а отказ сервера повторять нечего. */
    @Test
    fun отказ_связи_отличается_от_отказа_сервера() = runTest {
        val api = api { throw IOException("сеть отвалилась") }

        val исход = api.declarePlatform("android")

        assertIs<PlatformResult.NoConnection>(исход)
    }
}
