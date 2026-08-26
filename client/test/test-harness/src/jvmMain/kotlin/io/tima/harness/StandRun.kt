package io.tima.harness

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.tima.core.database.SqlInboxStore
import io.tima.core.database.TimaDatabase
import io.tima.core.encryption.DeviceIdentity
import io.tima.core.encryption.DeviceKeyFactoryOverKodium
import io.tima.core.encryption.EscrowKeyVerifier
import io.tima.core.encryption.LocalStoreFieldCipher
import io.tima.core.encryption.OutgoingSealer
import io.tima.core.encryption.PersonalChatIdsOverKodium
import io.tima.core.encryption.PersonalMessages
import io.tima.core.encryption.TextBodyCodec
import io.tima.core.encryption.RecipientDevice
import io.tima.core.encryption.deviceIdentityFrom
import io.tima.core.network.AuthApi
import io.tima.core.network.DeviceKeysResult
import io.tima.core.network.DevicesApi
import io.tima.core.network.EscrowApi
import io.tima.core.network.EscrowKeyResult
import io.tima.core.network.EventStream
import io.tima.core.network.HttpMessageTransport
import io.tima.core.network.KeysApi
import io.tima.core.network.PlatformResult
import io.tima.core.network.RegisterResult
import io.tima.core.network.RouteConfig
import io.tima.core.network.ServerRoute
import io.tima.core.network.UsersApi
import io.tima.core.network.SmsRequestResult
import io.tima.core.network.SmsVerifyResult
import io.tima.core.network.httpEngine
import io.tima.core.network.timaDefaults
import io.tima.core.outbox.Inbox
import io.tima.core.outbox.OpenOutcome
import io.tima.domain.chat.UserLookup
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Сквозной прогон **против живого стенда** — К4.3, «аккаунт заводится харнессом».
 *
 * Это не тест, а программа: она создаёт настоящие аккаунты на настоящем сервере.
 * Поэтому она не запускается сборкой и не ходит в CI — только руками, и только по
 * стенду, где `TIMA_DEV_SMS` включён (иначе код подтверждения пришлось бы получать
 * настоящей SMS).
 *
 * **Что она делает и почему по шагам.** Каждый шаг печатается, потому что отказ на
 * живом сервере надо уметь локализовать сразу: «не прошла регистрация» и «не сошлась
 * подпись ключа эпохи» лечатся в разных местах.
 *
 * ```
 * 1. запросить код и завести устройство А
 * 2. то же для устройства Б
 * 3. вывести chat_id из пары user_id (обе стороны считают одинаково)
 * 4. взять ключи устройств собеседника
 * 5. взять ключ эпохи escrow и ПРОВЕРИТЬ подпись анклава
 * 6. отправить сообщение
 * 7. принять его вторым устройством и открыть
 * ```
 *
 * Шаги 1–4 работают без ключа подписи анклава: их можно прогнать хоть сейчас.
 * Шаги 5–7 без него **намеренно** невозможны — непроверенный ключ эпохи означал бы
 * шифрование в обход анклава.
 *
 * Запуск:
 * ```
 * TIMA_STAND_HOST=xn--80aa4ar0b.xn--p1ai \
 * TIMA_ESCROW_SIGNING_PUB=<base64url из журнала анклава> \
 *   ./gradlew :test:test-harness:standRun
 * ```
 */
object StandRun {

    private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

