package io.tima.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Отдать обёртки ключей просящему устройству — ответ сервера `201`.
 *
 * Живьём 2026-09-30: Redmi при «Доверить» отдал ПК ключи трёх групп, сервер трижды ответил
 * 201, а клиент ждал ровно 200 — и записал «отданы не все, отдано=0 нет=3».
 */
class GroupKeyProvideTest {

    private fun api(status: HttpStatusCode, body: String = "{}") = GroupKeyRecoveryApi(
        ServerRoute.from(RouteConfig(host = "example.com")),
        HttpClient(MockEngine { respond(body, status, headersOf("Content-Type", "application/json")) }) { timaDefaults() },
        token = { "t" },
    )

    private val key = ProvidedKey(1, ByteArray(32), ByteArray(80))

    @Test
    fun создано_это_успех() = runTest {
        val answer = api(HttpStatusCode.Created, """{"saved":1}""").provide("g-1", "d-2", listOf(key))
        assertEquals(ProvideResult.Provided(1), answer)
    }

    @Test
    fun отказ_остаётся_отказом() = runTest {
        val answer = api(HttpStatusCode.BadRequest, """{"code":"not_member_device"}""").provide("g-1", "d-2", listOf(key))
        assertIs<ProvideResult.Refused>(answer)
        assertEquals("not_member_device", answer.code)
    }
}
