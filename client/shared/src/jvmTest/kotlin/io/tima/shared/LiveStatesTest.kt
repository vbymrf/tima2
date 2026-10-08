package io.tima.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.tima.core.network.RouteConfig
import io.tima.core.network.ServerRoute
import io.tima.core.network.StatesOverHttp
import io.tima.core.network.timaDefaults
import io.tima.domain.chat.Settings
import io.tima.feature.chat.ChatReceipt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** «Доставлено», «прочитано», «печатает», «в сети» на приложении (ПЛАН-(ОП)). */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveStatesTest {

    private class Memory : Settings {
        val values = MutableStateFlow<Map<String, String>>(emptyMap())
        override fun all(): Flow<Map<String, String>> = values
        override suspend fun put(name: String, value: String) {
            values.value = values.value + (name to value)
        }
    }

    private val route = ServerRoute.from(RouteConfig(host = "example.com"))

    private fun api(onRead: (String) -> Unit = {}, body: () -> String): StatesOverHttp =
        StatesOverHttp(route, HttpClient(MockEngine { request ->
            if (request.method == HttpMethod.Put) {
                onRead(request.url.encodedPath)
                respond("", HttpStatusCode.NoContent)
            } else {
                respond(body(), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            }
        }) { timaDefaults() }, token = { "t" })

    @Test
    fun лента_кладёт_отметки_и_печатает_и_помнит_номер() = runTest {
        val settings = Memory()
        val clock = 1_000_000L
        val live = LiveStates(
            api = api {
                """{"rev":7,"now_ms":$clock,"states":[
                   {"kind":"receipt","rev":5,"chat_id":"c1","peer_id":"p","delivered_ms":100,"read_ms":90},
                   {"kind":"typing","rev":6,"chat_id":"c1","from_id":"p","until_ms":${clock + 6_000}},
                   {"kind":"presence","rev":7,"user_id":"p","online":true,"until_ms":${clock + 60_000},"last_seen_ms":0}]}"""
            },
            settings = settings,
            scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
            now = { clock },
        )
        live.top(7)
        assertEquals(ChatReceipt(100, 90), live.receipts.value["c1"])
        assertTrue((live.typing.value["c1"] ?: 0) > clock, "печатает не легло: ${live.typing.value}")
        assertTrue(live.presence.value["p"]?.onlineAt(clock) == true, "в сети не легло")
        assertEquals("7", settings.values.value["states.rev"], "номер ленты не запомнен")
        assertEquals("100:90", settings.values.value["states.receipt.c1"], "отметки не пережили бы перезапуск")
    }

    @Test
    fun печатаю_не_чаще_раза_в_пять_секунд_и_отмена() = runTest {
        var clock = 0L
        val live = LiveStates(
            api = api { """{"rev":0,"states":[]}""" },
            settings = Memory(),
            scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
            now = { clock },
        )
        live.typed("c1", "p")
        clock = 1_000
        live.typed("c1", "p")
        clock = 5_500
        live.typed("c1", "p")
        live.stopTyping()
        val sent = generateSequence { live.frames.tryReceive().getOrNull() }.toList()
        assertEquals(
            listOf(true, true, false),
            sent.map { it.contains("\"on\":true") },
            "кадры «печатаю»: $sent",
        )
    }

    @Test
    fun прочитано_уходит_один_раз_на_новое() = runTest {
        val reads = mutableListOf<String>()
        val live = LiveStates(
            api = api(onRead = { reads += it }) { """{"rev":0,"states":[]}""" },
            settings = Memory(),
            scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
        )
        live.read("c1", 100)
        live.read("c1", 100)
        live.read("c1", 90)
        live.read("c1", 120)
        // Запрос идёт своим потоком поддельного сервера — ждём его настоящим временем.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            kotlinx.coroutines.withTimeout(5_000) { while (reads.size < 2) kotlinx.coroutines.delay(10) }
            kotlinx.coroutines.delay(100)
        }
        assertEquals(2, reads.size, "«прочитано» ушло не по разу на новое: $reads")
    }
}