    fun main() = runBlocking {
        val host = env("TIMA_STAND_HOST") ?: "xn--80aa4ar0b.xn--p1ai"
        val label = System.currentTimeMillis() % 100_000_000
        val aPhone = env("TIMA_PHONE_A") ?: "+799${label.toString().padStart(8, '0')}"
        val bPhone = env("TIMA_PHONE_B") ?: "+798${label.toString().padStart(8, '0')}"

        val route = ServerRoute.from(RouteConfig(host = host))
        step("маршрут", "${route.apiBase}, WS ${route.wsUrl}")

        val client = HttpClient(httpEngine()) {
            timaDefaults()
            install(WebSockets)
        }

        try {
            val A = create(client, route, aPhone, "устройство А") ?: return@runBlocking
            val B = create(client, route, bPhone, "устройство Б") ?: return@runBlocking

            declarePlatform(client, route, A)

            // Так переписку начинает приложение: по НОМЕРУ, а не по внутреннему
            // идентификатору. Проверяем справочник против живого сервера — единственное
            // место, где это вообще можно проверить.
            val bFound = UsersApi(route, client, token = { A.accessToken }).byPhone(bPhone)
            if (bFound !is UserLookup.Found) {
                failure("поиск Б по номеру", bFound.toString())
                return@runBlocking
            }
            if (bFound.userId != B.userId) {
                failure("поиск Б по номеру", "нашёлся другой: ${bFound.userId} вместо ${B.userId}")
                return@runBlocking
            }
            step("поиск по номеру", "нашёлся тот же user_id, имя от сервера: ${bFound.name ?: "no"}")

            val chatId = PersonalChatIdsOverKodium.personalChatId(A.userId, B.userId)
            step("chat_id", "$chatId (выведен из пары user_id, сервер его не назначает)")

            val bKeys = KeysApi(route, client, token = { A.accessToken }).devicesOf(B.userId)
            if (bKeys !is DeviceKeysResult.Devices) {
                failure("ключи устройств Б", bKeys.toString())
                return@runBlocking
            }
            step("ключи устройств Б", "${bKeys.devices.size} шт.: ${bKeys.devices.map { it.deviceId }}")

            val trust = env("TIMA_ESCROW_SIGNING_PUB")
            if (trust == null) {
                line("")
                line("Шаги 1–4 прошли. Дальше нужен ключ подписи анклава:")
                line("  docker compose -f deploy/docker-compose.prod.yml logs escrow-stub \\")
                line("    | grep 'ключ подписи конфига'")
                line("и повторный запуск с TIMA_ESCROW_SIGNING_PUB=<эта строка>.")
                line("")
                line("Без него отправка невозможна намеренно: непроверенный ключ эпохи")
                line("означал бы шифрование в обход анклава.")
                return@runBlocking
            }

            sendAndAccept(client, route, A, B, chatId, bKeys, trust)
        } finally {
            client.close()
            saveLog()
        }
    }

    // ── регистрация ──────────────────────────────────────────────────────────

    /** Что мы знаем про заведённое устройство. */
    private class Created(
        val userId: String,
        val deviceId: String,
        val accessToken: String,
        val identity: DeviceIdentity,
        /** Секрет устройства: из него выводится и ключ покоя локальной базы. */
        val secret: ByteArray,
    )

    private suspend fun create(
        client: HttpClient,
        route: ServerRoute,
        phone: String,
        name: String,
    ): Created? {
        val auth = AuthApi(route, client)

        val request = auth.requestSms(phone)
        if (request !is SmsRequestResult.Sent) {
            failure("$name: запрос кода", request.toString())
            return null
        }
        val code = request.devCode
        if (code == null) {
            failure("$name: код в ответе", "TIMA_DEV_SMS выключен — настоящую SMS харнесс не получит")
            return null
        }
        step("$name: код", "получен для $phone")

        val check = auth.verifySms(request.requestId, code)
        if (check !is SmsVerifyResult.Verified) {
            failure("$name: проверка кода", check.toString())
            return null
        }

        // Ключи порождаются здесь и живут в памяти: это программа на один запуск, и
        // хранилище платформы ей ни к чему. В приложении их пишет core-secrets, причём
        // ДО вызова сервера.
        val material = DeviceKeyFactoryOverKodium.newDeviceKeys()
        val answer = auth.register(
            registrationToken = check.registrationToken,
            encryptionPub = material.encryptionPub,
            signingPub = material.signingPub,
            platform = "харнесс",
        )
        if (answer !is RegisterResult.Registered) {
            failure("$name: заведение", answer.toString())
            return null
        }
        step("$name: заведено", "user=${answer.userId} device=${answer.deviceId}")

        return Created(
            userId = answer.userId,
            deviceId = answer.deviceId,
            accessToken = answer.accessToken,
            identity = deviceIdentityFrom(material.secret),
            secret = material.secret,
        )
    }

    /**
     * Самообъявление платформы — то, что приложение делает при каждом запуске.
     *
     * Ручка выглядит служебной, а решает продуктовое: подтвердить привязку нового
     * устройства по QR сервер разрешает **только телефону**, и знает он это по колонке
     * `platform`. Поэтому проверяются оба исхода: доброе значение принято тем самым
     * словом, каким его понял сервер, а незнакомое отвергнуто — молчаливое «ну ладно»
     * здесь означало бы, что опечатка в платформе выяснится через неделю на привязке.
     */
    private suspend fun declarePlatform(client: HttpClient, route: ServerRoute, device: Created) {
        val handle = DevicesApi(route, client, token = { device.accessToken })

        val good = handle.declarePlatform("desktop")
        if (good is PlatformResult.Declared && good.platform == "desktop") {
            step("платформа объявлена", "сервер понял её как «${good.platform}»")
        } else {
            failure("платформа объявлена", good.toString())
        }

        val unknown = handle.declarePlatform("харнесс")
        if (unknown is PlatformResult.Refused && unknown.code == "bad_platform") {
            step("незнакомая платформа", "отказ ${unknown.status} ${unknown.code}")
        } else {
            failure("незнакомая платформа", "ожидали 400 bad_platform, пришло: $unknown")
        }
    }

    // ── отправка и приём ─────────────────────────────────────────────────────

