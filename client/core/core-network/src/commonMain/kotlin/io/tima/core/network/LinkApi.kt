package io.tima.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType

/**
 * Привязка нового устройства — три ручки и **две роли**.
 *
 * Роли разведены по классам, потому что они разведены по правам, и это не деталь
 * реализации:
 *
 * - **Новое устройство** аккаунта ещё не имеет, токена у него нет: `link/start` и
 *   `link/claim` идут без авторизации, а пропуском служит `claim_token`, выданный
 *   в ответе на `start` — ровно как `registration_token` при обычной регистрации.
 * - **Доверенное устройство** сканирует код и подтверждает: `link/confirm` авторизован,
 *   и подтвердить вправе **только телефон** (`key-lifecycle.md §2`).
 *
 * Дай одному классу и то и другое — и однажды новое устройство пошлёт токен, которого у
 * него нет, а выяснится это отказом сервера.
 */
class LinkStartApi(
    private val route: ServerRoute,
    private val client: HttpClient,
) {

    /**
     * `POST /api/v1/link/start` — новое устройство просит код.
     *
     * Ключи те же, что при обычной регистрации: устройство — это пара X25519 + Ed25519.
     * Доверие принесёт `confirm` с другого устройства, поэтому здесь нет ни SMS, ни фразы.
     */
    suspend fun start(
        encryptionPub: ByteArray,
        signingPub: ByteArray,
        deviceName: String,
    ): LinkStartResult {
        val response = try {
            client.post(route.api("/api/v1/link/start")) {
                contentType(ContentType.Application.Json)
                setBody(
                    """{"encryption_pub":"${encodeBase64Url(encryptionPub)}",""" +
                        """"signing_pub":"${encodeBase64Url(signingPub)}",""" +
                        """"device_name":"$deviceName"}""",
                )
            }
        } catch (e: Throwable) {
            return LinkStartResult.NoConnection(classifyFailure(e))
        }
        val body = response.jsonBody()
        if (!response.status.value.let { it in 200..299 }) {
            return LinkStartResult.Refused(response.status.value, body.codeOf())
        }
        val sessionId = body?.str("session_id")
        val payload = body?.str("qr_payload")
        val claim = body?.str("claim_token")
        return if (sessionId == null || payload == null || claim == null) {
            LinkStartResult.Refused(response.status.value, "ответ без session_id/qr_payload/claim_token")
        } else {
            LinkStartResult.Started(sessionId, payload, claim, body.str("expires_at"))
        }
    }

    /**
     * `POST /api/v1/link/claim` — новое устройство спрашивает, подтвердили ли его.
     *
     * `not_ready` — **не ошибка, а нормальное состояние**: человек ещё не отсканировал код.
     * Отдельный исход нужен именно поэтому: показывать беду там, где надо просто ждать,
     * значит научить человека не верить сообщениям.
     */
    suspend fun claim(sessionId: String, claimToken: String): LinkClaimResult {
        val response = try {
            client.post(route.api("/api/v1/link/claim")) {
                contentType(ContentType.Application.Json)
                setBody("""{"session_id":"$sessionId","claim_token":"$claimToken"}""")
            }
        } catch (e: Throwable) {
            return LinkClaimResult.NoConnection(classifyFailure(e))
        }
        val body = response.jsonBody()
        val code = body.codeOf()
        return when {
            response.status == HttpStatusCode.OK -> {
                val userId = body?.str("user_id")
                val deviceId = body?.str("device_id")
                val token = body?.str("access_token")
                if (userId == null || deviceId == null || token == null) {
                    LinkClaimResult.Refused(response.status.value, "200 без user_id/device_id/access_token")
                } else {
                    LinkClaimResult.Claimed(userId, deviceId, token)
                }
            }
            code == "not_ready" -> LinkClaimResult.NotReady
            else -> LinkClaimResult.Refused(response.status.value, code)
        }
    }
}

/**
 * `POST /api/v1/link/confirm` — доверенное устройство подтверждает привязку.
 *
 * Подпись обязательна и делается **над данными из кода** (session_id, secret и ключи
 * нового устройства), а проверяет её сервер по ключу подтверждающего устройства, который
 * знает с его регистрации. Смысл не в лишнем барьере: если разбор кода на клиенте
 * разошёлся с тем, что реально лежит в сессии на сервере, подпись не сойдётся — то есть
 * расхождение проявится отказом, а не тихой привязкой не того устройства.
 */
class LinkConfirmApi(
    private val route: ServerRoute,
    private val client: HttpClient,
    private val token: () -> String,
) {

    suspend fun confirm(sessionId: String, secret: String, signature: ByteArray): LinkConfirmResult {
        val response = try {
            client.post(route.api("/api/v1/link/confirm")) {
                header("Authorization", "Bearer ${token()}")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"session_id":"$sessionId","secret":"$secret",""" +
                        """"signature":"${encodeBase64Url(signature)}"}""",
                )
            }
        } catch (e: Throwable) {
            return LinkConfirmResult.NoConnection(classifyFailure(e))
        }
        val body = response.jsonBody()
        val code = body.codeOf()
        return when {
            response.status == HttpStatusCode.OK ->
                LinkConfirmResult.Confirmed(body?.str("device_id") ?: "")

            // Каждый из трёх отказов означает для человека своё действие, и слипшись в
            // «не получилось» они стали бы неотличимы.
            code == "not_a_phone" -> LinkConfirmResult.NotAPhone
            code == "bad_session" -> LinkConfirmResult.SessionGone
            code == "bad_signature" -> LinkConfirmResult.BadSignature
            else -> LinkConfirmResult.Refused(response.status.value, code)
        }
    }
}

/** Чем закончился `link/start`. */
sealed interface LinkStartResult {
    data class Started(
        val sessionId: String,
        /** Строка для QR. Она же — то, что человек может перенести руками. */
        val qrPayload: String,
        /** Пропуск нового устройства к `claim`. Секрет: в журнал не пишется. */
        val claimToken: String,
        val expiresAt: String?,
    ) : LinkStartResult

    data class Refused(val status: Int, val code: String) : LinkStartResult
    data class NoConnection(val link: LinkState) : LinkStartResult
}

/** Чем закончился `link/claim`. */
sealed interface LinkClaimResult {
    data class Claimed(val userId: String, val deviceId: String, val accessToken: String) : LinkClaimResult

    /** Ещё не подтвердили. Нормальное состояние ожидания, а не отказ. */
    data object NotReady : LinkClaimResult
    data class Refused(val status: Int, val code: String) : LinkClaimResult
    data class NoConnection(val link: LinkState) : LinkClaimResult
}

/** Чем закончился `link/confirm`. */
sealed interface LinkConfirmResult {
    /** @param deviceId адрес нового устройства: на него подтвердивший перезаворачивает ключи истории. */
    data class Confirmed(val deviceId: String) : LinkConfirmResult

    /** Подтверждать вправе только телефон. Сервер отвечает человеческими словами сам. */
    data object NotAPhone : LinkConfirmResult

    /** Сессия не найдена, просрочена или уже подтверждена: код надо показать заново. */
    data object SessionGone : LinkConfirmResult

    /** Подпись не сошлась: разобранный код разошёлся с тем, что лежит на сервере. */
    data object BadSignature : LinkConfirmResult
    data class Refused(val status: Int, val code: String) : LinkConfirmResult
    data class NoConnection(val link: LinkState) : LinkConfirmResult
}
