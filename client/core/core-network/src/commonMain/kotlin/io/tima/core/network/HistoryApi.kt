package io.tima.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.longOrNull

/**
 * История личных переписок на новом устройстве (ПЛАН-(ДУ+ИУ)-УСТРОЙСТВ-И-ИСТОРИИ ИУ1–ИУ3).
 *
 * Три ручки одной работы: какие у меня переписки, страница истории под это устройство, и
 * передача перезавёрнутых ключей от своего доверенного устройства новому.
 */
class HistoryApi(
    private val route: ServerRoute,
    private val client: HttpClient,
    private val token: () -> String,
) {

    /**
     * Личная переписка: с кем и докуда. [ownerId] — чья она из личностей аккаунта (М6): пусто —
     * своя, иначе прежней личности, чью копию новая перенесла к себе.
     */
    class ChatRef(val chatId: String, val peerId: String, val lastMessageId: Long, val ownerId: String = "")

    /** Сообщение истории: конверт с обёрткой под это устройство и эфемерал обёртки. */
    class Item(val messageId: Long, val envelope: ByteArray, val wrapEphemeral: ByteArray?)

    /** Ключ одного сообщения под новое устройство. */
    class Key(val messageId: Long, val ephemeralPub: ByteArray, val wrapped: ByteArray)

    /**
     * `GET /api/v1/chats/personal` (ИУ1).
     *
     * @return `null` — сеть или отказ (в строгом режиме незаверенному списка не дают).
     */
    suspend fun personalChats(allIdentities: Boolean = false): List<ChatRef>? {
        // `all=1` — переписки всех личностей аккаунта с отметкой владельца (М6, Р55).
        val path = "/api/v1/chats/personal" + if (allIdentities) "?all=1" else ""
        val response = try {
            client.get(route.api(path)) { header("Authorization", "Bearer ${token()}") }
        } catch (e: Throwable) {
            return null
        }
        if (response.status != HttpStatusCode.OK) return null
        val list = response.jsonBody()?.get("chats")?.jsonArrayOrNull() ?: return null
        return list.mapNotNull { el ->
            val o = el.jsonObjectOrNull() ?: return@mapNotNull null
            ChatRef(
                chatId = o.str("chat_id") ?: return@mapNotNull null,
                peerId = o.str("peer_id") ?: return@mapNotNull null,
                lastMessageId = o.long("last_message_id") ?: 0,
                ownerId = o.str("owner_id").orEmpty(),
            )
        }
    }

    /**
     * `GET /api/v1/chats/{id}/messages` — страница истории, новые первыми.
     *
     * Сервер отдаёт только сообщения, для которых у ЭТОГО устройства есть обёртка ключа.
     *
     * @param before отдать старше этого номера; ноль — с самого нового.
     * @return `null` — сеть или отказ.
     */
    suspend fun page(chatId: String, before: Long, limit: Int = PAGE): List<Item>? {
        val path = "/api/v1/chats/$chatId/messages?limit=$limit" + if (before > 0) "&before=$before" else ""
        val response = try {
            client.get(route.api(path)) { header("Authorization", "Bearer ${token()}") }
        } catch (e: Throwable) {
            return null
        }
        if (response.status != HttpStatusCode.OK) return null
        val list = response.jsonBody()?.get("messages")?.jsonArrayOrNull() ?: return null
        return list.mapNotNull { el ->
            val o = el.jsonObjectOrNull() ?: return@mapNotNull null
            Item(
                messageId = o.long("message_id") ?: return@mapNotNull null,
                envelope = o.str("envelope")?.let { decodeBase64Url(it) } ?: return@mapNotNull null,
                wrapEphemeral = o.str("wrap_ephemeral")?.let { decodeBase64Url(it) },
            )
        }
    }

    /**
     * `POST /api/v1/chats/{id}/recover/provide` — отдать ключи сообщений своему новому
     * устройству (ИУ2).
     *
     * @return сколько сервер принял; `null` — сеть или отказ.
     */
    suspend fun provide(chatId: String, requesterDevice: String, keys: List<Key>): Int? {
        if (keys.isEmpty()) return 0
        val body = buildString {
            append("{\"requester_device\":\"").append(requesterDevice).append("\",\"keys\":[")
            keys.forEachIndexed { i, k ->
                if (i > 0) append(',')
                append("{\"message_id\":").append(k.messageId)
                append(",\"sender_ephemeral_pub\":\"").append(encodeBase64Url(k.ephemeralPub))
                append("\",\"wrapped\":\"").append(encodeBase64Url(k.wrapped)).append("\"}")
            }
            append("]}")
        }
        val response = try {
            client.post(route.api("/api/v1/chats/$chatId/recover/provide")) {
                header("Authorization", "Bearer ${token()}")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        } catch (e: Throwable) {
            return null
        }
        if (response.status != HttpStatusCode.Created) return null
        return response.jsonBody()?.int("saved") ?: keys.size
    }

    /** Чем кончилась просьба о ключах переписки. */
    sealed interface Recover {
        /**
         * Сервер разобрал просьбу (2026-10-06): [missing] сообщений вернут [helpers] устройств;
         * к [ready] ключ на сервере уже есть — история догонится сама (`recovery.msg_ready`);
         * [lost] — названные, к которым ключа не осталось ни у кого: просить их снова незачем.
         */
        data class Asked(
            val helpers: Int,
            val missing: Int = 0,
            val ready: Int = 0,
            val lost: List<Long> = emptyList(),
        ) : Recover
        data class Refused(val status: Int, val code: String) : Recover
        data object Offline : Recover
    }

    /**
     * `POST /api/v1/chats/{id}/recover` — попросить ключи сообщений переписки у своих устройств
     * и собеседника (2026-10-06: «сообщение недоступно, запросить»). Заверенному устройству
     * подпись фразой не нужна; незаверенное подписывает ([signature] — base64url,
     * `RecoverySignature.sign`), без неё сервер отвечает `bad_identity_sig`.
     *
     * @param named номера сообщений, которые устройство видит недоступными, новые первыми. Их
     *   кладётся столько, сколько влезает в [RECOVER_BODY_BYTES] — предел тела у сервера:
     *   число решает размер, а не счёт (заказчик 2026-10-06).
     */
    suspend fun recover(chatId: String, signature: String? = null, named: List<Long> = emptyList()): Recover {
        val response = try {
            client.post(route.api("/api/v1/chats/$chatId/recover")) {
                header("Authorization", "Bearer ${token()}")
                contentType(ContentType.Application.Json)
                setBody(recoverBody(signature, named))
            }
        } catch (e: Throwable) {
            return Recover.Offline
        }
        val body = response.jsonBody()
        if (response.status != HttpStatusCode.OK) return Recover.Refused(response.status.value, body.codeOf())
        val lost = body?.get("lost")?.jsonArrayOrNull()?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull }.orEmpty()
        return Recover.Asked(body?.int("helpers") ?: 0, body?.int("missing") ?: 0, body?.int("ready") ?: 0, lost)
    }

    companion object {
        /** Предел тела просьбы о ключах — тот же, что у сервера (`recoverBodyLimit`). */
        const val RECOVER_BODY_BYTES: Int = 4096

        /** Тело просьбы: подпись и столько номеров, сколько влезает в [RECOVER_BODY_BYTES]. */
        fun recoverBody(signature: String?, named: List<Long>): String {
            val head = if (signature == null) "{\"missing\":[" else "{\"signature\":\"$signature\",\"missing\":["
            val out = StringBuilder(head)
            var first = true
            for (id in named) {
                val part = (if (first) "" else ",") + id
                if (out.length + part.length + 2 > RECOVER_BODY_BYTES) break
                out.append(part)
                first = false
            }
            return out.append("]}").toString()
        }

        /** Страница истории: столько же отдаёт сервер по умолчанию, больше 200 он не даст. */
        const val PAGE: Int = 100
    }
}
