package io.tima.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.errors.IOException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ключи устройств и ключ эпохи escrow. Формы ответов — из `internal/api/auth.go` и
 * `internal/api/escrow.go` дословно.
 */
class KeysAndEscrowApiTest {

    private val route = ServerRoute.from(RouteConfig(host = "example.com"))
    private lateinit var engine: MockEngine

    private fun json(body: String, status: HttpStatusCode = HttpStatusCode.OK): MockRequestHandler =
        { respond(body, status, headersOf("Content-Type", "application/json")) }

    private fun keys(responds: MockRequestHandler): KeysApi {
        engine = MockEngine(responds)
        return KeysApi(route, HttpClient(engine) { timaDefaults() }, token = { "токен" })
    }

    private fun escrow(responds: MockRequestHandler): EscrowApi {
        engine = MockEngine(responds)
        return EscrowApi(route, HttpClient(engine) { timaDefaults() }, token = { "токен" })
    }

    /** base64url без выравнивания — так кодирует сервер. */
    private fun b64url(bytes: ByteArray): String = encodeBase64Url(bytes)

    // ── ключи устройств ──────────────────────────────────────────────────────

    @Test
    fun список_устройств_разбирается_и_ключи_декодируются() = runTest {
        val enc = ByteArray(32) { 1 }
        val sig = ByteArray(32) { 2 }
        val api = keys(json(
            """{"user_id":"u-1","devices":[
                {"device_id":"d-1","encryption_pub":"${b64url(enc)}","signing_pub":"${b64url(sig)}"},
                {"device_id":"d-2","encryption_pub":"${b64url(enc)}","signing_pub":"${b64url(sig)}"}]}"""
        ))

        val outcome = api.devicesOf("u-1")

        assertIs<DeviceKeysResult.Devices>(outcome)
        assertEquals(listOf("d-1", "d-2"), outcome.devices.map { it.deviceId })
        assertEquals(32, outcome.devices[0].encryptionPub.size)
        assertEquals(
            "https://api.example.com/api/v1/keys/devices?user_id=u-1",
            engine.requestHistory.single().url.toString(),
        )
        assertEquals("Bearer токен", engine.requestHistory.single().headers["Authorization"])
    }

    @Test
    fun empty_list_devices_not_error() = runTest {
        // У аккаунта могли отозвать все устройства. Отправлять некому, и решать это выше:
        // сетевой слой не должен превращать это в отказ.
        val outcome = keys(json("""{"user_id":"u-1","devices":[]}""")).devicesOf("u-1")
        assertIs<DeviceKeysResult.Devices>(outcome)
        assertTrue(outcome.devices.isEmpty())
    }

    @Test
    fun ключ_не_того_размера_это_отказ_а_не_молчаливый_пропуск() = runTest {
        // Пропустить устройство с испорченным ключом значило бы отправить сообщение,
        // которое это устройство никогда не прочитает, — и не сказать об этом никому.
        val api = keys(json(
            """{"user_id":"u-1","devices":[{"device_id":"d-1","encryption_pub":"${b64url(ByteArray(16))}","signing_pub":"${b64url(ByteArray(32))}"}]}"""
        ))
        val outcome = api.devicesOf("u-1")

        assertIs<DeviceKeysResult.Refused>(outcome)
        assertTrue(outcome.code.contains("32"), "причина обязана называть размер: " + outcome.code)
    }

    @Test
    fun обрыв_и_отказ_различаются() = runTest {
        val breakage = keys { throw IOException("нет сети") }.devicesOf("u-1")
        assertIs<DeviceKeysResult.Offline>(breakage)

        val refusal = keys(json("""{"code":"unauthorized"}""", HttpStatusCode.Unauthorized)).devicesOf("u-1")
        assertIs<DeviceKeysResult.Refused>(refusal)
        assertEquals("unauthorized", refusal.code)
    }

    // ── ключ эпохи escrow ────────────────────────────────────────────────────

    private val epochKey = ByteArray(1184) { (it % 251).toByte() }
    private val caption = ByteArray(64) { 7 }

