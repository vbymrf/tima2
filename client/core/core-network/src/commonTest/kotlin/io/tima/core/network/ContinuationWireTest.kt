package io.tima.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** «Только продолжение» (Решения §11.3) уходит открытым полем запроса и приходит полем кадра. */
class ContinuationWireTest {

    @Test
    fun поле_уходит_только_у_помеченного() = runTest {
        val bodies = mutableListOf<String>()
        val transport = GroupTransportOverHttp(
            GroupMessagesApi(
                route = ServerRoute.from(RouteConfig(host = "example.com")),
                client = HttpClient(
                    MockEngine { req ->
                        bodies += (req.body as TextContent).text
                        respond("""{"message_id":1}""", HttpStatusCode.Created, headersOf("Content-Type", ContentType.Application.Json.toString()))
                    },
                ),
                token = { "t" },
            ),
            continuation = { it == "cont" },
        )
        transport.send("g", "cont", 1, 0, ByteArray(1), ByteArray(64), 1, level = 3, threadRoot = 0, keyCommitment = null, editOf = 0)
        transport.send("g", "main", 1, 0, ByteArray(1), ByteArray(64), 1, level = 1, threadRoot = 0, keyCommitment = null, editOf = 0)
        assertTrue("\"only_continuation\":true" in bodies[0])
        assertFalse("only_continuation" in bodies[1])
    }

    @Test
    fun кадр_несёт_пометку() {
        val json = """{"message_id":5,"group_id":"g","sender_id":"u","sender_device":"d","kind":1,"gk_version":0,
            |"payload":"AA","thread_root":0,"reply_to":0,"created_at_unix_ms":1,"signature":"AA","level":3,"only_continuation":true}""".trimMargin()
        val frame = GroupFrame.fromJson(kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject)!!
        assertTrue(frame.onlyContinuation)
        assertFalse(GroupFrame.fromJson(kotlinx.serialization.json.Json.parseToJsonElement(json.replace(",\"only_continuation\":true", "")).jsonObject)!!.onlyContinuation)
    }
}
