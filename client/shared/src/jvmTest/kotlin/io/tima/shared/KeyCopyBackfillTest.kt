package io.tima.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.tima.core.encryption.AccountIdentitiesOverKodium
import io.tima.core.encryption.DeviceIdentity
import io.tima.core.encryption.KeyCopy
import io.tima.core.network.HistoryApi
import io.tima.core.network.KeyCopyApi
import io.tima.core.network.KeysApi
import io.tima.core.network.RouteConfig
import io.tima.core.network.ServerRoute
import io.tima.core.network.timaDefaults
import io.tima.domain.chat.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Дозаливка копии ключей тем, что у устройства уже есть (Р44).
 *
 * 2026-10-05 на Redmi: копию завели, данные приложения стёрли, вошли по фразе — из копии
 * вернулись только два сообщения, отправленные после «Завести копию». Прежняя переписка и ключ
 * служебной группы (а с ним книга контактов) в копию не попали: пополнение кладёт только новое.
 */
class KeyCopyBackfillTest {

    private val account = AccountIdentitiesOverKodium.fresh()
    private val copy = KeyCopy.fromWords(account.words, 1)!!
    private fun b64(bytes: ByteArray) = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private val json = headersOf("Content-Type", "application/json")

    private class MemorySettings : Settings {
        val values = MutableStateFlow<Map<String, String>>(emptyMap())
        override fun all(): Flow<Map<String, String>> = values
        override suspend fun put(name: String, value: String) {
            values.value = values.value + (name to value)
        }
    }

    @Test
    fun прежние_ключи_групп_уходят_в_копию_один_раз_на_эпоху() = runTest {
        val posted = mutableListOf<String>()
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path == "/api/v1/users/me/key-copy" -> respond(
                    """{"epoch":1,"pub":"${b64(copy.encryptionPublic)}","sig":"${b64(KeyCopy.sign(account.words, 1, copy.encryptionPublic)!!)}"}""",
                    HttpStatusCode.OK, json,
                )
                path == "/api/v1/keys/devices" ->
                    respond("""{"devices":[],"identity_pub":"${b64(account.identityPub)}"}""", HttpStatusCode.OK, json)
                path == "/api/v1/users/me/key-copy/groups" && request.method == HttpMethod.Post -> {
                    posted += (request.body as TextContent).text
                    respond("", HttpStatusCode.Created)
                }
                path == "/api/v1/chats/personal" -> respond("""{"chats":[]}""", HttpStatusCode.OK, json)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = HttpClient(engine) { timaDefaults() }
        val route = ServerRoute.from(RouteConfig(host = "example.com"))
        val settings = MemorySettings()
        val groupKey = ByteArray(32) { 5 }
        val service = KeyCopyService(
            api = KeyCopyApi(route, client) { "t" },
            keys = KeysApi(route, client) { "t" },
            history = HistoryApi(route, client) { "t" },
            userId = "u-1",
            deviceId = "d-1",
            identity = DeviceIdentity.generate(),
            scope = CoroutineScope(coroutineContext),
            secrets = null,
            localGroupKeys = { listOf(Triple("g-служебная", 1, groupKey), Triple("g-служебная", 2, groupKey)) },
            settings = settings,
        )

        assertTrue(service.backfillOnce())
        assertEquals(1, posted.size, "две версии ключа — одним запросом")
        val items = Json.parseToJsonElement(posted.single()).jsonObject["items"]!!.jsonArray
        assertEquals(listOf(1, 2), items.map { it.jsonObject["gk_version"]!!.jsonPrimitive.int })
        val wrapped = java.util.Base64.getUrlDecoder().decode(items.first().jsonObject["wrapped"]!!.jsonPrimitive.content)
        assertContentEquals(groupKey, KeyCopy.openGroupKey(copy, wrapped), "ключ открывается парой копии из фразы")

        assertTrue(service.backfillOnce())
        assertEquals(1, posted.size, "под ту же эпоху второй раз не заливаем")
    }

