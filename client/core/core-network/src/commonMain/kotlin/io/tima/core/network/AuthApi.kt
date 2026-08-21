package io.tima.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Регистрация устройства — К4.3, водопровод без экранов.
 *
 * **Контракт снят с кода сервера** (`internal/api/auth.go`), потому что сверка Д3
 * показала: каталог API описывает 26 маршрутов из 71, и эти три в описанные не входят.
 *
 * ```
 * POST /api/v1/auth/sms/request  {phone}                      → {request_id, dev_code?}
 * POST /api/v1/auth/sms/verify   {request_id, code}            → {registration_token}
 * POST /api/v1/auth/register     {registration_token, ключи…}  → 201 {user_id, device_id, access_token}
 * ```
 *
 * **Ключи едут base64url БЕЗ выравнивания.** Сервер разбирает их
 * `base64.RawURLEncoding`, и обычный base64 — с `+`, `/` и `=` — он не примет: ответ
 * будет `400 bad_keys`, а выглядеть это будет как «сервер не принимает наши ключи».
 * Ошибка ровно того рода, которую невозможно найти по сообщению.
 *
 * Здесь только вызовы. Порождение ключей устройства, запись токена в
 * `SecretVault` и решение «регистрироваться или входить» — не здесь: это склейка, и
 * она принадлежит слою выше (К4.2).
 */
