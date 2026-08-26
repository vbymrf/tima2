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

    suspend fun latest(): AppVersionResult {
        val response = try {
            client.get(route.api("/api/v1/app/version"))
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
    ) : AppVersionResult

    /** `204`: обновления на сервере не настроены. */
    data object NotConfigured : AppVersionResult

    data class NoConnection(val link: LinkState) : AppVersionResult

    data class Refused(val status: Int, val code: String) : AppVersionResult
}
