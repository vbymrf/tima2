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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Регистрация устройства (К4.3). Ответы взяты из `internal/api/auth.go` дословно —
 * каталог API эти три ручки не описывает вовсе (сверка Д3), и придумывать их форму
 * значило бы проверять свою фантазию.
 */
class AuthApiTest {

    private val route = ServerRoute.from(RouteConfig(host = "example.com"))
    private val key = ByteArray(32) { it.toByte() }
    private lateinit var engine: MockEngine

    private fun api(responds: MockRequestHandler): AuthApi {
        engine = MockEngine(responds)
        return AuthApi(route, HttpClient(engine) { timaDefaults() })
    }

    private fun json(body: String, status: HttpStatusCode = HttpStatusCode.OK): MockRequestHandler =
        { respond(body, status, headersOf("Content-Type", "application/json")) }

    /** Тело запроса как текст: всё, что здесь отправляется, — JSON. */
    private fun HttpRequestData.text(): String = (body as TextContent).text

    // ── запрос кода ──────────────────────────────────────────────────────────

    @Test
    fun код_запрашивается_и_dev_код_доезжает() = runTest {
        // dev_code сервер отдаёт только при TIMA_DEV_SMS. Харнесс К4 живёт именно этим:
        // иначе сквозной путь требовал бы настоящей SMS на настоящий телефон.
        val api = api(json("""{"request_id":"r-1","dev_code":"123456"}"""))

        val outcome = api.requestSms("+79001234567")

        assertIs<SmsRequestResult.Sent>(outcome)
        assertEquals("r-1", outcome.requestId)
        assertEquals("123456", outcome.devCode)
        assertEquals(
            "https://api.example.com/api/v1/auth/sms/request",
            engine.requestHistory.single().url.toString(),
        )
    }

    @Test
    fun боевой_ответ_без_dev_кода_тоже_принимается() = runTest {
        val outcome = api(json("""{"request_id":"r-1"}""")).requestSms("+79001234567")
        assertIs<SmsRequestResult.Sent>(outcome)
        assertNull(outcome.devCode, "на боевом сервере кода в ответе нет — и это норма")
    }

    @Test
    fun негодный_телефон_не_доходит_до_сети() = runTest {
        // Правило сервера известно: ^\+[1-9][0-9]{7,14}$. Гонять запрос ради заведомого
        // 400 — трата времени человека, стоящего перед полем ввода.
        val api = api(json("""{"request_id":"r-1"}"""))

        for (bad in listOf("", "79001234567", "+0900123456", "+7900", "+7900123456789012", "+7 900 123")) {
            assertIs<SmsRequestResult.BadPhone>(
                api.requestSms(bad),
                "телефон «$bad» обязан быть отвергнут",
            )
        }
        assertEquals(0, engine.requestHistory.size, "ни один запрос не должен был уйти")
    }

    @Test
    fun отказ_сервера_на_запросе_кода_несёт_код_причины() = runTest {
        // Ограничитель частоты отвечает 429: это не «плохой телефон», и человеку надо
        // сказать другое.
        val outcome = api(json("""{"code":"rate_limited"}""", HttpStatusCode.TooManyRequests))
            .requestSms("+79001234567")
        assertIs<SmsRequestResult.Refused>(outcome)
        assertEquals(429, outcome.status)
        assertEquals("rate_limited", outcome.code)
    }

    // ── проверка кода ────────────────────────────────────────────────────────

    @Test
    fun верный_код_даёт_регистрационный_токен() = runTest {
        val api = api(json("""{"registration_token":"rt-1"}"""))
        assertEquals(SmsVerifyResult.Verified("rt-1"), api.verifySms("r-1", "123456"))
    }

    @Test
    fun неверный_код_это_исход_а_не_исключение() = runTest {
        // Неверный код — обычное поведение человека. Исключение заставило бы каждый
        // экран ловить его вместо разбора случая.
        val api = api(json("""{"code":"bad_code","message":"код неверен"}""", HttpStatusCode.Forbidden))
        assertEquals(SmsVerifyResult.BadCode, api.verifySms("r-1", "000000"))
    }

    @Test
    fun пустые_значения_отвергаются_до_сети() = runTest {
        val api = api(json("""{"registration_token":"rt"}"""))
        assertFailsWith<IllegalArgumentException> { api.verifySms("", "123456") }
        assertFailsWith<IllegalArgumentException> { api.verifySms("r-1", "") }
        assertEquals(0, engine.requestHistory.size)
    }

    // ── заведение устройства ─────────────────────────────────────────────────

