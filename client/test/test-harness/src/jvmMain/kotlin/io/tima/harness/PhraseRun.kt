package io.tima.harness

import io.ktor.client.HttpClient
import io.tima.core.encryption.AccountIdentitiesOverKodium
import io.tima.core.encryption.DeviceKeyFactoryOverKodium
import io.tima.core.network.AuthApi
import io.tima.core.network.RegisterResult
import io.tima.core.network.RouteConfig
import io.tima.core.network.ServerRoute
import io.tima.core.network.SmsRequestResult
import io.tima.core.network.SmsVerifyResult
import io.tima.core.network.httpEngine
import io.tima.core.network.timaDefaults
import java.io.File
import kotlinx.coroutines.runBlocking

/**
 * Секретная фраза **против живого сервера** — К5.1.
 *
 * Экранные тесты проверяют поведение экрана на подделке сервера. Они не могут проверить
 * главного: что сервер узнаёт личность по выведенному из фразы ключу, отказывает чужой
 * личности и форкает цепочку только по прямой просьбе. Ровно на этой границе — «клиент
 * обещает, сервер делает что-то другое» — и нашлись находки 20–25.
 *
 * Программа, а не тест: она заводит настоящие аккаунты на настоящем сервере и — намеренно —
 * форкает цепочку личности. Поэтому не запускается сборкой и не ходит в CI.
 *
 * **Два номера, а не один, и это не удобство.** Сервер даёт 3 SMS на номер за 10 минут
 * (`rlSmsPerPhone`), а утверждений здесь пять. Разложены они так, что каждому номеру
 * достаётся ровно три:
 * ```
 * номер 1 — возврат:
 *   1. фраза заводит аккаунт            → 201, ключ личности установлен
 *   2. ЧУЖАЯ фраза на том же номере     → 403 identity_mismatch, а не второй аккаунт
 *   3. СВОЯ фраза, выведенная ЗАНОВО
 *      из тех же слов                   → 201 с ТЕМ ЖЕ user_id и новым device_id
 * номер 2 — форк:
 *   4. заведение и «начать заново»      → 201 с ДРУГИМ user_id: цепочка форкнута
 *   5. прежняя фраза после форка        → 403: register знает только текущую личность
 * ```
 *
 * Шаг 3 — сердце проверки: ключ выводится **из слов**, а не берётся из объекта, который
 * его породил. Иначе проверялось бы «переменная равна себе», а не то, ради чего фраза
 * существует: вернуться в аккаунт с чистого устройства, имея только бумажку.
 *
 * Шаг 5 — не поломка, а найденная граница: после «начать заново» прежняя фраза через
 * `register` не работает — сервер сверяет её с ТЕКУЩЕЙ личностью аккаунта. Для прежней у
 * него есть отдельная ручка `/identity/reunion` с подписью челленджа; клиент её пока не
 * умеет, и это записано в карте.
 *
 * Запуск:
 * ```
 * TIMA_STAND_HOST=xn--80aa4ar0b.xn--p1ai ./gradlew :test:test-harness:phraseRun
 * ```
 */
object PhraseRun {

    private val журнал = StringBuilder()

    /**
     * Пишем и в консоль, и в файл.
     *
     * Консоль Windows отдаёт кириллицу вопросами независимо от `file.encoding`, а
     * диагностический вывод — это всё, ради чего программа написана. Файл в UTF-8 читается
     * всегда.
     */
    private fun строка(текст: String = "") {
        println(текст)
        журнал.appendLine(текст)
    }

    private var провалов = 0

    private fun проверка(имя: String, условие: Boolean, подробность: String) {
        строка(if (условие) "  ok    $имя — $подробность" else "  ПЛОХО  $имя — $подробность")
        if (!условие) провалов++
    }

    fun main() = runBlocking {
        val host = System.getenv("TIMA_STAND_HOST")?.takeIf { it.isNotBlank() }
            ?: "xn--80aa4ar0b.xn--p1ai"
        val метка = System.currentTimeMillis() % 100_000_000
        val номер1 = System.getenv("TIMA_PHONE_A")?.takeIf { it.isNotBlank() }
            ?: "+797${метка.toString().padStart(8, '0')}"
        val номер2 = System.getenv("TIMA_PHONE_B")?.takeIf { it.isNotBlank() }
            ?: "+796${метка.toString().padStart(8, '0')}"

        val route = ServerRoute.from(RouteConfig(host = host))
        строка("маршрут:  ${route.apiBase}")
        строка("номер 1:  $номер1 (возврат по фразе)")
        строка("номер 2:  $номер2 (форк личности)")
        строка()

        val client = HttpClient(httpEngine()) { timaDefaults() }
        try {
            возврат(client, route, номер1)
            строка()
            форк(client, route, номер2)
        } finally {
            client.close()
            строка()
            строка(if (провалов == 0) "ВСЁ СОШЛОСЬ" else "НЕ СОШЛОСЬ: $провалов")
            val файл = File("build/phrase-run.txt")
            файл.parentFile?.mkdirs()
            файл.writeText(журнал.toString(), Charsets.UTF_8)
            println("журнал: ${файл.absolutePath}")
        }
    }

