package io.tima.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.tima.domain.chat.EntityKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Подписка на канал и название сущности для «Описания», открытого ссылкой (2026-11-25): живьём на Samsung «Подписаться»
 * у канала не слала ничего, а шапка страницы была без названия.
 */
class ChannelSubscribeOverHttpTest {

    private val route = ServerRoute.from(RouteConfig(host = "example.com"))
    private val json = headersOf("Content-Type", ContentType.Application.Json.toString())

    @Test
    fun подписка_и_отписка_идут_на_адрес_канала_нужным_методом() = runTest {
        val seen = mutableListOf<Pair<HttpMethod, String>>()
        val channels = ChannelsOverHttp(
            route,
            HttpClient(MockEngine { r ->
                seen += r.method to r.url.encodedPath
                respond("""{"subscribed":true}""", HttpStatusCode.OK, json)
            }),
            token = { "токен" },
        )
        assertTrue(channels.subscribe("c-1", true))
        assertTrue(channels.subscribe("c-1", false))
        assertEquals(
            listOf(HttpMethod.Post to "/api/v1/channels/c-1/subscribe", HttpMethod.Delete to "/api/v1/channels/c-1/subscribe"),
            seen,
        )
    }

    @Test
    fun отказ_сервера_не_считается_подпиской() = runTest {
        // Секретный канал без приглашения — 404, бан — 403: подписки нет, и клиент не делает вид, что она есть.
        val channels = ChannelsOverHttp(
            route,
            HttpClient(MockEngine { respond("""{"code":"not_found"}""", HttpStatusCode.NotFound, json) }),
            token = { "токен" },
        )
        assertFalse(channels.subscribe("c-1", true))
    }

    @Test
    fun названия_читаются_рядом_с_описаниями() = runTest {
        val invites = InvitesOverHttp(
            route,
            HttpClient(MockEngine {
                respond("""{"about":{"c-1":"про нас"},"titles":{"c-1":"trupiki"}}""", HttpStatusCode.OK, json)
            }),
            token = { "токен" },
        )
        assertEquals(mapOf("c-1" to "trupiki"), invites.titles(EntityKind.Channel, listOf("c-1")))
        assertEquals(mapOf("c-1" to "про нас"), invites.abouts(EntityKind.Channel, listOf("c-1")))
    }

    @Test
    fun сервер_без_названий_даёт_пустое_а_не_ошибку() = runTest {
        // Прежний сервер поля `titles` не знает: шапка остаётся без названия, как была, страница открывается.
        val invites = InvitesOverHttp(
            route,
            HttpClient(MockEngine { respond("""{"about":{}}""", HttpStatusCode.OK, json) }),
            token = { "токен" },
        )
        assertEquals(emptyMap(), invites.titles(EntityKind.Channel, listOf("c-1")))
    }
}
