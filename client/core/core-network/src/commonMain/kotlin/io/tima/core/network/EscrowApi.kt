package io.tima.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Ключ эпохи escrow для переписки — `GET /api/v1/escrow/key?chat_id=…`.
 *
 * **Что здесь делается и чего не делается.** Здесь только достаются поля. **Подпись
 * анклава проверяется не здесь**, а в `core-encryption`: раскладка подписываемых байт
 * — знание криптографического модуля (`EscrowConfigSignature`), и повторять её в
 * сетевом слое значило бы держать её в двух местах, откуда она однажды разойдётся.
 *
 * Возвращаются **сырые** поля вместе с подписью: тот, кто проверяет, обязан видеть
 * ровно то, что пришло. Собери из них «удобный» объект — и проверять станет нечего.
 *
 * Контракт из `internal/api/escrow.go`:
 * ```
 * {"region":"ru","current":{"id":7,"epoch":"2026-08","public_key":"…","signature":"…",
 *  "valid_from":"…RFC3339…","valid_to":"…","destroy_at":"…"},"next":{…}}
 * ```
 * **`region` лежит НАРУЖИ ключа, а `chat_id` не приходит вовсе** — и то и другое
 * входит в подписываемые байты. Значит собирать их для проверки обязан клиент: region
 * из верхнего уровня, `chat_id` — из своего же запроса. Пропусти это, и подпись не
 * сойдётся, а выглядеть будет как «анклав подписывает неправильно».
 */
class EscrowApi(
    private val route: ServerRoute,
    private val client: HttpClient,
    private val token: () -> String,
) {

    suspend fun keyForChat(chatId: String): EscrowKeyResult {
        require(chatId.isNotBlank()) { "chat_id пустой" }
        val response = try {
            client.get(route.api("/api/v1/escrow/key")) {
                header("Authorization", "Bearer ${token()}")
                parameter("chat_id", chatId)
            }
        } catch (e: Throwable) {
            return EscrowKeyResult.Offline(classifyFailure(e))
        }

        val body = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
        val code = body?.get("code")?.jsonPrimitive?.content

        if (response.status != HttpStatusCode.OK) {
            // 503 no_escrow означает, что анклав не сконфигурирован. Отдельный исход:
            // без анклава отправка невозможна вообще, и это не «попробуем позже».
            return if (code == "no_escrow") {
                EscrowKeyResult.NoEnclave
            } else {
                EscrowKeyResult.Refused(response.status.value, code ?: "без кода")
            }
        }

        val region = body?.get("region")?.jsonPrimitive?.content
            ?: return EscrowKeyResult.Refused(response.status.value, "ответ без region")
        val current = runCatching { body["current"]!!.jsonObject.toSigned(region, chatId) }
            .getOrElse { return EscrowKeyResult.Refused(response.status.value, "current не разобран: ${it.message}") }
        val next = runCatching { body["next"]?.jsonObject?.toSigned(region, chatId) }.getOrNull()

        return EscrowKeyResult.Keys(current, next)
    }

    private fun JsonObject.toSigned(region: String, chatId: String): SignedEscrowKey {
        val publicKey = decodeBase64Url(this["public_key"]!!.jsonPrimitive.content)
        val signature = decodeBase64Url(this["signature"]!!.jsonPrimitive.content)
        require(publicKey != null && signature != null) { "public_key или signature не base64url" }
        return SignedEscrowKey(
            id = this["id"]!!.jsonPrimitive.content.toLong(),
            region = region,
            chatId = chatId,
            epoch = this["epoch"]!!.jsonPrimitive.content,
            publicKey = publicKey,
            signature = signature,
            // Усечение, а не округление: анклав считал миллисекунды из своего времени
            // тем же усечением (Go UnixMilli). Округли вверх — и подписываемые байты
            // разойдутся на единицу, а подпись не сойдётся.
            validFromMs = millisOf(this["valid_from"]!!.jsonPrimitive.content),
            validToMs = millisOf(this["valid_to"]!!.jsonPrimitive.content),
            destroyAtMs = millisOf(this["destroy_at"]!!.jsonPrimitive.content),
        )
    }

    private fun millisOf(rfc3339: String): Long = Instant.parse(rfc3339).toEpochMilliseconds()
}

/**
 * Ключ эпохи как он пришёл — вместе с подписью и со всем, что в неё входит.
 *
 * `region` и [chatId] здесь потому, что подпись их покрывает, а в самом объекте ключа
 * сервер их не присылает: первый лежит уровнем выше, второй известен только нам.
 */
data class SignedEscrowKey(
    /** Он же `escrow_key_version` в конверте. */
    val id: Long,
    val region: String,
    val chatId: String,
    val epoch: String,
    /** ML-KEM-768, 1184 байта. */
    val publicKey: ByteArray,
    /** Ed25519 анклава, 64 байта. */
    val signature: ByteArray,
    val validFromMs: Long,
    val validToMs: Long,
    /** Когда анклав уничтожит приватную часть. Входит в подпись (Р2). */
    val destroyAtMs: Long,
) {
    override fun equals(other: Any?): Boolean = other is SignedEscrowKey &&
        id == other.id && region == other.region && chatId == other.chatId &&
        epoch == other.epoch && publicKey.contentEquals(other.publicKey) &&
        signature.contentEquals(other.signature) && validFromMs == other.validFromMs &&
        validToMs == other.validToMs && destroyAtMs == other.destroyAtMs

    override fun hashCode(): Int {
        var h = id.hashCode()
        h = 31 * h + region.hashCode()
        h = 31 * h + chatId.hashCode()
        h = 31 * h + epoch.hashCode()
        h = 31 * h + publicKey.contentHashCode()
        h = 31 * h + signature.contentHashCode()
        h = 31 * h + validFromMs.hashCode()
        h = 31 * h + validToMs.hashCode()
        h = 31 * h + destroyAtMs.hashCode()
        return h
    }
}

sealed interface EscrowKeyResult {
    /** @param next следующая эпоха, если сервер её уже знает. */
    data class Keys(val current: SignedEscrowKey, val next: SignedEscrowKey?) : EscrowKeyResult

    /**
     * Анклав не сконфигурирован (`503 no_escrow`).
     *
     * Отдельный исход, а не «попробуем позже»: без анклава отправка невозможна в
     * принципе, и человеку надо сказать не «нет сети», а другое.
     */
    data object NoEnclave : EscrowKeyResult
    data class Refused(val status: Int, val code: String) : EscrowKeyResult
    data class Offline(val link: LinkState) : EscrowKeyResult
}