    private suspend fun sendAndAccept(
        client: HttpClient,
        route: ServerRoute,
        A: Created,
        B: Created,
        chatId: String,
        bKeys: DeviceKeysResult.Devices,
        trustBase64: String,
    ) {
        val pubEnclave = decode(trustBase64)
        if (pubEnclave == null) {
            failure("ключ анклава", "не base64url: $trustBase64")
            return
        }

        val epoch = EscrowApi(route, client, token = { A.accessToken }).keyForChat(chatId)
        if (epoch !is EscrowKeyResult.Keys) {
            failure("ключ эпохи escrow", epoch.toString())
            return
        }
        val signed = epoch.current
        step("ключ эпохи", "id=${signed.id} эпоха=${signed.epoch} регион=${signed.region}")

        val verified = EscrowKeyVerifier.verify(
            enclaveSigningPub = pubEnclave,
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
        ).getOrElse {
            failure("подпись анклава", it.message ?: "не сошлась")
            return
        }
        step("подпись анклава", "сошлась")

        val text = "Первое сквозное сообщение v2, ${System.currentTimeMillis()}"
        val sealer = OutgoingSealer(A.userId, A.deviceId, A.identity).sealerFor(
            escrowKey = verified,
            recipients = bKeys.devices.map { RecipientDevice(it.deviceId, it.encryptionPub) },
        )

        // База в памяти: программа на один запуск, следов на диске оставлять незачем.
        val harness = ChatHarness(harnessDriver(), sealWith = sealer)
        harness.send(chatId, text)

        val transport = HttpMessageTransport(route, client, token = { A.accessToken })
        val sent = harness.pumpVia { ready ->
            val outcome = transport.send(ready.entry.dedupKey, ready.envelope)
            // Исход печатается всегда: «сообщение не ушло» без причины — отчёт ни о чём.
            step("исход отправки", outcome.toString() + ", конверт " + ready.envelope.size + " байт")
            outcome
        }
        step("отправка", "исходов: $sent, в очереди осталось ${harness.pending().size}")
        if (harness.pending().isNotEmpty()) {
            failure("отправка", "сообщение не ушло: ${harness.pending()}")
            return
        }

        // Приём вторым устройством: живой канал того же сервера.
        // Шифр покоя выводится из НАСТОЯЩЕГО секрета устройства, а не из постоянного
        // харнессного: прогон по стенду проверяет то, что поедет на устройство, включая
        // ключ локальной базы.
        val bCipher = LocalStoreFieldCipher(B.secret)
        val bDatabase = TimaDatabase(harnessDriver())
        val inbox = Inbox(
            SqlInboxStore(bDatabase, bCipher),
            nowMs = { System.currentTimeMillis() },
        )
        // Ждём до первого дошедшего, а не до конца тайм-аута: канал сам не закрывается.
        val delivered = CompletableDeferred<String>()

        val channel = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
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
                    // Текст берётся ИЗ БАЗЫ, а не из лямбды: прогон обязан доказать, что
                    // сообщение доехало туда, откуда его читает экран. Раньше здесь была
                    // лямбда persist, и она же скрывала, что в базу тело не ложилось.
                    if (entry.state == io.tima.core.outbox.IncomingState.STORED) {
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
        val read = withTimeoutOrNull(30_000) { delivered.await() }
        channel.cancel()

        if (read == text) {
            step("ПРИНЯТО", "текст сошёлся целиком")
            line("")
            line("Сквозной путь пройден: отправлено устройством А, прочитано устройством Б.")
        } else {
            failure("приём", "ожидался «$text», получено «$read»")
        }
    }

    private fun decode(text: String): ByteArray? = runCatching {
        @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
        kotlin.io.encoding.Base64.UrlSafe
            .withPadding(kotlin.io.encoding.Base64.PaddingOption.ABSENT)
            .decode(text.trim())
    }.getOrNull()

    /**
     * Вывод идёт и в консоль, и в файл UTF-8.
     *
     * Файл не роскошь: консоль Windows перекодирует кириллицу в вопросы, и
     * диагностический вывод — то, ради чего программа и написана, — становится
     * нечитаемым ровно там, где он нужен. Журнал ещё и прикладывается к отчёту.
     */
    private val log = StringBuilder()

    private fun line(text: String) {
        println(text)
        log.appendLine(text)
    }

    private fun step(what: String, detail: String) = line("  + " + what + ": " + detail)

    private fun failure(what: String, detail: String) = line("  ! " + what + ": " + detail)

    /** Куда лёг журнал. Вызывается в конце, что бы ни случилось. */
    private fun saveLog() {
        val file = java.io.File("build/stand-run.log")
        file.parentFile?.mkdirs()
        file.writeText(log.toString(), Charsets.UTF_8)
        println("журнал: " + file.absolutePath)
    }
}

fun main() = StandRun.main()
