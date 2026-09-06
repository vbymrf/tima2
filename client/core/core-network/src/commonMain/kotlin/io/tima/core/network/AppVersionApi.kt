package io.tima.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode

/**
 * Какая версия приложения лежит на сервере.
 *
 * **Ручка публичная — без токена.** Спросить, что доступно, должно и то устройство, на
 * котором ещё не вошли: обновление может понадобиться именно затем, чтобы вход заработал.
 *
 * `204` — не ошибка, а «сервер обновления не раздаёт»: так отвечает стенд без настроенных
 * `APP_*`. Отдельный исход, потому что молчание сервера и обрыв связи требуют от человека
 * разного.
 */
class AppVersionApi(
    private val route: ServerRoute,
    private val client: HttpClient,
) {

    /**
     * @param platform под какую платформу спрашиваем: `android`, `windows`. Пусто —
     *   сервер отвечает по-старому, то есть про Android. Параметр появился 2026-09-06
     *   вместе с установщиком для ПК; сервер постарше его не заметит и ответит как
     *   отвечал, а это ровно то, чего мы хотим от расширения API.
     */
    suspend fun latest(platform: String = ""): AppVersionResult {
        val path = "/api/v1/app/version" + if (platform.isBlank()) "" else "?platform=$platform"
        val response = try {
            client.get(route.api(path))
        } catch (e: Throwable) {
            return AppVersionResult.NoConnection(classifyFailure(e))
        }
        if (response.status == HttpStatusCode.NoContent) return AppVersionResult.NotConfigured
        val body = response.jsonBody()
        if (response.status != HttpStatusCode.OK || body == null) {
            return AppVersionResult.Refused(response.status.value, body.codeOf())
        }
        val code = body.int("version_code") ?: return AppVersionResult.Refused(
            response.status.value,
            "ответ без version_code",
        )
        return AppVersionResult.Version(
            versionCode = code,
            versionName = body.str("version_name").orEmpty(),
            url = body.str("url").orEmpty(),
            notes = body.str("notes").orEmpty(),
            // Поток появился 2026-08-26 и необязателен: сервер постарше его не пришлёт.
            // Пустая строка здесь — не «поток пустой», а «сервер поток не называет», и
            // решение, что с этим делать, принимает потребитель.
            stream = body.str("stream").orEmpty(),
            // Хэш и размер пакета появились 2026-09-06 и тоже необязательны. Пустой
            // хэш — «сервер его не объявляет»; ставить непроверенное или нет, решает
            // потребитель, а не разбор ответа.
            sha256 = body.str("sha256").orEmpty(),
            size = body.long("size") ?: 0L,
            // Порог совместимости. Ноль и отсутствие поля — одно и то же: «гейта нет».
            // Не «блокировать»: сервер, откатившийся на прежнюю версию, иначе выключал бы
            // все установленные приложения разом (Plan.md §3.5).
            minClient = body.int("min_client") ?: 0,
        )
    }
}

/** Чем кончился вопрос о версии. */
sealed interface AppVersionResult {
    data class Version(
        val versionCode: Int,
        val versionName: String,
        val url: String,
        val notes: String,
        val stream: String,
        /** sha256 пакета в шестнадцатеричном виде. Пусто — сервер хэш не объявляет. */
        val sha256: String = "",
        /** Размер пакета в байтах. 0 — сервер размер не объявляет. */
        val size: Long = 0,
        /** Ниже этой версии работа заблокирована. 0 — порога нет. */
        val minClient: Int = 0,
    ) : AppVersionResult

    /** `204`: обновления на сервере не настроены. */
    data object NotConfigured : AppVersionResult

    data class NoConnection(val link: LinkState) : AppVersionResult

    data class Refused(val status: Int, val code: String) : AppVersionResult
}
