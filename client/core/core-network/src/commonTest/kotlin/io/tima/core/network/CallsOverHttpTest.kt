package io.tima.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.tima.core.call.CallStep
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Сигналинг звонка: что клиент делает с ответами сервера.
 *
 * Проверяется не «ходит ли HTTP», а **решения**: что считать открытой дверью, чем отличать
 * «звонков у нас нет» от «не получилось», и что делать с ответом, в котором чего-то не
 * хватает. Всё это тихие случаи — без проверки они обнаруживаются на живом звонке.
 */
class CallsOverHttpTest {

    @Test
    fun дверь_собирается_из_ответа() = runTest {
        val calls = api(
            HttpStatusCode.OK,
            """{"call_id":"c-1","room":"call-abc","url":"wss://lk.пример","token":"жетон"}""",
        )
        val step = calls.start(peerId = "u-2", video = false)
        assertTrue(step is CallStep.Door, "дверь не собралась: $step")
        assertEquals("c-1", step.door.callId)
        assertEquals("call-abc", step.door.room)
        assertEquals("wss://lk.пример", step.door.url)
        assertEquals("жетон", step.door.token)
    }

    @Test
    fun пятьсот_третий_это_не_отказ_а_отсутствие_звонков() = runTest {
        // Сервер отвечает 503 no_livekit, когда у него нет ключей LiveKit. Свалить это в
        // общий отказ значило бы сказать человеку «попробуйте позже» там, где пробовать
        // нечего: звонков нет вовсе, пока не настроят сервер.
        val calls = api(HttpStatusCode.ServiceUnavailable, """{"code":"no_livekit"}""")
        assertEquals(CallStep.NotConfigured, calls.start(peerId = "u-2", video = false))
    }

    @Test
    fun ответ_без_адреса_или_токена_считается_отказом() = runTest {
        // В комнату без адреса и токена не войти. Сделать вид, что дверь открыта, и
        // упасть уже внутри — хуже честного отказа: беда всплывёт дальше от причины.
        val calls = api(HttpStatusCode.OK, """{"call_id":"c-1","room":"call-abc","token":"жетон"}""")
        val step = calls.start(peerId = "u-2", video = false)
        assertTrue(step is CallStep.Refused, "ответ без адреса принят за рабочий: $step")
    }

    @Test
    fun ответ_на_приём_берёт_идентификатор_у_спросившего() = runTest {
        // `/answer` идентификатора звонка не повторяет — он известен тому, кто отвечает.
        val calls = api(HttpStatusCode.OK, """{"room":"call-abc","url":"wss://lk.пример","token":"жетон"}""")
        val step = calls.answer(callId = "c-7")
        assertTrue(step is CallStep.Door)
        assertEquals("c-7", step.door.callId)
    }

    @Test
    fun отказ_сервера_возвращается_кодом_как_есть() = runTest {
        // Без выдуманных слов: код находит строку в обработчике, слова — нет.
        val calls = api(HttpStatusCode.Forbidden, """{"code":"peer_blocked"}""")
        assertEquals(CallStep.Refused("peer_blocked"), calls.start(peerId = "u-2", video = true))
    }

    @Test
    fun дверь_группового_несёт_группу_создателя_и_правила() = runTest {
        // ПЛАН-ГРУППОВЫХ-ЗВОНКОВ ГЗ2: сверх обычных полей — группа, создатель, пауза, правила.
        val calls = api(
            HttpStatusCode.Created,
            """{"call_id":"g-1","room":"call-g","url":"wss://lk","token":"t","type":"group","group_id":"grp",""" +
                """"creator_id":"u-1","paused":true,"rules":{"max":12,"video":[{"up_to":3,"height":540}]}}""",
        )
        val step = calls.startGroup("grp", ring = true, video = true, invited = listOf("u-2"))
        assertTrue(step is CallStep.Door, "дверь не собралась: $step")
        val group = step.door.group
        assertEquals("grp", group?.groupId)
        assertEquals("u-1", group?.creatorId)
        assertEquals(true, group?.paused)
        assertEquals(12, group?.rules?.max)
        assertEquals(540, group?.rules?.heightFor(3))
        assertEquals(null, group?.rules?.heightFor(4))
    }

    @Test
    fun дверь_личного_без_группы() = runTest {
        val calls = api(HttpStatusCode.OK, """{"call_id":"c-1","room":"r","url":"wss://lk","token":"t"}""")
        val step = calls.start(peerId = "u-2", video = false)
        assertTrue(step is CallStep.Door)
        assertEquals(null, step.door.group)
    }

    @Test
    fun звонок_группы_читается_с_участниками() = runTest {
        val calls = api(
            HttpStatusCode.OK,
            """{"call":{"call_id":"g-1","creator_id":"u-1","kind":"video","started_at":"2026-10-01T10:00:00Z","paused":false,""" +
                """"participants":[{"user_id":"u-1","state":"joined","invited":true,"removed":false},""" +
                """{"user_id":"u-2","state":"invited","invited":true,"removed":false}]},""" +
                """"rules":{"max":25,"video":[{"up_to":4,"height":720},{"up_to":8,"height":480}]},"my_role":"member","can_start":false,""" +
                """"call_ttl_until":"2026-10-01T22:00:00Z"}""",
        )
        val info = calls.groupCall("grp")
        assertEquals("g-1", info?.call?.callId)
        assertEquals(1, info?.call?.members?.count { it.inRoom })
        assertEquals(false, info?.canStart)
        assertTrue((info?.ttlUntilMs ?: 0) > 0, "срок временной группы не прочитан")
    }

    private fun api(status: HttpStatusCode, body: String) = CallsOverHttp(
        route = ServerRoute.from(RouteConfig(host = "example.com")),
        client = HttpClient(
            MockEngine { respond(body, status, headersOf("Content-Type", ContentType.Application.Json.toString())) },
        ),
        token = { "токен" },
    )
}