    @Test
    fun копии_нет_дозаливки_нет() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.NotFound) }
        val client = HttpClient(engine) { timaDefaults() }
        val route = ServerRoute.from(RouteConfig(host = "example.com"))
        val settings = MemorySettings()
        val service = KeyCopyService(
            api = KeyCopyApi(route, client) { "t" },
            keys = KeysApi(route, client) { "t" },
            history = HistoryApi(route, client) { "t" },
            userId = "u-1",
            deviceId = "d-1",
            identity = DeviceIdentity.generate(),
            scope = CoroutineScope(coroutineContext),
            secrets = null,
            localGroupKeys = { error("без копии ключи не читаются") },
            settings = settings,
        )
        assertFalse(service.backfillOnce())
        assertTrue(settings.values.value.isEmpty(), "отметки нет — повтор при следующем запуске")
    }

    /**
     * Перенос копии прежней личности в копию новой (М6, Р55): ключи групп и ключи сообщений
     * её переписок разворачиваются парой из её фразы и заворачиваются под свою копию. Чужая
     * фраза ничего не переносит.
     */
    @Test
    fun копия_прежней_личности_переносится_в_свою() = runTest {
        val prior = AccountIdentitiesOverKodium.fresh()
        val priorCopy = KeyCopy.fromWords(prior.words, 3)!!
        val groupKey = ByteArray(32) { 7 }
        val postedGroups = mutableListOf<String>()
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            val owner = request.url.parameters["owner"]
            when {
                path == "/api/v1/users/me/key-copy" && owner == "u-прежняя" -> respond(
                    """{"epoch":3,"pub":"${b64(priorCopy.encryptionPublic)}","sig":"${b64(KeyCopy.sign(prior.words, 3, priorCopy.encryptionPublic)!!)}"}""",
                    HttpStatusCode.OK, json,
                )
                path == "/api/v1/users/me/key-copy" -> respond(
                    """{"epoch":1,"pub":"${b64(copy.encryptionPublic)}","sig":"${b64(KeyCopy.sign(account.words, 1, copy.encryptionPublic)!!)}"}""",
                    HttpStatusCode.OK, json,
                )
                path == "/api/v1/keys/devices" ->
                    respond("""{"devices":[],"identity_pub":"${b64(account.identityPub)}"}""", HttpStatusCode.OK, json)
                path == "/api/v1/users/me/key-copy/groups" && request.method == HttpMethod.Get && owner == "u-прежняя" -> respond(
                    """{"items":[{"group_id":"g-1","gk_version":2,"wrapped":"${b64(KeyCopy.wrapGroupKey(priorCopy.encryptionPublic, groupKey)!!)}"}]}""",
                    HttpStatusCode.OK, json,
                )
                path == "/api/v1/users/me/key-copy/groups" && request.method == HttpMethod.Post -> {
                    postedGroups += (request.body as TextContent).text
                    respond("", HttpStatusCode.Created)
                }
                path == "/api/v1/chats/personal" -> respond("""{"chats":[]}""", HttpStatusCode.OK, json)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = HttpClient(engine) { timaDefaults() }
        val route = ServerRoute.from(RouteConfig(host = "example.com"))
        val service = KeyCopyService(
            api = KeyCopyApi(route, client) { "t" },
            keys = KeysApi(route, client) { "t" },
            history = HistoryApi(route, client) { "t" },
            userId = "u-новая",
            deviceId = "d-1",
            identity = DeviceIdentity.generate(),
            scope = CoroutineScope(coroutineContext),
            secrets = null,
        )

        assertEquals(KeyCopyService.Adopt.WRONG_PHRASE, service.adoptPrior(account.words, "u-прежняя"), "фраза не прежней личности")
        assertTrue(postedGroups.isEmpty())
        assertEquals(KeyCopyService.Adopt.DONE, service.adoptPrior(prior.words, "u-прежняя"))
        val item = Json.parseToJsonElement(postedGroups.single()).jsonObject["items"]!!.jsonArray.single().jsonObject
        assertEquals(2, item["gk_version"]!!.jsonPrimitive.int)
        val wrapped = java.util.Base64.getUrlDecoder().decode(item["wrapped"]!!.jsonPrimitive.content)
        assertContentEquals(groupKey, KeyCopy.openGroupKey(copy, wrapped), "ключ прежней открывается своей копией")
    }
}
