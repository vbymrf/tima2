package io.tima.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * История группы (ИУ3): страница с сервера становится теми же кадрами, что приносит живой
 * канал. Если кадр истории разберётся иначе, чем живой, сообщение откроется на одном
 * устройстве и останется «недоступным» на другом — и выглядеть это будет как потеря ключа.
 */
class GroupHistoryPageTest {

    private val message = """{"message_id":42,"group_id":"g-1","sender_id":"u-1","sender_device":"d-1",""" +
        """"kind":1,"gk_version":3,"payload":"AQID","thread_root":0,"reply_to":0,""" +
        """"created_at_unix_ms":1700000000000,"signature":"BAUG","level":-1}"""

    @Test
    fun кадр_истории_разбирается_как_живой() = runTest {
        val page = assertNotNull(api(HttpStatusCode.OK, """{"messages":[$message]}""").page("g-1", before = 0))
        assertEquals(1, page.size)
        val item = page.single()
        assertEquals(42L, item.messageId)
        assertEquals(1700000000000L, item.sentAtMs)
        assertTrue(GroupFrame.isGroupFrame(item.stored), "кадр истории не помечен групповым")
        val frame = assertNotNull(GroupFrame.parse(item.stored))
        assertEquals("g-1", frame.groupId)
        assertEquals(3, frame.gkVersion)
        assertEquals("d-1", frame.senderDevice)
    }

    @Test
    fun неполный_кадр_пропускается_а_не_роняет_страницу() = runTest {
        // Без подписи сообщение не проверить: в очередь его класть незачем, но и остальная
        // страница из-за него теряться не должна.
        val broken = """{"message_id":43,"group_id":"g-1","sender_id":"u-1","sender_device":"d-1","payload":"AQID"}"""
        val page = assertNotNull(api(HttpStatusCode.OK, """{"messages":[$message,$broken]}""").page("g-1", before = 0))
        assertEquals(listOf(42L), page.map { it.messageId })
    }

    @Test
    fun отказ_сервера_это_не_пустая_история() = runTest {
        // 404 — группа не видна. Сказать «истории нет» значило бы перестать спрашивать.
        assertNull(api(HttpStatusCode.NotFound, """{"code":"group_not_found"}""").page("g-1", before = 0))
    }

    private fun api(status: HttpStatusCode, body: String) = GroupMessagesApi(
        route = ServerRoute.from(RouteConfig(host = "example.com")),
        client = HttpClient(
            MockEngine { respond(body, status, headersOf("Content-Type", ContentType.Application.Json.toString())) },
        ),
        token = { "токен" },
    )
}