class AuthApi(
    private val route: ServerRoute,
    private val client: HttpClient,
) {

    /**
     * Запрос кода. Телефон проверяется **до отправки**: правило сервера известно
     * (`^\+[1-9][0-9]{7,14}$`), и гонять запрос ради заведомого `400` — это трата
     * времени человека, стоящего перед полем ввода.
     */
    suspend fun requestSms(phone: String): SmsRequestResult {
        if (!PHONE.matches(phone)) {
            return SmsRequestResult.BadPhone("ожидается E.164: плюс и от 8 до 15 цифр")
        }
        val response = try {
            client.post(route.api("/api/v1/auth/sms/request")) {
                contentType(ContentType.Application.Json)
                setBody("""{"phone":"$phone"}""")
            }
        } catch (e: Throwable) {
            return SmsRequestResult.NoConnection(classifyFailure(e))
        }
        val body = response.jsonBody()
        return when {
            response.status.isSuccess() -> body?.str("request_id")
                ?.let { SmsRequestResult.Sent(it, devCode = body.str("dev_code")) }
                ?: SmsRequestResult.Refused(response.status.value, "ответ без request_id")

            else -> SmsRequestResult.Refused(response.status.value, body.codeOf())
        }
    }

    /**
     * Проверка кода.
     *
     * `403 bad_code` — **исход, а не беда**: неверный код это обычное поведение
     * человека, и исключением его делать значит заставлять каждый экран ловить
     * исключение вместо разбора случая.
     */
    suspend fun verifySms(requestId: String, code: String): SmsVerifyResult {
        require(requestId.isNotBlank()) { "request_id пустой" }
        require(code.isNotBlank()) { "код пустой" }
        val response = try {
            client.post(route.api("/api/v1/auth/sms/verify")) {
                contentType(ContentType.Application.Json)
                setBody("""{"request_id":"$requestId","code":"$code"}""")
            }
        } catch (e: Throwable) {
            return SmsVerifyResult.NoConnection(classifyFailure(e))
        }
        val body = response.jsonBody()
        return when {
            response.status.isSuccess() -> body?.str("registration_token")
                ?.let { SmsVerifyResult.Verified(it) }
                ?: SmsVerifyResult.Refused(response.status.value, "ответ без registration_token")

            response.status == HttpStatusCode.Forbidden && body.codeOf() == "bad_code" ->
                SmsVerifyResult.BadCode
            else -> SmsVerifyResult.Refused(response.status.value, body.codeOf())
        }
    }

    /**
     * Заведение устройства.
     *
     * @param encryptionPub X25519, ровно 32 байта.
     * @param signingPub Ed25519, ровно 32 байта.
     * @param identityPub ключ личности из фразы аккаунта. `null` для нового аккаунта;
     *   для возврата на новом устройстве он и решает, что личность та же.
     * @param platform для показа в списке устройств. Не проверяется сервером.
     */
    suspend fun register(
        registrationToken: String,
        encryptionPub: ByteArray,
        signingPub: ByteArray,
        identityPub: ByteArray? = null,
        platform: String? = null,
    ): RegisterResult {
        require(encryptionPub.size == KEY_BYTES) { "encryption_pub обязан быть $KEY_BYTES байт" }
        require(signingPub.size == KEY_BYTES) { "signing_pub обязан быть $KEY_BYTES байт" }
        require(identityPub == null || identityPub.size == KEY_BYTES) {
            "identity_pub обязан быть $KEY_BYTES байт"
        }

        val поля = buildList {
            add(""""registration_token":"$registrationToken"""")
            add(""""encryption_pub":"${encodeBase64Url(encryptionPub)}"""")
            add(""""signing_pub":"${encodeBase64Url(signingPub)}"""")
            if (identityPub != null) add(""""identity_pub":"${encodeBase64Url(identityPub)}"""")
            if (platform != null) add(""""platform":"$platform"""")
        }
        val response = try {
            client.post(route.api("/api/v1/auth/register")) {
                contentType(ContentType.Application.Json)
                setBody(поля.joinToString(",", "{", "}"))
            }
        } catch (e: Throwable) {
            return RegisterResult.NoConnection(classifyFailure(e))
        }
        val body = response.jsonBody()
        val code = body.codeOf()
        return when {
            response.status == HttpStatusCode.Created -> {
                val userId = body?.str("user_id")
                val deviceId = body?.str("device_id")
                val token = body?.str("access_token")
                if (userId == null || deviceId == null || token == null) {
                    RegisterResult.Refused(response.status.value, "201 без user_id/device_id/access_token")
                } else {
                    RegisterResult.Registered(userId, deviceId, token)
                }
            }
            // Телефон принадлежит другой личности. Не ошибка сети и не «неверный код»:
            // это встреча с собственным прошлым аккаунтом, и решать её человеку.
            code == "identity_mismatch" -> RegisterResult.IdentityMismatch

            // registration_token живёт минуты: истёк — значит начинать с кода заново.
            code == "bad_token" -> RegisterResult.TokenExpired

            else -> RegisterResult.Refused(response.status.value, code)
        }
    }

    private suspend fun HttpResponse.jsonBody(): JsonObject? =
        runCatching { Json.parseToJsonElement(bodyAsText()) as JsonObject }.getOrNull()

    private fun JsonObject?.codeOf(): String = this?.str("code") ?: "без кода"

    private fun JsonObject.str(name: String): String? =
        runCatching { this[name]?.jsonPrimitive?.content }.getOrNull()

    private fun HttpStatusCode.isSuccess(): Boolean = value in 200..299

    private companion object {
        val PHONE = Regex("""^\+[1-9][0-9]{7,14}$""")
        const val KEY_BYTES = 32
    }
}

/** Чем закончился запрос кода. */
sealed interface SmsRequestResult {
    /**
     * @param devCode код прямо в ответе — **только** когда на сервере включён
     *   `TIMA_DEV_SMS`. На стенде это так, и харнесс К4 живёт именно этим: иначе
     *   сквозной путь требовал бы настоящей SMS на настоящий телефон.
     */
    data class Sent(val requestId: String, val devCode: String? = null) : SmsRequestResult
    data class BadPhone(val reason: String) : SmsRequestResult
    data class Refused(val status: Int, val code: String) : SmsRequestResult
    data class NoConnection(val link: LinkState) : SmsRequestResult
}

/** Чем закончилась проверка кода. */
sealed interface SmsVerifyResult {
    data class Verified(val registrationToken: String) : SmsVerifyResult

    /** Код неверен, просрочен или уже использован — сервер не различает эти три. */
    data object BadCode : SmsVerifyResult
    data class Refused(val status: Int, val code: String) : SmsVerifyResult
    data class NoConnection(val link: LinkState) : SmsVerifyResult
}

/** Чем закончилось заведение устройства. */
sealed interface RegisterResult {
    data class Registered(
        val userId: String,
        val deviceId: String,
        /** JWT устройства. Его место — `SecretVault`, а не файл настроек. */
        val accessToken: String,
    ) : RegisterResult

    /** Телефон уже связан с другой личностью: путь возврата, а не ошибка. */
    data object IdentityMismatch : RegisterResult

    /** `registration_token` просрочен: начинать с запроса кода. */
    data object TokenExpired : RegisterResult
    data class Refused(val status: Int, val code: String) : RegisterResult
    data class NoConnection(val link: LinkState) : RegisterResult
}
