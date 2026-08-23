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
import io.tima.core.network.UsersApi
import io.tima.core.network.SmsRequestResult
import io.tima.core.network.SmsVerifyResult
import io.tima.core.network.httpEngine
import io.tima.core.network.timaDefaults
import io.tima.core.outbox.Inbox
import io.tima.core.outbox.OpenOutcome
import io.tima.crypto.PersonalChatId
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
        val метка = System.currentTimeMillis() % 100_000_000
        val телефонА = env("TIMA_PHONE_A") ?: "+799${метка.toString().padStart(8, '0')}"
        val телефонБ = env("TIMA_PHONE_B") ?: "+798${метка.toString().padStart(8, '0')}"

        val route = ServerRoute.from(RouteConfig(host = host))
        шаг("маршрут", "${route.apiBase}, WS ${route.wsUrl}")

        val client = HttpClient(httpEngine()) {
            timaDefaults()
            install(WebSockets)
        }

        try {
            val А = завести(client, route, телефонА, "устройство А") ?: return@runBlocking
            val Б = завести(client, route, телефонБ, "устройство Б") ?: return@runBlocking

            // Так переписку начинает приложение: по НОМЕРУ, а не по внутреннему
            // идентификатору. Проверяем справочник против живого сервера — единственное
            // место, где это вообще можно проверить.
            val найденБ = UsersApi(route, client, token = { А.accessToken }).byPhone(телефонБ)
            if (найденБ !is UserLookup.Found) {
                провал("поиск Б по номеру", найденБ.toString())
                return@runBlocking
            }
            if (найденБ.userId != Б.userId) {
                провал("поиск Б по номеру", "нашёлся другой: ${найденБ.userId} вместо ${Б.userId}")
                return@runBlocking
            }
            шаг("поиск по номеру", "нашёлся тот же user_id, имя от сервера: ${найденБ.name ?: "нет"}")

            val chatId = PersonalChatId.of(А.userId, Б.userId)
            шаг("chat_id", "$chatId (выведен из пары user_id, сервер его не назначает)")

            val ключиБ = KeysApi(route, client, token = { А.accessToken }).devicesOf(Б.userId)
            if (ключиБ !is DeviceKeysResult.Devices) {
                провал("ключи устройств Б", ключиБ.toString())
                return@runBlocking
            }
            шаг("ключи устройств Б", "${ключиБ.devices.size} шт.: ${ключиБ.devices.map { it.deviceId }}")

            val доверие = env("TIMA_ESCROW_SIGNING_PUB")
            if (доверие == null) {
                строка("")
                строка("Шаги 1–4 прошли. Дальше нужен ключ подписи анклава:")
                строка("  docker compose -f deploy/docker-compose.prod.yml logs escrow-stub \\")
                строка("    | grep 'ключ подписи конфига'")
                строка("и повторный запуск с TIMA_ESCROW_SIGNING_PUB=<эта строка>.")
                строка("")
                строка("Без него отправка невозможна намеренно: непроверенный ключ эпохи")
                строка("означал бы шифрование в обход анклава.")
                return@runBlocking
            }

            отправитьИПринять(client, route, А, Б, chatId, ключиБ, доверие)
        } finally {
            client.close()
            сохранитьЖурнал()
        }
    }

    // ── регистрация ──────────────────────────────────────────────────────────

    /** Что мы знаем про заведённое устройство. */
    private class Заведённое(
        val userId: String,
        val deviceId: String,
        val accessToken: String,
        val identity: DeviceIdentity,
        /** Секрет устройства: из него выводится и ключ покоя локальной базы. */
        val secret: ByteArray,
    )

    private suspend fun завести(
        client: HttpClient,
        route: ServerRoute,
        phone: String,
        имя: String,
    ): Заведённое? {
        val auth = AuthApi(route, client)

        val запрос = auth.requestSms(phone)
        if (запрос !is SmsRequestResult.Sent) {
            провал("$имя: запрос кода", запрос.toString())
            return null
        }
        val код = запрос.devCode
        if (код == null) {
            провал("$имя: код в ответе", "TIMA_DEV_SMS выключен — настоящую SMS харнесс не получит")
            return null
        }
        шаг("$имя: код", "получен для $phone")

        val проверка = auth.verifySms(запрос.requestId, код)
        if (проверка !is SmsVerifyResult.Verified) {
            провал("$имя: проверка кода", проверка.toString())
            return null
        }

        // Ключи порождаются здесь и живут в памяти: это программа на один запуск, и
        // хранилище платформы ей ни к чему. В приложении их пишет core-secrets, причём
        // ДО вызова сервера.
        val материал = DeviceKeyFactoryOverKodium.newDeviceKeys()
        val ответ = auth.register(
            registrationToken = проверка.registrationToken,
            encryptionPub = материал.encryptionPub,
            signingPub = материал.signingPub,
            platform = "харнесс",
        )
        if (ответ !is RegisterResult.Registered) {
            провал("$имя: заведение", ответ.toString())
            return null
        }
        шаг("$имя: заведено", "user=${ответ.userId} device=${ответ.deviceId}")

        return Заведённое(
            userId = ответ.userId,
            deviceId = ответ.deviceId,
            accessToken = ответ.accessToken,
            identity = deviceIdentityFrom(материал.secret),
            secret = материал.secret,
        )
    }

    // ── отправка и приём ─────────────────────────────────────────────────────

    private suspend fun отправитьИПринять(
        client: HttpClient,
        route: ServerRoute,
        А: Заведённое,
        Б: Заведённое,
        chatId: String,
        ключиБ: DeviceKeysResult.Devices,
        довериеBase64: String,
    ) {
        val анклавПуб = декодировать(довериеBase64)
        if (анклавПуб == null) {
            провал("ключ анклава", "не base64url: $довериеBase64")
            return
        }

        val эпоха = EscrowApi(route, client, token = { А.accessToken }).keyForChat(chatId)
        if (эпоха !is EscrowKeyResult.Keys) {
            провал("ключ эпохи escrow", эпоха.toString())
            return
        }
        val подписанный = эпоха.current
        шаг("ключ эпохи", "id=${подписанный.id} эпоха=${подписанный.epoch} регион=${подписанный.region}")

        val проверенный = EscrowKeyVerifier.verify(
            enclaveSigningPub = анклавПуб,
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
        ).getOrElse {
            провал("подпись анклава", it.message ?: "не сошлась")
            return
        }
        шаг("подпись анклава", "сошлась")

        val текст = "Первое сквозное сообщение v2, ${System.currentTimeMillis()}"
        val sealer = OutgoingSealer(А.userId, А.deviceId, А.identity).sealerFor(
            escrowKey = проверенный,
            recipients = ключиБ.devices.map { RecipientDevice(it.deviceId, it.encryptionPub) },
        )

        // База в памяти: программа на один запуск, следов на диске оставлять незачем.
        val harness = ChatHarness(harnessDriver(), sealWith = sealer)
        harness.send(chatId, текст)

        val transport = HttpMessageTransport(route, client, token = { А.accessToken })
        val отправлено = harness.pumpVia { готовое ->
            val исход = transport.send(готовое.entry.dedupKey, готовое.envelope)
            // Исход печатается всегда: «сообщение не ушло» без причины — отчёт ни о чём.
            шаг("исход отправки", исход.toString() + ", конверт " + готовое.envelope.size + " байт")
            исход
        }
        шаг("отправка", "исходов: $отправлено, в очереди осталось ${harness.pending().size}")
        if (harness.pending().isNotEmpty()) {
            провал("отправка", "сообщение не ушло: ${harness.pending()}")
            return
        }

        // Приём вторым устройством: живой канал того же сервера.
        // Шифр покоя выводится из НАСТОЯЩЕГО секрета устройства, а не из постоянного
        // харнессного: прогон по стенду проверяет то, что поедет на устройство, включая
        // ключ локальной базы.
        val шифрБ = LocalStoreFieldCipher(Б.secret)
        val базаБ = TimaDatabase(harnessDriver())
        val inbox = Inbox(
            SqlInboxStore(базаБ, шифрБ),
            nowMs = { System.currentTimeMillis() },
        )
        // Ждём до первого дошедшего, а не до конца тайм-аута: канал сам не закрывается.
        val дошло = CompletableDeferred<String>()

        val канал = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
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
                    // Текст берётся ИЗ БАЗЫ, а не из лямбды: прогон обязан доказать, что
                    // сообщение доехало туда, откуда его читает экран. Раньше здесь была
                    // лямбда persist, и она же скрывала, что в базу тело не ложилось.
                    if (запись.state == io.tima.core.outbox.IncomingState.STORED) {
                        val строка = базаБ.messagesQueries
                            .byDedupKey("${запись.chatId}/${запись.messageId}")
                            .executeAsOneOrNull()
                        val тело = строка?.body_enc?.let { шифрБ.open(it) }
                        if (тело != null) дошло.complete(тело.decodeToString())
                    }
                }
            }
        }
        val прочитано = withTimeoutOrNull(30_000) { дошло.await() }
        канал.cancel()

        if (прочитано == текст) {
            шаг("ПРИНЯТО", "текст сошёлся целиком")
            строка("")
            строка("Сквозной путь пройден: отправлено устройством А, прочитано устройством Б.")
        } else {
            провал("приём", "ожидался «$текст», получено «$прочитано»")
        }
    }

    private fun декодировать(text: String): ByteArray? = runCatching {
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
    private val журнал = StringBuilder()

    private fun строка(текст: String) {
        println(текст)
        журнал.appendLine(текст)
    }

    private fun шаг(что: String, подробность: String) = строка("  + " + что + ": " + подробность)

    private fun провал(что: String, подробность: String) = строка("  ! " + что + ": " + подробность)

    /** Куда лёг журнал. Вызывается в конце, что бы ни случилось. */
    private fun сохранитьЖурнал() {
        val файл = java.io.File("build/stand-run.log")
        файл.parentFile?.mkdirs()
        файл.writeText(журнал.toString(), Charsets.UTF_8)
        println("журнал: " + файл.absolutePath)
    }
}

fun main() = StandRun.main()
