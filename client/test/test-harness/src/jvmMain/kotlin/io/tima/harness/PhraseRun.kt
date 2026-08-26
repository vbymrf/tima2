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

    private val log = StringBuilder()

    /**
     * Пишем и в консоль, и в файл.
     *
     * Консоль Windows отдаёт кириллицу вопросами независимо от `file.encoding`, а
     * диагностический вывод — это всё, ради чего программа написана. Файл в UTF-8 читается
     * всегда.
     */
    private fun line(text: String = "") {
        println(text)
        log.appendLine(text)
    }

    private var failures = 0

    private fun check(name: String, condition: Boolean, detail: String) {
        line(if (condition) "  ok    $name — $detail" else "  ПЛОХО  $name — $detail")
        if (!condition) failures++
    }

    fun main() = runBlocking {
        val host = System.getenv("TIMA_STAND_HOST")?.takeIf { it.isNotBlank() }
            ?: "xn--80aa4ar0b.xn--p1ai"
        val label = System.currentTimeMillis() % 100_000_000
        val number1 = System.getenv("TIMA_PHONE_A")?.takeIf { it.isNotBlank() }
            ?: "+797${label.toString().padStart(8, '0')}"
        val number2 = System.getenv("TIMA_PHONE_B")?.takeIf { it.isNotBlank() }
            ?: "+796${label.toString().padStart(8, '0')}"

        val route = ServerRoute.from(RouteConfig(host = host))
        line("маршрут:  ${route.apiBase}")
        line("номер 1:  $number1 (возврат по фразе)")
        line("номер 2:  $number2 (форк личности)")
        line()

        val client = HttpClient(httpEngine()) { timaDefaults() }
        try {
            returnValue(client, route, number1)
            line()
            fork(client, route, number2)
        } finally {
            client.close()
            line()
            line(if (failures == 0) "ВСЁ СОШЛОСЬ" else "НЕ СОШЛОСЬ: $failures")
            val file = File("build/phrase-run.txt")
            file.parentFile?.mkdirs()
            file.writeText(log.toString(), Charsets.UTF_8)
            println("журнал: ${file.absolutePath}")
        }
    }

    /** Номер 1: фраза заводит аккаунт, чужая не входит, своя возвращает. */
    private suspend fun returnValue(client: HttpClient, route: ServerRoute, number: String) {
        val my = AccountIdentitiesOverKodium.fresh()
        // Слова не печатаются даже здесь: это секрет, а журнал остаётся на диске.
        line("шаг 1: заведение с новой личностью (${my.words.size} слов)")
        val first = create(client, route, number, my.identityPub)
        check("заведено", first is RegisterResult.Registered, first.shortName())
        if (first !is RegisterResult.Registered) return
        line("        user=${first.userId} device=${first.deviceId}")

        val foreign = AccountIdentitiesOverKodium.fresh()
        line("шаг 2: тот же номер, ЧУЖАЯ личность")
        val second = create(client, route, number, foreign.identityPub)
        check(
            "отказ по личности",
            second is RegisterResult.IdentityMismatch,
            "${second.shortName()} — второй аккаунт на том же номере был бы угоном",
        )

        line("шаг 3: тот же номер, ключ выведен ЗАНОВО из тех же слов")
        val fromWords = AccountIdentitiesOverKodium.fromWords(my.words)
        check(
            "ключ из слов тот же",
            fromWords != null && fromWords.contentEquals(my.identityPub),
            "иначе фраза не вернула бы в аккаунт ни на каком устройстве",
        )
        if (fromWords == null) return
        val third = create(client, route, number, fromWords)
        check("вход по фразе", third is RegisterResult.Registered, third.shortName())
        if (third !is RegisterResult.Registered) return
        check(
            "аккаунт тот же",
            third.userId == first.userId,
            "user=${third.userId}, ожидали ${first.userId}",
        )
        check(
            "устройство новое",
            third.deviceId != first.deviceId,
            "device=${third.deviceId} — прежнее осталось на месте",
        )
    }

    /** Номер 2: «начать заново» форкает цепочку, и прежняя фраза через register не входит. */
    private suspend fun fork(client: HttpClient, route: ServerRoute, number: String) {
        val previous = AccountIdentitiesOverKodium.fresh()
        line("шаг 4: заведение, затем «начать заново» с новой личностью")
        val first = create(client, route, number, previous.identityPub)
        check("заведено", first is RegisterResult.Registered, first.shortName())
        if (first !is RegisterResult.Registered) return
        line("        user=${first.userId}")

        val new = AccountIdentitiesOverKodium.fresh()
        val forked = create(client, route, number, new.identityPub, fork = true)
        check("форк принят", forked is RegisterResult.Registered, forked.shortName())
        if (forked is RegisterResult.Registered) {
            check(
                "цепочка форкнута",
                forked.userId != first.userId,
                "user=${forked.userId} — прежний ${first.userId} закрыт",
            )
        }

        line("шаг 5: прежняя фраза ПОСЛЕ форка")
        val fifth = create(client, route, number, previous.identityPub)
        check(
            "register знает только текущую личность",
            fifth is RegisterResult.IdentityMismatch,
            "${fifth.shortName()} — для прежней есть /identity/reunion с подписью",
        )
    }

    /**
     * Заведение устройства: код, проверка, register.
     *
     * Ключи устройства каждый раз новые — так и бывает на чистом устройстве, ради которого
     * фраза и нужна. Личность аккаунта задаётся снаружи: её узнавание и проверяется.
     */
    private suspend fun create(
        client: HttpClient,
        route: ServerRoute,
        phone: String,
        identityPub: ByteArray,
        fork: Boolean = false,
    ): RegisterResult {
        val auth = AuthApi(route, client)
        val request = auth.requestSms(phone)
        if (request !is SmsRequestResult.Sent) return RegisterResult.Refused(0, "запрос кода: $request")
        val code = request.devCode
            ?: return RegisterResult.Refused(0, "TIMA_DEV_SMS выключен — кода в ответе нет")
        val check = auth.verifySms(request.requestId, code)
        if (check !is SmsVerifyResult.Verified) {
            return RegisterResult.Refused(0, "проверка кода: $check")
        }
        val material = DeviceKeyFactoryOverKodium.newDeviceKeys()
        return auth.register(
            registrationToken = check.registrationToken,
            encryptionPub = material.encryptionPub,
            signingPub = material.signingPub,
            identityPub = identityPub,
            platform = "харнесс",
            forceNewIdentity = fork,
        )
    }

    private fun RegisterResult.shortName(): String = when (this) {
        is RegisterResult.Registered -> "201 Registered"
        RegisterResult.IdentityMismatch -> "403 identity_mismatch"
        RegisterResult.TokenExpired -> "403 bad_token"
        is RegisterResult.Refused -> "отказ $status $code"
        is RegisterResult.NoConnection -> "нет связи: $link"
    }
}

fun main() = PhraseRun.main()