    @Test
    fun ключи_едут_base64url_без_выравнивания() = runTest {
        // Сервер разбирает их base64.RawURLEncoding. Обычный base64 — с «+», «/» и «=» —
        // даст 400 bad_keys, и выглядеть это будет как «сервер не принимает наши ключи».
        // Ошибка ровно того рода, которую не найти по сообщению.
        val api = api(json(
            """{"user_id":"u-1","device_id":"d-1","access_token":"jwt"}""",
            HttpStatusCode.Created,
        ))
        // Байты подобраны так, что обычный base64 дал бы и «+», и «/»: 0xFB 0xEF 0xBE.
        val withWithPlus = ByteArray(32).also { it[0] = -5; it[1] = -17; it[2] = -66 }

        api.register("rt-1", encryptionPub = withWithPlus, signingPub = key)

        val body = engine.requestHistory.single().text()
        assertFalse(body.contains("="), "выравнивание сервер не примет: $body")
        assertFalse(body.contains("+"), "обычный алфавит base64 сервер не примет: $body")
        assertFalse(body.contains("/"), "обычный алфавит base64 сервер не примет: $body")
        // И проверяем, что байты вообще доехали: «+» и «/» заменяются на «-» и «_».
        assertTrue(body.contains("\"encryption_pub\":\"--"), "ожидались знаки base64url: $body")
    }

    @Test
    fun успешная_регистрация_отдаёт_три_значения() = runTest {
        val api = api(json(
            """{"user_id":"u-1","device_id":"d-1","access_token":"jwt-устройства"}""",
            HttpStatusCode.Created,
        ))

        val outcome = api.register("rt-1", key, key, platform = "desktop")

        assertEquals(RegisterResult.Registered("u-1", "d-1", "jwt-устройства"), outcome)
    }

    @Test
    fun ответ_без_токена_не_считается_успехом() = runTest {
        // 201 без access_token означает либо не наш сервер, либо сломанный ответ. Молча
        // считать это успехом — значит остаться без токена и не понять почему.
        val api = api(json("""{"user_id":"u-1","device_id":"d-1"}""", HttpStatusCode.Created))
        assertIs<RegisterResult.Refused>(api.register("rt-1", key, key))
    }

    @Test
    fun чужая_личность_и_просроченный_токен_это_разные_исходы() = runTest {
        // identity_mismatch — встреча с собственным прошлым аккаунтом, и решать её
        // человеку. bad_token — начинать с запроса кода. Свалить оба в «отказ» значило бы
        // показать одно сообщение на две разные ситуации.
        val withForeign = api(json(
            """{"code":"identity_mismatch","message":"телефон связан с другой личностью"}""",
            HttpStatusCode.Forbidden,
        ))
        assertEquals(RegisterResult.IdentityMismatch, withForeign.register("rt-1", key, key))

        val withExpired = api(json(
            """{"code":"bad_token","message":"registration_token просрочен"}""",
            HttpStatusCode.Forbidden,
        ))
        assertEquals(RegisterResult.TokenExpired, withExpired.register("rt-1", key, key))
    }

    @Test
    fun ключ_не_того_размера_не_уходит_в_сеть() = runTest {
        val api = api(json("""{}""", HttpStatusCode.Created))
        assertFailsWith<IllegalArgumentException> { api.register("rt-1", ByteArray(31), key) }
        assertFailsWith<IllegalArgumentException> { api.register("rt-1", key, ByteArray(33)) }
        assertFailsWith<IllegalArgumentException> {
            api.register("rt-1", key, key, identityPub = ByteArray(16))
        }
        assertEquals(0, engine.requestHistory.size)
    }

    @Test
    fun ключ_личности_едет_только_когда_он_есть() = runTest {
        // Для нового аккаунта его нет; для возврата на новом устройстве он и решает, что
        // личность та же. Пустое поле сервер разобрал бы как «ключ есть и он пустой».
        val api = api(json(
            """{"user_id":"u","device_id":"d","access_token":"t"}""", HttpStatusCode.Created,
        ))

        api.register("rt-1", key, key)
        assertFalse(engine.requestHistory[0].text().contains("identity_pub"), "поля быть не должно вовсе")

        api.register("rt-1", key, key, identityPub = key)
        assertTrue(engine.requestHistory[1].text().contains("identity_pub"))
    }

    // ── сеть ─────────────────────────────────────────────────────────────────

    @Test
    fun обрыв_связи_на_любом_шаге_это_исход_с_паузой() = runTest {
        // Пауза берётся из состояния связи, снятого в живой мобильной сети v1: экран
        // авторизации не должен придумывать свой таймаут.
        val api = api { throw IOException("Unable to resolve host") }

        val first = api.requestSms("+79001234567")
        assertIs<SmsRequestResult.NoConnection>(first)
        assertEquals(LinkState.NO_NETWORK, first.link)

        assertIs<SmsVerifyResult.NoConnection>(api.verifySms("r", "1"))
        assertIs<RegisterResult.NoConnection>(api.register("rt", key, key))
    }
}
