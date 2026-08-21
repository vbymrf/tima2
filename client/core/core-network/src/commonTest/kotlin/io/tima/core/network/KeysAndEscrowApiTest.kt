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

    private fun json(тело: String, статус: HttpStatusCode = HttpStatusCode.OK): MockRequestHandler =
        { respond(тело, статус, headersOf("Content-Type", "application/json")) }

    private fun keys(отвечает: MockRequestHandler): KeysApi {
        engine = MockEngine(отвечает)
        return KeysApi(route, HttpClient(engine) { timaDefaults() }, token = { "токен" })
    }

    private fun escrow(отвечает: MockRequestHandler): EscrowApi {
        engine = MockEngine(отвечает)
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

        val исход = api.devicesOf("u-1")

        assertIs<DeviceKeysResult.Devices>(исход)
        assertEquals(listOf("d-1", "d-2"), исход.devices.map { it.deviceId })
        assertEquals(32, исход.devices[0].encryptionPub.size)
        assertEquals(
            "https://api.example.com/api/v1/keys/devices?user_id=u-1",
            engine.requestHistory.single().url.toString(),
        )
        assertEquals("Bearer токен", engine.requestHistory.single().headers["Authorization"])
    }

    @Test
    fun пустой_список_устройств_не_ошибка() = runTest {
        // У аккаунта могли отозвать все устройства. Отправлять некому, и решать это выше:
        // сетевой слой не должен превращать это в отказ.
        val исход = keys(json("""{"user_id":"u-1","devices":[]}""")).devicesOf("u-1")
        assertIs<DeviceKeysResult.Devices>(исход)
        assertTrue(исход.devices.isEmpty())
    }

    @Test
    fun ключ_не_того_размера_это_отказ_а_не_молчаливый_пропуск() = runTest {
        // Пропустить устройство с испорченным ключом значило бы отправить сообщение,
        // которое это устройство никогда не прочитает, — и не сказать об этом никому.
        val api = keys(json(
            """{"user_id":"u-1","devices":[{"device_id":"d-1","encryption_pub":"${b64url(ByteArray(16))}","signing_pub":"${b64url(ByteArray(32))}"}]}"""
        ))
        val исход = api.devicesOf("u-1")

        assertIs<DeviceKeysResult.Refused>(исход)
        assertTrue(исход.code.contains("32"), "причина обязана называть размер: " + исход.code)
    }

    @Test
    fun обрыв_и_отказ_различаются() = runTest {
        val обрыв = keys { throw IOException("нет сети") }.devicesOf("u-1")
        assertIs<DeviceKeysResult.Offline>(обрыв)

        val отказ = keys(json("""{"code":"unauthorized"}""", HttpStatusCode.Unauthorized)).devicesOf("u-1")
        assertIs<DeviceKeysResult.Refused>(отказ)
        assertEquals("unauthorized", отказ.code)
    }

    // ── ключ эпохи escrow ────────────────────────────────────────────────────

    private val ключЭпохи = ByteArray(1184) { (it % 251).toByte() }
    private val подпись = ByteArray(64) { 7 }

    private fun ответЭпохи(сNext: Boolean = false): String {
        val текущий = """{"id":7,"epoch":"2026-08","public_key":"${b64url(ключЭпохи)}",
            "signature":"${b64url(подпись)}","valid_from":"2026-08-01T00:00:00Z",
            "valid_to":"2026-09-01T00:00:00Z","destroy_at":"2027-03-01T00:00:00.123456789Z"}"""
        return if (сNext) {
            """{"region":"ru","current":$текущий,"next":$текущий}"""
        } else {
            """{"region":"ru","current":$текущий}"""
        }
    }

    @Test
    fun ключ_эпохи_разбирается_вместе_с_тем_что_входит_в_подпись() = runTest {
        // region лежит НАРУЖИ ключа, а chat_id не приходит вовсе — и то и другое входит в
        // подписываемые байты. Собрать их обязан клиент, иначе подпись не сойдётся, а
        // выглядеть это будет как «анклав подписывает неправильно».
        val исход = escrow(json(ответЭпохи())).keyForChat("chat-1")

        assertIs<EscrowKeyResult.Keys>(исход)
        val ключ = исход.current
        assertEquals(7, ключ.id)
        assertEquals("ru", ключ.region, "region подхвачен с верхнего уровня")
        assertEquals("chat-1", ключ.chatId, "chat_id взят из своего же запроса")
        assertEquals("2026-08", ключ.epoch)
        assertEquals(1184, ключ.publicKey.size)
        assertEquals(64, ключ.signature.size)
        assertNull(исход.next)
    }

    @Test
    fun время_переводится_усечением_а_не_округлением() = runTest {
        // Анклав считал миллисекунды усечением (Go UnixMilli). Округли вверх — и
        // подписываемые байты разойдутся на единицу, а подпись не сойдётся.
        val исход = escrow(json(ответЭпохи())).keyForChat("chat-1")
        assertIs<EscrowKeyResult.Keys>(исход)

        assertEquals(1_785_542_400_000, исход.current.validFromMs, "2026-08-01T00:00:00Z")
        // .123456789 обязаны стать 123, а не 124.
        assertTrue(
            исход.current.destroyAtMs % 1000 == 123L,
            "ожидалось усечение до 123 мс, получено ${исход.current.destroyAtMs % 1000}",
        )
    }

    @Test
    fun следующая_эпоха_подхватывается_если_сервер_её_знает() = runTest {
        val исход = escrow(json(ответЭпохи(сNext = true))).keyForChat("chat-1")
        assertIs<EscrowKeyResult.Keys>(исход)
        assertEquals(7, исход.next?.id)
    }

    @Test
    fun отсутствие_анклава_отдельный_исход_а_не_отказ() = runTest {
        // Без анклава отправка невозможна в принципе, и человеку надо сказать не «нет
        // сети», а другое. 503 no_escrow — это состояние сервера, а не наша беда.
        val исход = escrow(json(
            """{"code":"no_escrow","message":"escrow-анклав не сконфигурирован"}""",
            HttpStatusCode.ServiceUnavailable,
        )).keyForChat("chat-1")
        assertEquals(EscrowKeyResult.NoEnclave, исход)
    }

    @Test
    fun недоступный_анклав_это_отказ_с_кодом() = runTest {
        val исход = escrow(json(
            """{"code":"escrow_unreachable"}""", HttpStatusCode.BadGateway,
        )).keyForChat("chat-1")
        assertIs<EscrowKeyResult.Refused>(исход)
        assertEquals("escrow_unreachable", исход.code)
    }

    @Test
    fun испорченный_ответ_не_роняет_отправку() = runTest {
        for (мусор in listOf("{}", """{"region":"ru"}""", """{"region":"ru","current":{}}""", "не json")) {
            val исход = escrow(json(мусор)).keyForChat("chat-1")
            assertIs<EscrowKeyResult.Refused>(исход, "вход «$мусор» обязан быть отказом")
        }
    }

    @Test
    fun пустой_чат_отвергается_до_сети() = runTest {
        val api = escrow(json(ответЭпохи()))
        assertFailsWith<IllegalArgumentException> { api.keyForChat("") }
        assertEquals(0, engine.requestHistory.size)
    }
}
