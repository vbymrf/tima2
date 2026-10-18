package io.tima.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.tima.domain.chat.OpenBody
import io.tima.domain.chat.PublishStep
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** Запись на своей странице — `POST /channels/{id}/posts` узлами и разметкой открытым текстом. */
class PagePostsOverHttpTest {

    private val route = ServerRoute.from(RouteConfig(host = "example.com"))

    @Test
    fun узлы_разметка_и_уровень_уходят_открытыми() = runTest {
        var path = ""
        var sent = ""
        val posts = PagePostsOverHttp(
            route,
            HttpClient(
                MockEngine { req ->
                    path = req.url.encodedPath
                    sent = (req.body as TextContent).text
                    respond("""{"post_id":42}""", HttpStatusCode.Created, headersOf("Content-Type", "application/json"))
                },
            ) { timaDefaults() },
            token = { "t" },
        )
        val step = posts.publish("ch-1", OpenBody("Сбор\nв 9", listOf("Сбор", "в 9"), """{"n":[1,2]}"""), 2)
        assertEquals(PublishStep.Published(42), step)
        assertEquals("/api/v1/channels/ch-1/posts", path)
        val json = Json.parseToJsonElement(sent).jsonObject
        assertEquals(listOf("Сбор", "в 9"), json["nodes"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("""{"n":[1,2]}""", json["markup"]!!.jsonPrimitive.content)
        assertEquals(2, json["level"]!!.jsonPrimitive.int)
    }

    @Test
    fun отказ_сервера_кодом() = runTest {
        val posts = PagePostsOverHttp(
            route,
            HttpClient(MockEngine { respond("""{"code":"not_owner"}""", HttpStatusCode.Forbidden, headersOf("Content-Type", "application/json")) }) { timaDefaults() },
            token = { "t" },
        )
        assertEquals(PublishStep.Refused("not_owner"), posts.publish("ch", OpenBody("x", listOf("x"), ""), 1))
    }
}
