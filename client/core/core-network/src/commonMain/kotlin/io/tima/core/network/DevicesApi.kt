package io.tima.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
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
     * `GET /api/v1/devices` — свои действующие устройства.
     *
     * Только свои и намеренно: чужой список устройств — это карта того, чем человек
     * пользуется и когда.
     */
    suspend fun mine(): MyDevicesResult {
        val response = try {
            client.get(route.api("/api/v1/devices")) {
                header("Authorization", "Bearer ${token()}")
            }
        } catch (e: Throwable) {
            return MyDevicesResult.NoConnection(classifyFailure(e))
        }
        val body = response.jsonBody()
        if (response.status != HttpStatusCode.OK) {
            return MyDevicesResult.Refused(response.status.value, body.codeOf())
        }
        val список = body?.get("devices")?.jsonArrayOrNull()?.mapNotNull { элемент ->
            val объект = элемент.jsonObjectOrNull() ?: return@mapNotNull null
            val id = объект.str("device_id") ?: return@mapNotNull null
            MyDevice(
                deviceId = id,
                name = объект.str("name").orEmpty(),
                createdAt = объект.str("created_at"),
                current = объект.bool("current") == true,
            )
        }
        return список?.let { MyDevicesResult.Devices(it) }
            ?: MyDevicesResult.Refused(response.status.value, "ответ без devices")
    }

    /**
     * `DELETE /api/v1/devices/{id}` — отозвать своё устройство.
     *
     * Последнее отозвать нельзя: аккаунт остался бы без единой точки входа. Сервер это
     * запрещает сам, и отдельный исход [RevokeResult.LastDevice] нужен, чтобы сказать
     * человеку именно это, а не «не получилось».
     */
    suspend fun revoke(deviceId: String): RevokeResult {
        val response = try {
            client.delete(route.api("/api/v1/devices/$deviceId")) {
                header("Authorization", "Bearer ${token()}")
            }
        } catch (e: Throwable) {
            return RevokeResult.NoConnection(classifyFailure(e))
        }
        val body = response.jsonBody()
        val code = body.codeOf()
        return when {
            response.status == HttpStatusCode.OK -> RevokeResult.Revoked
            code == "last_device" -> RevokeResult.LastDevice
            code == "device_not_found" -> RevokeResult.Gone
            else -> RevokeResult.Refused(response.status.value, code)
        }
    }

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

/** Устройство аккаунта в списке. */
class MyDevice(
    val deviceId: String,
    /** Имя, которое устройство назвало при регистрации или привязке. */
    val name: String,
    val createdAt: String?,
    /** Это устройство. Отзывать его — значит выходить из аккаунта здесь. */
    val current: Boolean,
)

/** Чем закончился запрос списка устройств. */
sealed interface MyDevicesResult {
    data class Devices(val devices: List<MyDevice>) : MyDevicesResult
    data class Refused(val status: Int, val code: String) : MyDevicesResult
    data class NoConnection(val link: LinkState) : MyDevicesResult
}

/** Чем закончился отзыв. */
sealed interface RevokeResult {
    data object Revoked : RevokeResult

    /** Последнее устройство аккаунта: отозвать нельзя, иначе входить будет нечем. */
    data object LastDevice : RevokeResult

    /** Уже отозвано или не наше. */
    data object Gone : RevokeResult
    data class Refused(val status: Int, val code: String) : RevokeResult
    data class NoConnection(val link: LinkState) : RevokeResult
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
