package io.tima.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.errors.IOException
import io.tima.domain.account.AccountApi
import io.tima.domain.account.CodeRequestStep
import io.tima.domain.account.CodeSubmitStep
import io.tima.domain.account.DeviceCreateStep
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Перевод исходов HTTP в слова продукта. Проверяется насквозь — от ответа сервера до
 * шага, который увидит `domain`: в переводе ошибки живут чаще, чем в самом вызове, и
 * проверять его на выдуманных промежуточных значениях бессмысленно.
 */
class AccountApiOverHttpTest {

    private val route = ServerRoute.from(RouteConfig(host = "example.com"))
    private val key = ByteArray(32) { it.toByte() }

    private fun port(responds: MockRequestHandler): AccountApi =
        AccountApiOverHttp(AuthApi(route, HttpClient(MockEngine(responds)) { timaDefaults() }))

    private fun json(body: String, status: HttpStatusCode = HttpStatusCode.OK): MockRequestHandler =
        { respond(body, status, headersOf("Content-Type", "application/json")) }

    @Test
    fun удачный_путь_переводится_целиком() = runTest {
        val request = port(json("""{"request_id":"r-1","dev_code":"123456"}"""))
            .requestCode("+79001234567")
        assertEquals(CodeRequestStep.CodeRequested("r-1", "123456"), request)

        val check = port(json("""{"registration_token":"rt-1"}""")).submitCode("r-1", "123456")
        assertEquals(CodeSubmitStep.Accepted("rt-1"), check)

        val creation = port(json(
            """{"user_id":"u-1","device_id":"d-1","access_token":"jwt"}""",
            HttpStatusCode.Created,
        )).createDevice("rt-1", key, key, null, "desktop")
        assertEquals(DeviceCreateStep.Created("u-1", "d-1", "jwt"), creation)
    }

    @Test
    fun негодный_телефон_остаётся_негодным_телефоном() = runTest {
        val outcome = port(json("""{"request_id":"r"}""")).requestCode("не телефон")
        assertIs<CodeRequestStep.BadPhone>(outcome)
    }

    @Test
    fun неверный_код_становится_своим_шагом_а_не_отказом() = runTest {
        val outcome = port(json("""{"code":"bad_code"}""", HttpStatusCode.Forbidden))
            .submitCode("r-1", "000000")
        assertEquals(CodeSubmitStep.WrongCode, outcome)
    }

    @Test
    fun чужая_личность_и_просроченный_токен_не_сливаются_в_отказ() = runTest {
        val foreign = port(json("""{"code":"identity_mismatch"}""", HttpStatusCode.Forbidden))
            .createDevice("rt", key, key, null, "desktop")
        assertEquals(DeviceCreateStep.IdentityMismatch, foreign)

        val expired = port(json("""{"code":"bad_token"}""", HttpStatusCode.Forbidden))
            .createDevice("rt", key, key, null, "desktop")
        assertEquals(DeviceCreateStep.TokenExpired, expired)
    }

    @Test
    fun прочий_отказ_несёт_код_причины_а_не_только_номер() = runTest {
        // Номер статуса без кода бесполезен в отчёте о проблеме: 400 бывает по десяти
        // причинам, и различает их только code.
        val outcome = port(json("""{"code":"bad_keys"}""", HttpStatusCode.BadRequest))
            .createDevice("rt", key, key, null, "desktop")
        assertIs<DeviceCreateStep.Refused>(outcome)
        assertEquals("bad_keys", outcome.reason)
    }

    @Test
    fun обрыв_связи_доносит_паузу_из_состояния_связи() = runTest {
        // Пауза измерена в живой мобильной сети v1. Domain её не придумывает — и здесь
        // проверяется, что она не теряется по дороге.
        val port = port { throw IOException("Unable to resolve host") }

        val request = port.requestCode("+79001234567")
        assertIs<CodeRequestStep.Offline>(request)
        assertEquals(LinkState.NO_NETWORK.retryDelayMs, request.retryAfterMs)
        assertTrue(request.retryAfterMs > 0, "пауза без числа бесполезна")

        assertIs<CodeSubmitStep.Offline>(port.submitCode("r", "1"))
        assertIs<DeviceCreateStep.Offline>(port.createDevice("rt", key, key, null, "desktop"))
    }
}
