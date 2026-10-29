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

    /** Ключ копии из своего источника (КК8): без него устройство копию не пополняет. */
    private fun anchored(): MemorySettings = MemorySettings().apply {
        values.value = mapOf(
            "key-copy.anchor.v1" to io.tima.core.encryption.CopyAnchor(
                account.identityPub, 1, copy.encryptionPublic, KeyCopy.sign(account.words, 1, copy.encryptionPublic)!!,
            ).encode(),
        )
    }

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
        val settings = anchored()
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
     * План КК, КК8: сервер отдаёт свою пару «ключ личности + ключ копии» с сошедшейся подписью — устройство без
     * своего источника под неё ничего не заворачивает.
     */
    @Test
    fun пара_от_сервера_без_своего_источника_не_принимается() = runTest {
        val forged = AccountIdentitiesOverKodium.fresh()
        val forgedCopy = KeyCopy.fromWords(forged.words, 1)!!
        val posted = mutableListOf<String>()
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path == "/api/v1/users/me/key-copy" -> respond(
                    """{"epoch":1,"pub":"${b64(forgedCopy.encryptionPublic)}","sig":"${b64(KeyCopy.sign(forged.words, 1, forgedCopy.encryptionPublic)!!)}"}""",
                    HttpStatusCode.OK, json,
                )
                path == "/api/v1/keys/devices" ->
                    respond("""{"devices":[],"identity_pub":"${b64(forged.identityPub)}"}""", HttpStatusCode.OK, json)
                request.method == HttpMethod.Post -> {
                    posted += path
                    respond("", HttpStatusCode.Created)
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = HttpClient(engine) { timaDefaults() }
        val route = ServerRoute.from(RouteConfig(host = "example.com"))
        val service = KeyCopyService(
            api = KeyCopyApi(route, client) { "t" },
            keys = KeysApi(route, client) { "t" },
            history = HistoryApi(route, client) { "t" },
            userId = "u-1",
            deviceId = "d-1",
            identity = DeviceIdentity.generate(),
            scope = CoroutineScope(coroutineContext),
            secrets = null,
            localGroupKeys = { listOf(Triple("g-1", 1, ByteArray(32) { 5 })) },
            settings = MemorySettings(),
        )
        assertFalse(service.backfillOnce())
        service.feedGroupKey("g-1", 2, ByteArray(32) { 6 })
        testScheduler.advanceUntilIdle()
        assertTrue(posted.isEmpty(), "под пару от сервера завёрнуто: $posted")
    }

    /**
     * План КК, КК11: фраза больше не оставляет секрет копии на устройстве; сохранённый раньше стирается, когда
     * ключ копии запомнен и ключи прочитанного собраны.
     */
    @Test
    fun секрет_копии_не_хранится_и_старый_стирается() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/v1/users/me/key-copy" -> respond(
                    """{"epoch":1,"pub":"${b64(copy.encryptionPublic)}","sig":"${b64(KeyCopy.sign(account.words, 1, copy.encryptionPublic)!!)}"}""",
                    HttpStatusCode.OK, json,
                )
                "/api/v1/keys/devices" -> respond("""{"devices":[],"identity_pub":"${b64(account.identityPub)}"}""", HttpStatusCode.OK, json)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = HttpClient(engine) { timaDefaults() }
        val route = ServerRoute.from(RouteConfig(host = "example.com"))
        val vault = object : KeyCopySecrets {
            var held: Pair<Int, ByteArray>? = 1 to copy.exportRaw()
            override fun get() = held
            override fun put(epoch: Int, secret: ByteArray) { held = epoch to secret }
            override fun clear() { held = null }
        }
        val settings = MemorySettings()
        val service = KeyCopyService(
            api = KeyCopyApi(route, client) { "t" },
            keys = KeysApi(route, client) { "t" },
            history = HistoryApi(route, client) { "t" },
            userId = "u-1",
            deviceId = "d-1",
            identity = DeviceIdentity.generate(),
            scope = CoroutineScope(coroutineContext),
            secrets = vault,
            settings = settings,
        )
        assertEquals(null, service.copyIdentity(), "сохранённый секрет больше не открывает копию")
        assertFalse(service.dropStoredSecret(harvested = false), "ключи прочитанного не собраны — рано")
        assertTrue(vault.held != null)
        assertTrue(service.dropStoredSecret(harvested = true))
        assertEquals(null, vault.held, "секрет не стёрт")
        assertTrue(service.anchored(), "ключ копии запомнен до стирания")
        // Чужая фраза (КК7): копию не трогает и не открывает.
        val strangerService = KeyCopyService(
            api = KeyCopyApi(route, client) { "t" },
            keys = KeysApi(route, client) { "t" },
            history = HistoryApi(route, client) { "t" },
            userId = "u-1",
            deviceId = "d-2",
            identity = DeviceIdentity.generate(),
            scope = CoroutineScope(coroutineContext),
            secrets = null,
            settings = MemorySettings(),
        )
        strangerService.onPhrase(AccountIdentitiesOverKodium.fresh().words)
        assertFalse(strangerService.anchored(), "чужая фраза запомнена как ключ копии")
        assertEquals(null, strangerService.copyIdentity())
        service.onPhrase(account.words)
        assertEquals(null, vault.held, "фраза снова сохранила секрет")
        assertTrue(service.copyIdentity() != null, "фраза этого запуска открывает копию")
    }

    /**
     * План КК, КК8 и КК9: ключ копии пришёл книгой аккаунта — устройство дозаливает из своих ключей сообщений,
     * а не из обёрток на сервере.
     */
    @Test
    fun ключ_копии_из_книги_и_дозаливка_из_своих_ключей() = runTest {
        val posted = mutableListOf<String>()
        val messageKey = ByteArray(32) { 9 }
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path == "/api/v1/users/me/key-copy" -> respond(
                    """{"epoch":1,"pub":"${b64(copy.encryptionPublic)}","sig":"${b64(KeyCopy.sign(account.words, 1, copy.encryptionPublic)!!)}"}""",
                    HttpStatusCode.OK, json,
                )
                path == "/api/v1/keys/devices" ->
                    respond("""{"devices":[],"identity_pub":"${b64(account.identityPub)}"}""", HttpStatusCode.OK, json)
                request.method == HttpMethod.Post && path.endsWith("/backup") -> {
                    posted += (request.body as TextContent).text
                    respond("", HttpStatusCode.Created)
                }
                request.method == HttpMethod.Post -> respond("", HttpStatusCode.Created)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
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
            settings = settings,
            localMessageKeys = { mapOf("c-1" to mapOf(42L to messageKey)) },
        )
        assertFalse(service.anchored())
        service.book = object : CopyAnchorBook {
            override suspend fun read() = io.tima.core.encryption.CopyAnchor(
                account.identityPub, 1, copy.encryptionPublic, KeyCopy.sign(account.words, 1, copy.encryptionPublic)!!,
            )
            override suspend fun write(anchor: io.tima.core.encryption.CopyAnchor) = true
        }
        assertTrue(service.backfillOnce())
        assertTrue(service.anchored(), "ключ из книги запомнен")
        val item = Json.parseToJsonElement(posted.single()).jsonObject["items"]!!.jsonArray.single().jsonObject
        val wrapped = java.util.Base64.getUrlDecoder().decode(item["wrapped"]!!.jsonPrimitive.content)
        assertContentEquals(messageKey, KeyCopy.openGroupKey(copy, wrapped), "ключ сообщения открывается парой копии")
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
            settings = anchored(),
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
