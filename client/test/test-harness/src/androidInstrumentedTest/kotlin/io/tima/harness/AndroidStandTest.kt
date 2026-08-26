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
import io.tima.core.encryption.TextBodyCodec
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

    private val label = "СТЕНД"
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var client: HttpClient
    private val steps = StringBuilder()

    @BeforeTest
    fun prepare() {
        AndroidHarness.install(context)
        AndroidSecrets.install(context)
        client = HttpClient(httpEngine()) {
            timaDefaults()
            install(WebSockets)
        }
    }

    @AfterTest
    fun remove() {
        client.close()
        // Секрет устройства за собой чистим: телефон не наш, и оставлять на нём ключи от
        // тестовых аккаунтов незачем.
        runCatching { platformVault("android-stand-test").remove(Secrets.DEVICE_SECRET) }
        Log.i(label, steps.toString())
    }

    private fun step(text: String) {
        steps.appendLine(text)
        Log.i(label, text)
    }

    @Test
    fun сообщение_уходит_и_приходит_на_телефоне() = runBlocking {
        val host = "xn--80aa4ar0b.xn--p1ai"
        val route = ServerRoute.from(RouteConfig(host = host))
        step("маршрут: ${route.apiBase}, WS ${route.wsUrl}")

        val enclave = EscrowTrust.enclaveSigningPub
        assertTrue(enclave != null, "ключ подписи анклава обязан быть зашит в сборку")
        assertEquals(32, enclave.size)

        // ── два устройства ───────────────────────────────────────────────────
        val label = System.currentTimeMillis() % 100_000_000
        val A = create(route, "+797${label.toString().padStart(8, '0')}", "А")
        val B = create(route, "+796${label.toString().padStart(8, '0')}", "Б")

        // Секрет устройства А проходит через настоящее хранилище платформы: на этом
        // телефоне это AndroidKeyStore, и путь обязан работать целиком.
        val store = platformVault("android-stand-test")
        store.put(Secrets.DEVICE_SECRET, A.secret)
        assertContentEquals(
            A.secret,
            store.get(Secrets.DEVICE_SECRET),
            "секрет устройства обязан читаться из хранилища платформы обратно",
        )
        step("секрет устройства прошёл через AndroidKeyStore")

        val chatId = PersonalChatId.of(A.userId, B.userId)
        step("chat_id: $chatId")

        // ── ключи собеседника и ключ эпохи ───────────────────────────────────
        val bKeys = KeysApi(route, client, token = { A.accessToken }).devicesOf(B.userId)
        assertIs<DeviceKeysResult.Devices>(bKeys)
        assertTrue(bKeys.devices.isNotEmpty(), "у собеседника обязано быть устройство")
        step("ключи устройств Б: ${bKeys.devices.size}")

        val epoch = EscrowApi(route, client, token = { A.accessToken }).keyForChat(chatId)
        assertIs<EscrowKeyResult.Keys>(epoch)
        val signed = epoch.current
        val verified = EscrowKeyVerifier.verify(
            enclaveSigningPub = enclave,
            id = signed.id,
            region = signed.region,
            chatId = signed.chatId,
            epoch = signed.epoch,
            publicKey = signed.publicKey,
            signature = signed.signature,
            validFromMs = signed.validFromMs,
            validToMs = signed.validToMs,
            destroyAtMs = signed.destroyAtMs,
            nowMs = System.currentTimeMillis(),
        ).getOrThrow()
        step("подпись анклава сошлась, эпоха ${signed.epoch}")

        // ── отправка через настоящую очередь на базе Android ──────────────────
        val sealer = OutgoingSealer(A.userId, A.deviceId, A.identity).sealerFor(
            escrowKey = verified,
            recipients = bKeys.devices.map { RecipientDevice(it.deviceId, it.encryptionPub) },
        )
        val harness = ChatHarness(harnessDriver(), sealWith = sealer)
        val text = "Сообщение с телефона, ${System.currentTimeMillis()}"
        harness.send(chatId, text)

        val transport = HttpMessageTransport(route, client, token = { A.accessToken })
        val outcomes = harness.pumpVia { ready ->
            val outcome = transport.send(ready.entry.dedupKey, ready.envelope)
            step("исход отправки: $outcome, конверт ${ready.envelope.size} байт")
            outcome
        }
        assertEquals(1, outcomes)
        assertTrue(harness.pending().isEmpty(), "сообщение не ушло: ${harness.pending()}")

        // ── приём вторым устройством по живому каналу ─────────────────────────
        val bCipher = cipherHarness()
        val bDatabase = TimaDatabase(harnessDriver())
        val inbox = Inbox(
            SqlInboxStore(bDatabase, bCipher),
            nowMs = { System.currentTimeMillis() },
        )
        // Ждём ДО первого дошедшего сообщения, а не до конца тайм-аута: живой канал
        // сам не закрывается, и «подожди минуту на всякий случай» превращает проверку
        // в ту, которую однажды выключат за медлительность.
        val delivered = CompletableDeferred<String>()
        val channel = launch {
            EventStream(route, client, token = { B.accessToken }).run(cursor = null) { event ->
                inbox.receive(event.chatId, event.messageId, event.envelope)
                inbox.openNext(
                    open = { entry ->
                        PersonalMessages.open(
                            envelopeBytes = entry.envelope,
                            myDeviceId = B.deviceId,
                            me = B.identity,
                            senderSigningPublic = A.identity.signingPublic,
                        ).fold(
                            // Байты тела, как пришли: столбец читается кодеком, и текстом писать нельзя.
                            onSuccess = { OpenOutcome.Opened(it.body, it.meta.senderId) },
                            onFailure = { OpenOutcome.NoKey(it.message ?: "не открылось") },
                        )
                    },
                )?.let { entry ->
                    // Текст берётся ИЗ БАЗЫ УСТРОЙСТВА: прогон на телефоне обязан
                    // доказать, что сообщение доехало туда, откуда его читает экран.
                    if (entry.state == IncomingState.STORED) {
                        val line = bDatabase.messagesQueries
                            .byDedupKey("${entry.chatId}/${entry.messageId}")
                            .executeAsOneOrNull()
                        val body = line?.body_enc?.let { bCipher.open(it) }
                        // Тело читается КОДЕКОМ, как его читает экран: столбец хранит
                        // упакованное тело, а не текст. Прежняя проверка сравнивала байты
                        // напрямую и потому не заметила, что приёмник писал текстом.
                        val lineText = body?.let { TextBodyCodec.decode(it).getOrNull()?.plainText() }
                        if (lineText != null) delivered.complete(lineText)
                    }
                }
            }
        }
        val read = withTimeoutOrNull(60_000) { delivered.await() }
        channel.cancel()

        assertEquals(text, read, "до второго устройства обязан дойти тот же текст")
        step("ПРИНЯТО на телефоне: текст сошёлся")
    }

    // ── регистрация ──────────────────────────────────────────────────────────

    private class Created(
        val userId: String,
        val deviceId: String,
        val accessToken: String,
        val identity: DeviceIdentity,
        val secret: ByteArray,
    )

    private suspend fun create(route: ServerRoute, phone: String, name: String): Created {
        val auth = AuthApi(route, client)

        val request = auth.requestSms(phone)
        assertIs<SmsRequestResult.Sent>(request, "$name: запрос кода не прошёл: $request")
        val code = request.devCode
        assertTrue(code != null, "$name: сервер не отдал dev_code — TIMA_DEV_SMS выключен?")

        val check = auth.verifySms(request.requestId, code)
        assertIs<SmsVerifyResult.Verified>(check, "$name: код не принят: $check")

        val material = DeviceKeyFactoryOverKodium.newDeviceKeys()
        val answer = auth.register(
            registrationToken = check.registrationToken,
            encryptionPub = material.encryptionPub,
            signingPub = material.signingPub,
            platform = "android-харнесс",
        )
        assertIs<RegisterResult.Registered>(answer, "$name: заведение не прошло: $answer")
        step("$name заведено: user=${answer.userId} device=${answer.deviceId}")

        return Created(
            userId = answer.userId,
            deviceId = answer.deviceId,
            accessToken = answer.accessToken,
            identity = deviceIdentityFrom(material.secret),
            secret = material.secret,
        )
    }
}
