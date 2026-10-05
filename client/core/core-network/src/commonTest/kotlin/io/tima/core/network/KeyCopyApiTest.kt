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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Копия ключей (модель Matrix, §3а) — что клиент делает с ответами сервера. «Ключа копии ещё
 * нет» и «не узнали» различаются: первое значит «опубликовать», второе — «подождать»; спутай
 * их — и устройство без сети заведёт копию заново поверх действующей.
 */
class KeyCopyApiTest {

    @Test
    fun нет_ключа_копии_и_не_узнали_это_разное() = runTest {
        assertEquals(KeyCopyApi.Current.Missing, api(HttpStatusCode.NotFound, """{"code":"no_key_copy"}""").current())
        assertEquals(KeyCopyApi.Current.Unknown, api(HttpStatusCode.InternalServerError, """{}""").current())
        val published = api(HttpStatusCode.OK, """{"epoch":2,"pub":"AQID","sig":"BAUG"}""").current()
        assertEquals(2, assertIs<KeyCopyApi.Current.Published>(published).key.epoch)
    }

    @Test
    fun страница_копии_разбирается() = runTest {
        val page = assertNotNull(
            api(HttpStatusCode.OK, """{"items":[{"message_id":5,"envelope":"AQID","wrap_ephemeral":"BAUG"},{"message_id":6}]}""")
                .page("c-1", before = 0),
        )
        assertEquals(listOf(5L), page.map { it.messageId }, "неполная строка пропускается, страница не теряется")
        assertNull(api(HttpStatusCode.Forbidden, """{"code":"not_participant"}""").page("c-1", before = 0))
    }

    @Test
    fun смена_эпохи_отличается_от_отказа() = runTest {
        assertEquals(KeyCopyApi.Saved.STALE, api(HttpStatusCode.Conflict, """{"code":"stale_epoch"}""").saveMessages("c-1", 1, listOf(1L to byteArrayOf(1))))
        assertEquals(KeyCopyApi.Saved.OK, api(HttpStatusCode.Created, """{"saved":1}""").saveMessages("c-1", 1, listOf(1L to byteArrayOf(1))))
    }

    private fun api(status: HttpStatusCode, body: String) = KeyCopyApi(
        route = ServerRoute.from(RouteConfig(host = "example.com")),
        client = HttpClient(
            MockEngine { respond(body, status, headersOf("Content-Type", ContentType.Application.Json.toString())) },
        ),
        token = { "токен" },
    )
}