    /** Номер 1: фраза заводит аккаунт, чужая не входит, своя возвращает. */
    private suspend fun возврат(client: HttpClient, route: ServerRoute, номер: String) {
        val моя = AccountIdentitiesOverKodium.fresh()
        // Слова не печатаются даже здесь: это секрет, а журнал остаётся на диске.
        строка("шаг 1: заведение с новой личностью (${моя.words.size} слов)")
        val первое = завести(client, route, номер, моя.identityPub)
        проверка("заведено", первое is RegisterResult.Registered, первое.краткоеИмя())
        if (первое !is RegisterResult.Registered) return
        строка("        user=${первое.userId} device=${первое.deviceId}")

        val чужая = AccountIdentitiesOverKodium.fresh()
        строка("шаг 2: тот же номер, ЧУЖАЯ личность")
        val второе = завести(client, route, номер, чужая.identityPub)
        проверка(
            "отказ по личности",
            второе is RegisterResult.IdentityMismatch,
            "${второе.краткоеИмя()} — второй аккаунт на том же номере был бы угоном",
        )

        строка("шаг 3: тот же номер, ключ выведен ЗАНОВО из тех же слов")
        val изСлов = AccountIdentitiesOverKodium.fromWords(моя.words)
        проверка(
            "ключ из слов тот же",
            изСлов != null && изСлов.contentEquals(моя.identityPub),
            "иначе фраза не вернула бы в аккаунт ни на каком устройстве",
        )
        if (изСлов == null) return
        val третье = завести(client, route, номер, изСлов)
        проверка("вход по фразе", третье is RegisterResult.Registered, третье.краткоеИмя())
        if (третье !is RegisterResult.Registered) return
        проверка(
            "аккаунт тот же",
            третье.userId == первое.userId,
            "user=${третье.userId}, ожидали ${первое.userId}",
        )
        проверка(
            "устройство новое",
            третье.deviceId != первое.deviceId,
            "device=${третье.deviceId} — прежнее осталось на месте",
        )
    }

    /** Номер 2: «начать заново» форкает цепочку, и прежняя фраза через register не входит. */
    private suspend fun форк(client: HttpClient, route: ServerRoute, номер: String) {
        val прежняя = AccountIdentitiesOverKodium.fresh()
        строка("шаг 4: заведение, затем «начать заново» с новой личностью")
        val первое = завести(client, route, номер, прежняя.identityPub)
        проверка("заведено", первое is RegisterResult.Registered, первое.краткоеИмя())
        if (первое !is RegisterResult.Registered) return
        строка("        user=${первое.userId}")

        val новая = AccountIdentitiesOverKodium.fresh()
        val форкнутое = завести(client, route, номер, новая.identityPub, форк = true)
        проверка("форк принят", форкнутое is RegisterResult.Registered, форкнутое.краткоеИмя())
        if (форкнутое is RegisterResult.Registered) {
            проверка(
                "цепочка форкнута",
                форкнутое.userId != первое.userId,
                "user=${форкнутое.userId} — прежний ${первое.userId} закрыт",
            )
        }

        строка("шаг 5: прежняя фраза ПОСЛЕ форка")
        val пятое = завести(client, route, номер, прежняя.identityPub)
        проверка(
            "register знает только текущую личность",
            пятое is RegisterResult.IdentityMismatch,
            "${пятое.краткоеИмя()} — для прежней есть /identity/reunion с подписью",
        )
    }

    /**
     * Заведение устройства: код, проверка, register.
     *
     * Ключи устройства каждый раз новые — так и бывает на чистом устройстве, ради которого
     * фраза и нужна. Личность аккаунта задаётся снаружи: её узнавание и проверяется.
     */
    private suspend fun завести(
        client: HttpClient,
        route: ServerRoute,
        phone: String,
        identityPub: ByteArray,
        форк: Boolean = false,
    ): RegisterResult {
        val auth = AuthApi(route, client)
        val запрос = auth.requestSms(phone)
        if (запрос !is SmsRequestResult.Sent) return RegisterResult.Refused(0, "запрос кода: $запрос")
        val код = запрос.devCode
            ?: return RegisterResult.Refused(0, "TIMA_DEV_SMS выключен — кода в ответе нет")
        val проверка = auth.verifySms(запрос.requestId, код)
        if (проверка !is SmsVerifyResult.Verified) {
            return RegisterResult.Refused(0, "проверка кода: $проверка")
        }
        val материал = DeviceKeyFactoryOverKodium.newDeviceKeys()
        return auth.register(
            registrationToken = проверка.registrationToken,
            encryptionPub = материал.encryptionPub,
            signingPub = материал.signingPub,
            identityPub = identityPub,
            platform = "харнесс",
            forceNewIdentity = форк,
        )
    }

    private fun RegisterResult.краткоеИмя(): String = when (this) {
        is RegisterResult.Registered -> "201 Registered"
        RegisterResult.IdentityMismatch -> "403 identity_mismatch"
        RegisterResult.TokenExpired -> "403 bad_token"
        is RegisterResult.Refused -> "отказ $status $code"
        is RegisterResult.NoConnection -> "нет связи: $link"
    }
}

fun main() = PhraseRun.main()
