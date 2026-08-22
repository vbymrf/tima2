package io.tima.harness

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.tima.core.database.SqlInboxStore
import io.tima.core.database.TimaDatabase
import io.tima.core.encryption.DeviceIdentity
import io.tima.core.encryption.DeviceKeyFactoryOverKodium
import io.tima.core.encryption.EscrowKeyVerifier
import io.tima.core.encryption.EscrowTrust
import io.tima.core.encryption.OutgoingSealer
import io.tima.core.encryption.PersonalMessages
import io.tima.core.encryption.RecipientDevice
import io.tima.core.encryption.deviceIdentityFrom
import io.tima.core.network.AuthApi
import io.tima.core.network.DeviceKeysResult
import io.tima.core.network.EscrowApi
import io.tima.core.network.EscrowKeyResult
import io.tima.core.network.EventStream
import io.tima.core.network.HttpMessageTransport
import io.tima.core.network.KeysApi
import io.tima.core.network.RegisterResult
import io.tima.core.network.RouteConfig
import io.tima.core.network.ServerRoute
import io.tima.core.network.SmsRequestResult
import io.tima.core.network.SmsVerifyResult
import io.tima.core.network.httpEngine
import io.tima.core.network.timaDefaults
import io.tima.core.outbox.Inbox
import io.tima.core.outbox.IncomingState
import io.tima.core.outbox.OpenOutcome
import io.tima.core.secrets.AndroidSecrets
import io.tima.core.secrets.Secrets
import io.tima.core.secrets.platformVault
import io.tima.crypto.PersonalChatId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * **Первый признак готовности К4 для Android: сообщение уходит и приходит на телефоне
 * против живого сервера.**
 *
 * Это не проверка в обычном смысле — это прогон по стенду с настоящими аккаунтами,
 * поэтому он живёт в инструментальном наборе и не участвует ни в сборке, ни в CI:
 * запускается руками, когда телефон подключён.
 *
 * **Почему одного телефона достаточно.** Оба участника переписки — это две личности
 * устройства в одном процессе, ровно как в прогоне на ПК (`StandRun`). Второй телефон
 * понадобится тогда, когда проверять придётся то, чего в одном процессе не бывает:
 * доставку на спящее устройство, push, две разные версии приложения.
 *
 * **Что здесь настоящее, в отличие от прогона на ПК:** движок сети Android (OkHttp),
 * драйвер базы Android (`AndroidSqliteDriver` с настройками соединения), хранилище
 * секретов Android (AndroidKeyStore). То есть проверяется не «наш код вообще», а наш
 * код **на этой платформе**.
 */
class AndroidStandTest {

    private val метка = "СТЕНД"
    private val контекст = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var client: HttpClient
    private val шаги = StringBuilder()

    @BeforeTest
    fun подготовить() {
        AndroidHarness.install(контекст)
        AndroidSecrets.install(контекст)
        client = HttpClient(httpEngine()) {
            timaDefaults()
            install(WebSockets)
        }
    }

    @AfterTest
    fun убрать() {
        client.close()
        // Секрет устройства за собой чистим: телефон не наш, и оставлять на нём ключи от
        // тестовых аккаунтов незачем.
        runCatching { platformVault("android-stand-test").remove(Secrets.DEVICE_SECRET) }
        Log.i(метка, шаги.toString())
    }

    private fun шаг(текст: String) {
        шаги.appendLine(текст)
        Log.i(метка, текст)
    }