    private fun epochAnswer(withNext: Boolean = false): String {
        val current = """{"id":7,"epoch":"2026-08","public_key":"${b64url(epochKey)}",
            "signature":"${b64url(caption)}","valid_from":"2026-08-01T00:00:00Z",
            "valid_to":"2026-09-01T00:00:00Z","destroy_at":"2027-03-01T00:00:00.123456789Z"}"""
        return if (withNext) {
            """{"region":"ru","current":$current,"next":$current}"""
        } else {
            """{"region":"ru","current":$current}"""
        }
    }

    @Test
    fun ключ_эпохи_разбирается_вместе_с_тем_что_входит_в_подпись() = runTest {
        // region лежит НАРУЖИ ключа, а chat_id не приходит вовсе — и то и другое входит в
        // подписываемые байты. Собрать их обязан клиент, иначе подпись не сойдётся, а
        // выглядеть это будет как «анклав подписывает неправильно».
        val outcome = escrow(json(epochAnswer())).keyForChat("chat-1")

        assertIs<EscrowKeyResult.Keys>(outcome)
        val key = outcome.current
        assertEquals(7, key.id)
        assertEquals("ru", key.region, "region подхвачен с верхнего уровня")
        assertEquals("chat-1", key.chatId, "chat_id взят из своего же запроса")
        assertEquals("2026-08", key.epoch)
        assertEquals(1184, key.publicKey.size)
        assertEquals(64, key.signature.size)
        assertNull(outcome.next)
    }

    @Test
    fun время_переводится_усечением_а_не_округлением() = runTest {
        // Анклав считал миллисекунды усечением (Go UnixMilli). Округли вверх — и
        // подписываемые байты разойдутся на единицу, а подпись не сойдётся.
        val outcome = escrow(json(epochAnswer())).keyForChat("chat-1")
        assertIs<EscrowKeyResult.Keys>(outcome)

        assertEquals(1_785_542_400_000, outcome.current.validFromMs, "2026-08-01T00:00:00Z")
        // .123456789 обязаны стать 123, а не 124.
        assertTrue(
            outcome.current.destroyAtMs % 1000 == 123L,
            "ожидалось усечение до 123 мс, получено ${outcome.current.destroyAtMs % 1000}",
        )
    }

    @Test
    fun следующая_эпоха_подхватывается_если_сервер_её_знает() = runTest {
        val outcome = escrow(json(epochAnswer(withNext = true))).keyForChat("chat-1")
        assertIs<EscrowKeyResult.Keys>(outcome)
        assertEquals(7, outcome.next?.id)
    }

    @Test
    fun отсутствие_анклава_отдельный_исход_а_не_отказ() = runTest {
        // Без анклава отправка невозможна в принципе, и человеку надо сказать не «нет
        // сети», а другое. 503 no_escrow — это состояние сервера, а не наша беда.
        val outcome = escrow(json(
            """{"code":"no_escrow","message":"escrow-анклав не сконфигурирован"}""",
            HttpStatusCode.ServiceUnavailable,
        )).keyForChat("chat-1")
        assertEquals(EscrowKeyResult.NoEnclave, outcome)
    }

    @Test
    fun недоступный_анклав_это_отказ_с_кодом() = runTest {
        val outcome = escrow(json(
            """{"code":"escrow_unreachable"}""", HttpStatusCode.BadGateway,
        )).keyForChat("chat-1")
        assertIs<EscrowKeyResult.Refused>(outcome)
        assertEquals("escrow_unreachable", outcome.code)
    }

    @Test
    fun испорченный_ответ_не_роняет_отправку() = runTest {
        for (garbage in listOf("{}", """{"region":"ru"}""", """{"region":"ru","current":{}}""", "не json")) {
            val outcome = escrow(json(garbage)).keyForChat("chat-1")
            assertIs<EscrowKeyResult.Refused>(outcome, "вход «$garbage» обязан быть отказом")
        }
    }

    @Test
    fun пустой_чат_отвергается_до_сети() = runTest {
        val api = escrow(json(epochAnswer()))
        assertFailsWith<IllegalArgumentException> { api.keyForChat("") }
        assertEquals(0, engine.requestHistory.size)
    }
}
