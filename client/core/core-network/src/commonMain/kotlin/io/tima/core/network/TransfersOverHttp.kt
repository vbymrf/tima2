package io.tima.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.tima.domain.account.Session
import io.tima.domain.account.TransferAcceptStep
import io.tima.domain.account.TransferStartStep
import io.tima.domain.account.TransfersApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Передача виртуального аккаунта по HTTP (ПЛАН-КОНТАКТОВ.md, Д12).
 *
 * **403 здесь означает две разные вещи, и путать их нельзя.** «Фраза не подходит» — ещё
 * есть попытки; «код сгорел» — их больше нет, и нужен новый код от прежнего владельца.
 * Свести к одному значило бы предложить человеку пробовать снова там, где пробовать
 * нечего.
 */
class TransfersOverHttp(
    private val route: ServerRoute,
    private val client: HttpClient,
    private val token: () -> String,
) : TransfersApi {

    override suspend fun start(virtualUserId: String): TransferStartStep = try {
        val response = client.post(route.api("/api/v1/users/me/virtuals/$virtualUserId/transfer")) {
            header("Authorization", "Bearer ${token()}")
        }
        val body = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
        when (response.status) {
            HttpStatusCode.Created -> {
                val code = body?.get("code")?.jsonPrimitive?.content
                if (code.isNullOrBlank()) {
                    TransferStartStep.Offline
                } else {
                    TransferStartStep.Code(
                        code = code,
                        secondsLeft = body["expires_in_sec"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                        attempts = body["attempts_before"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    )
                }
            }
            // 403 «не ваш» и 404 «нет такого» для человека одно: аккаунта, которым он
            // распоряжается, здесь нет.
            HttpStatusCode.Forbidden, HttpStatusCode.NotFound -> TransferStartStep.NotYours
            else -> TransferStartStep.Offline
        }
    } catch (_: Throwable) {
        TransferStartStep.Offline
    }

    override suspend fun cancel(virtualUserId: String): Boolean = try {
        client.delete(route.api("/api/v1/users/me/virtuals/$virtualUserId/transfer")) {
            header("Authorization", "Bearer ${token()}")
        }.status == HttpStatusCode.NoContent
    } catch (_: Throwable) {
        false
    }

    override suspend fun accept(
        code: String,
        proof: ByteArray,
        encryptionPub: ByteArray,
        signingPub: ByteArray,
        platform: String,
    ): TransferAcceptStep = try {
        val response = client.post(route.api("/api/v1/transfers/accept")) {
            header("Authorization", "Bearer ${token()}")
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("code", JsonPrimitive(code))
                    put("proof", JsonPrimitive(encodeBase64Url(proof)))
                    put("encryption_pub", JsonPrimitive(encodeBase64Url(encryptionPub)))
                    put("signing_pub", JsonPrimitive(encodeBase64Url(signingPub)))
                    put("platform", JsonPrimitive(platform))
                }.toString(),
            )
        }
        val body = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
        when (response.status) {
            HttpStatusCode.OK -> {
                val userId = body?.get("user_id")?.jsonPrimitive?.content
                val deviceId = body?.get("device_id")?.jsonPrimitive?.content
                val access = body?.get("access_token")?.jsonPrimitive?.content
                if (userId.isNullOrBlank() || deviceId.isNullOrBlank() || access.isNullOrBlank()) {
                    // Аккаунт перешёл, а войти в него нечем. Сказать «готово» тут нельзя:
                    // человек увидел бы успех и пустоту.
                    TransferAcceptStep.Offline
                } else {
                    TransferAcceptStep.Taken(
                        session = Session(userId, deviceId, access),
                        rotateNeeded = body["rotate_needed"]?.jsonPrimitive?.content == "true",
                    )
                }
            }
            HttpStatusCode.NotFound -> TransferAcceptStep.CodeGone
            HttpStatusCode.Forbidden -> when (body?.get("error")?.jsonPrimitive?.content) {
                "transfer_burned" -> TransferAcceptStep.Burned
                else -> TransferAcceptStep.BadPhrase
            }
            // 400 — код не разбирается: для человека это «не тот код», а не поломка.
            HttpStatusCode.BadRequest -> TransferAcceptStep.CodeGone
            else -> TransferAcceptStep.Offline
        }
    } catch (_: Throwable) {
        TransferAcceptStep.Offline
    }
}
