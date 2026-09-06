package io.tima.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Обновление токена доступа подписью устройства.
 *
 * **Зачем это вообще есть.** Токен живёт сутки, и до 2026-09-06 получить новый было
 * неоткуда: приложение молча упиралось в `401` на каждой ручке под токеном, а человек
 * видел не «войдите снова», а просто неработающее приложение. Поймано первым живым
 * отчётом о проблеме (ПЛАН-ОТЛАДКИ.md §6).
 *
 * **Ручка без токена — по необходимости.** Её зовут ровно тогда, когда прежний токен уже
 * не принимается; требовать для неё токен значило бы требовать то, за чем сюда и пришли.
 * Доказательство здесь другое — подпись ключом устройства, открытая часть которого лежит
 * у сервера с момента регистрации.
 */
class DeviceTokenApi(
    private val route: ServerRoute,
    private val client: HttpClient,
) {

    /**
     * @param issuedAt метка времени в секундах эпохи. Сервер принимает её в окне ±2
     *   минуты: подпись без срока годилась бы вечно, и перехваченная однажды давала бы
     *   токены всегда.
     */
    suspend fun renew(
        userId: String,
        deviceId: String,
        issuedAt: Long,
        signature: ByteArray,
    ): DeviceTokenResult {
        val response = try {
            client.post(route.api("/api/v1/auth/device/token")) {
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("user_id", JsonPrimitive(userId))
                        put("device_id", JsonPrimitive(deviceId))
                        put("issued_at", JsonPrimitive(issuedAt))
                        put("signature", JsonPrimitive(encodeBase64Url(signature)))
                    }.toString(),
                )
            }
        } catch (e: Throwable) {
            return DeviceTokenResult.NoConnection(classifyFailure(e))
        }
        val body = response.jsonBody()
        if (response.status != HttpStatusCode.OK) {
            val code = body.codeOf()
            // Отозванное устройство — не «попробуйте позже», а конец: у этого устройства
            // доступа больше нет, и человеку придётся заводить его заново.
            return if (code == "device_revoked") DeviceTokenResult.Revoked
            else DeviceTokenResult.Refused(response.status.value, code)
        }
        val token = body?.str("access_token").orEmpty()
        return if (token.isBlank()) DeviceTokenResult.Refused(response.status.value, "ответ без токена")
        else DeviceTokenResult.Renewed(token)
    }
}

/** Чем кончилось обновление токена. */
sealed interface DeviceTokenResult {
    data class Renewed(val accessToken: String) : DeviceTokenResult

    /** Устройство отозвано или неизвестно серверу — обновлять больше нечего. */
    data object Revoked : DeviceTokenResult

    data class NoConnection(val link: LinkState) : DeviceTokenResult

    data class Refused(val status: Int, val code: String) : DeviceTokenResult
}

/**
 * Что подписывает устройство. Раскладка **нормативна**: она зеркалит
 * `deviceTokenSigningBytes` на сервере байт-в-байт.
 *
 * Домен-разделитель стоит первым и обязателен: без него подпись, снятая здесь, годилась
 * бы везде, где подписывается похожий набор полей.
 */
fun deviceTokenSigningBytes(userId: String, deviceId: String, issuedAt: Long): ByteArray =
    ("tima:device-token:v1\n$userId\n$deviceId\n$issuedAt").encodeToByteArray()
