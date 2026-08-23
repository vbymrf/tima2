package io.tima.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType

/**
 * Устройства аккаунта.
 *
 * Пока одна ручка — самообъявление платформы, и она нужна не для отчётности.
 * **Подтвердить привязку нового устройства по QR вправе только телефон**
 * (`key-lifecycle.md §2`: якорь доверия — аттестуемое устройство, ПК своё доверие
 * наследует, а не раздаёт дальше). Решает это сервер по колонке `platform`, а
 * заполняется она из самообъявления клиента. Устройство, которое молчит или
 * объявило себя не тем, получит `not_a_phone` — то есть привязка не заработает
 * вовсе, и по симптому это будет неотличимо от поломки QR.
 */
class DevicesApi(
    private val route: ServerRoute,
    private val client: HttpClient,
    private val token: () -> String,
) {

    /**
     * `PUT /api/v1/devices/me/platform` — объявить свою платформу.
     *
     * Вызывается **при каждом запуске**, а не один раз при регистрации: сервер сам так
     * задуман (`devices.go`), потому что установки, заведённые до появления колонки,
     * иначе навсегда потеряли бы право подтверждать привязку. Идемпотентно.
     */
    suspend fun declarePlatform(platform: String): PlatformResult {
        val response = try {
            client.put(route.api("/api/v1/devices/me/platform")) {
                header("Authorization", "Bearer ${token()}")
                contentType(ContentType.Application.Json)
                setBody("""{"platform":"$platform"}""")
            }
        } catch (e: Throwable) {
            return PlatformResult.NoConnection(classifyFailure(e))
        }
        val body = response.jsonBody()
        return when {
            response.status == HttpStatusCode.OK ->
                PlatformResult.Declared(body?.str("platform") ?: platform)
            else -> PlatformResult.Refused(response.status.value, body.codeOf())
        }
    }
}

/** Чем закончилось самообъявление платформы. */
sealed interface PlatformResult {

    /**
     * @param platform как её понял сервер. Значение возвращается не для симметрии:
     *   сервер приводит объявленное к своему списку (`android`/`ios`/`desktop`), и
     *   всё незнакомое становится пустой строкой — то есть отказом.
     */
    data class Declared(val platform: String) : PlatformResult

    data class Refused(val status: Int, val code: String) : PlatformResult
    data class NoConnection(val link: LinkState) : PlatformResult
}
