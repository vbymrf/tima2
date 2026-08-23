package io.tima.harness

import io.ktor.client.HttpClient
import io.tima.core.encryption.DeviceKeyFactoryOverKodium
import io.tima.core.network.LinkClaimResult
import io.tima.core.network.LinkStartApi
import io.tima.core.network.LinkStartResult
import io.tima.core.network.RouteConfig
import io.tima.core.network.ServerRoute
import io.tima.core.network.httpEngine
import io.tima.core.network.timaDefaults
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * Привязка устройства **против живого сервера** — К5.1.
 *
 * Программа играет роль нового устройства: просит код, кладёт его в файл и ждёт
 * подтверждения. Подтверждает настоящий телефон — тот, где уже есть аккаунт.
 *
 * **Зачем это отдельно от приложения.** В приложении ту же роль играет экран «Подключить к
 * аккаунту», и он проверен снимками. Здесь проверяется другое: что живой сервер принимает
 * наши запросы, что телефон получает переход по `tima://link/…`, что подпись подтверждения
 * сходится на сервере и что новое устройство действительно получает токен аккаунта. Ни
 * одно из этих утверждений нельзя проверить без второго настоящего устройства.
 *
 * Порядок прогона:
 * ```
 * 1. TIMA_STAND_HOST=… ./gradlew :test:test-harness:linkRun   (пишет код в build/link-run.txt)
 * 2. adb shell am start -a android.intent.action.VIEW -d "<код>"   — то же, что делает камера
 * 3. на телефоне «Доверить»
 * 4. программа печатает user_id и device_id нового устройства
 * ```
 *
 * Шаг 2 руками — не упрощение: сканирование камерой автоматизировать нечем, а переход
 * несёт ровно ту строку, которую камера и передала бы.
 */
object LinkRun {

    fun main() = runBlocking {
        val host = System.getenv("TIMA_STAND_HOST")?.takeIf { it.isNotBlank() }
            ?: "xn--80aa4ar0b.xn--p1ai"
        val имя = System.getenv("TIMA_DEVICE_NAME")?.takeIf { it.isNotBlank() } ?: "Компьютер"

        val route = ServerRoute.from(RouteConfig(host = host))
        val client = HttpClient(httpEngine()) { timaDefaults() }
        val журнал = File("build/link-run.txt")
        журнал.parentFile?.mkdirs()

        fun строка(текст: String) {
            println(текст)
            журнал.appendText(текст + "\n", Charsets.UTF_8)
        }

        журнал.writeText("", Charsets.UTF_8)
        строка("маршрут: ${route.apiBase}")
        строка("имя устройства: $имя")

        try {
            val api = LinkStartApi(route, client)
            val материал = DeviceKeyFactoryOverKodium.newDeviceKeys()

            val начало = api.start(материал.encryptionPub, материал.signingPub, имя)
            if (начало !is LinkStartResult.Started) {
                строка("ПЛОХО start: $начало")
                return@runBlocking
            }
            строка("сессия: ${начало.sessionId}")
            строка("срок: ${начало.expiresAt}")
            строка("")
            строка("КОД:")
            строка(начало.qrPayload)
            строка("")
            строка("Теперь на телефоне: переход по этому коду и «Доверить».")

            var прошло = 0L
            while (прошло < СРОК_МС) {
                when (val ответ = api.claim(начало.sessionId, начало.claimToken)) {
                    is LinkClaimResult.Claimed -> {
                        строка("")
                        строка("ok ПОДТВЕРЖДЕНО")
                        строка("  user=${ответ.userId}")
                        строка("  device=${ответ.deviceId}")
                        строка("  токен получен: ${ответ.accessToken.isNotEmpty()}")
                        return@runBlocking
                    }
                    LinkClaimResult.NotReady -> Unit
                    is LinkClaimResult.NoConnection -> строка("нет связи: ${ответ.link}")
                    is LinkClaimResult.Refused -> {
                        строка("ПЛОХО claim: ${ответ.status} ${ответ.code}")
                        return@runBlocking
                    }
                }
                delay(ОПРОС_МС)
                прошло += ОПРОС_МС
            }
            строка("ПЛОХО: срок вышел, подтверждения не было")
        } finally {
            client.close()
            println("журнал: ${журнал.absolutePath}")
        }
    }

    /** Сервер держит сессию пять минут; опрашиваем раз в две секунды, как приложение. */
    private const val СРОК_МС = 5 * 60 * 1000L
    private const val ОПРОС_МС = 2_000L
}

fun main() = LinkRun.main()