    @Test
    fun сообщение_уходит_и_приходит_на_телефоне() = runBlocking {
        val host = "xn--80aa4ar0b.xn--p1ai"
        val route = ServerRoute.from(RouteConfig(host = host))
        шаг("маршрут: ${route.apiBase}, WS ${route.wsUrl}")

        val анклав = EscrowTrust.enclaveSigningPub
        assertTrue(анклав != null, "ключ подписи анклава обязан быть зашит в сборку")
        assertEquals(32, анклав.size)

        // ── два устройства ───────────────────────────────────────────────────
        val метка = System.currentTimeMillis() % 100_000_000
        val А = завести(route, "+797${метка.toString().padStart(8, '0')}", "А")
        val Б = завести(route, "+796${метка.toString().padStart(8, '0')}", "Б")

        // Секрет устройства А проходит через настоящее хранилище платформы: на этом
        // телефоне это AndroidKeyStore, и путь обязан работать целиком.
        val хранилище = platformVault("android-stand-test")
        хранилище.put(Secrets.DEVICE_SECRET, А.secret)
        assertContentEquals(
            А.secret,
            хранилище.get(Secrets.DEVICE_SECRET),
            "секрет устройства обязан читаться из хранилища платформы обратно",
        )
        шаг("секрет устройства прошёл через AndroidKeyStore")

        val chatId = PersonalChatId.of(А.userId, Б.userId)
        шаг("chat_id: $chatId")

        // ── ключи собеседника и ключ эпохи ───────────────────────────────────
        val ключиБ = KeysApi(route, client, token = { А.accessToken }).devicesOf(Б.userId)
        assertIs<DeviceKeysResult.Devices>(ключиБ)
        assertTrue(ключиБ.devices.isNotEmpty(), "у собеседника обязано быть устройство")
        шаг("ключи устройств Б: ${ключиБ.devices.size}")

        val эпоха = EscrowApi(route, client, token = { А.accessToken }).keyForChat(chatId)
        assertIs<EscrowKeyResult.Keys>(эпоха)
        val подписанный = эпоха.current
        val проверенный = EscrowKeyVerifier.verify(
            enclaveSigningPub = анклав,
            id = подписанный.id,
            region = подписанный.region,
            chatId = подписанный.chatId,
            epoch = подписанный.epoch,
            publicKey = подписанный.publicKey,
            signature = подписанный.signature,
            validFromMs = подписанный.validFromMs,
            validToMs = подписанный.validToMs,
            destroyAtMs = подписанный.destroyAtMs,
            nowMs = System.currentTimeMillis(),
        ).getOrThrow()
        шаг("подпись анклава сошлась, эпоха ${подписанный.epoch}")

        // ── отправка через настоящую очередь на базе Android ──────────────────
        val sealer = OutgoingSealer(А.userId, А.deviceId, А.identity).sealerFor(
            escrowKey = проверенный,
            recipients = ключиБ.devices.map { RecipientDevice(it.deviceId, it.encryptionPub) },
        )
        val harness = ChatHarness(harnessDriver(), sealWith = sealer)
        val текст = "Сообщение с телефона, ${System.currentTimeMillis()}"
        harness.send(chatId, текст)

        val transport = HttpMessageTransport(route, client, token = { А.accessToken })
        val исходов = harness.pumpVia { готовое ->
            val исход = transport.send(готовое.entry.dedupKey, готовое.envelope)
            шаг("исход отправки: $исход, конверт ${готовое.envelope.size} байт")
            исход
        }
        assertEquals(1, исходов)
        assertTrue(harness.pending().isEmpty(), "сообщение не ушло: ${harness.pending()}")

        // ── приём вторым устройством по живому каналу ─────────────────────────
        val шифрБ = харнессШифр()
        val базаБ = TimaDatabase(harnessDriver())
        val inbox = Inbox(
            SqlInboxStore(базаБ, шифрБ),
            nowMs = { System.currentTimeMillis() },
        )
        // Ждём ДО первого дошедшего сообщения, а не до конца тайм-аута: живой канал
        // сам не закрывается, и «подожди минуту на всякий случай» превращает проверку
        // в ту, которую однажды выключат за медлительность.
        val дошло = CompletableDeferred<String>()
        val канал = launch {
            EventStream(route, client, token = { Б.accessToken }).run(cursor = null) { событие ->
                inbox.receive(событие.chatId, событие.messageId, событие.envelope)
                inbox.openNext(
                    open = { запись ->
                        PersonalMessages.open(
                            envelopeBytes = запись.envelope,
                            myDeviceId = Б.deviceId,
                            me = Б.identity,
                            senderSigningPublic = А.identity.signingPublic,
                        ).fold(
                            onSuccess = { OpenOutcome.Opened(it.content.plainText().encodeToByteArray()) },
                            onFailure = { OpenOutcome.NoKey(it.message ?: "не открылось") },
                        )
                    },
                )?.let { запись ->
                    // Текст берётся ИЗ БАЗЫ УСТРОЙСТВА: прогон на телефоне обязан
                    // доказать, что сообщение доехало туда, откуда его читает экран.
                    if (запись.state == IncomingState.STORED) {
                        val строка = базаБ.messagesQueries
                            .byDedupKey("${запись.chatId}/${запись.messageId}")
                            .executeAsOneOrNull()
                        val тело = строка?.body_enc?.let { шифрБ.open(it) }
                        if (тело != null) дошло.complete(тело.decodeToString())
                    }
                }
            }
        }
        val прочитано = withTimeoutOrNull(60_000) { дошло.await() }
        канал.cancel()

        assertEquals(текст, прочитано, "до второго устройства обязан дойти тот же текст")
        шаг("ПРИНЯТО на телефоне: текст сошёлся")
    }

    // ── регистрация ──────────────────────────────────────────────────────────

    private class Заведённое(
        val userId: String,
        val deviceId: String,
        val accessToken: String,
        val identity: DeviceIdentity,
        val secret: ByteArray,
    )

    private suspend fun завести(route: ServerRoute, phone: String, имя: String): Заведённое {
        val auth = AuthApi(route, client)

        val запрос = auth.requestSms(phone)
        assertIs<SmsRequestResult.Sent>(запрос, "$имя: запрос кода не прошёл: $запрос")
        val код = запрос.devCode
        assertTrue(код != null, "$имя: сервер не отдал dev_code — TIMA_DEV_SMS выключен?")

        val проверка = auth.verifySms(запрос.requestId, код)
        assertIs<SmsVerifyResult.Verified>(проверка, "$имя: код не принят: $проверка")

        val материал = DeviceKeyFactoryOverKodium.newDeviceKeys()
        val ответ = auth.register(
            registrationToken = проверка.registrationToken,
            encryptionPub = материал.encryptionPub,
            signingPub = материал.signingPub,
            platform = "android-харнесс",
        )
        assertIs<RegisterResult.Registered>(ответ, "$имя: заведение не прошло: $ответ")
        шаг("$имя заведено: user=${ответ.userId} device=${ответ.deviceId}")

        return Заведённое(
            userId = ответ.userId,
            deviceId = ответ.deviceId,
            accessToken = ответ.accessToken,
            identity = deviceIdentityFrom(материал.secret),
            secret = материал.secret,
        )
    }
}
