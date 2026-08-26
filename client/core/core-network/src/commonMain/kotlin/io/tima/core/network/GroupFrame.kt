package io.tima.core.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Пришедшее сообщение группы — то, что кладётся в местное хранилище как есть.
 *
 * ── ПОЧЕМУ ХРАНИМ КАДР ЦЕЛИКОМ, А НЕ РАЗОБРАННЫМ ────────────────────────────
 *
 * Порядок «сначала записать, потом разбирать» — правило хранилища, и оно не про
 * аккуратность: разбор падает по любой причине (нет ключа версии, испорченные байты,
 * ошибка в нашем коде), а живой канал сообщение больше не пришлёт. Значит записать надо
 * до всякой попытки понять.
 *
 * У личного сообщения для этого есть конверт — самодостаточные байты. У группового такого
 * конверта нет: подпись проверяется по метаданным вместе с payload, и без метаданных
 * сообщение не открыть и не проверить. Поэтому сохраняется весь кадр.
 *
 * ── ПОЧЕМУ ВЕДУЩИЙ НОЛЬ ──────────────────────────────────────────────────────
 *
 * В одном столбце теперь лежат два разных вида байт: protobuf-конверт личного сообщения
 * и JSON-кадр группового. Различать их «по первому символу `{`» нельзя — protobuf может
 * начаться с того же байта, и тогда личное сообщение однажды прочтётся как групповое.
 *
 * Ноль в начале protobuf-сообщения невозможен: первый байт — тег поля, а поля с номером
 * ноль в protobuf не бывает. Поэтому нулевой байт — надёжный признак, а не догадка.
 */
class GroupFrame(
    val groupId: String,
    val messageId: Long,
    val senderId: String,
    val senderDevice: String,
    val kind: Int,
    val gkVersion: Int,
    val payload: ByteArray,
    val signature: ByteArray,
    val createdAtUnixMs: Long,
    val threadRoot: Long,
    val replyTo: Long,
) {

    companion object {
        /** Признак группового кадра. В protobuf невозможен: поля с номером ноль нет. */
        const val LABEL: Byte = 0

        /** Кадр как он хранится: метка и исходный JSON сервера. */
        fun toStored(frameJson: String): ByteArray = byteArrayOf(LABEL) + frameJson.encodeToByteArray()

        /** Групповой ли это кадр. Дешёвая проверка перед разбором. */
        fun isGroupFrame(stored: ByteArray): Boolean = stored.isNotEmpty() && stored[0] == LABEL

        /**
         * Разбирает сохранённый кадр.
         *
         * `null` — байты не наши либо неполные. Вызывающий обязан показать такую строку
         * нечитаемой, а не молчать: сообщение в базе есть, и притвориться, что его нет,
         * значит соврать человеку.
         */
        fun parse(stored: ByteArray): GroupFrame? {
            if (!isGroupFrame(stored) || stored.size < 2) return null
            val json = runCatching {
                Json.parseToJsonElement(stored.decodeToString(1, stored.size)) as JsonObject
            }.getOrNull() ?: return null
            return fromJson(json)
        }

        /** Разбирает кадр из живого события `message.group`. */
        fun fromJson(json: JsonObject): GroupFrame? {
            val groupId = json.str("group_id") ?: return null
            val messageId = json.long("message_id") ?: return null
            val senderId = json.str("sender_id") ?: return null
            val senderDevice = json.str("sender_device") ?: return null
            val payload = json.str("payload")?.let { decodeBase64Url(it) } ?: return null
            val signature = json.str("signature")?.let { decodeBase64Url(it) } ?: return null
            return GroupFrame(
                groupId = groupId,
                messageId = messageId,
                senderId = senderId,
                senderDevice = senderDevice,
                kind = json.int("kind") ?: 0,
                // Ноль означает публичную группу с открытым текстом. Клиент такие не
                // умеет, и разбор их отвергнет — но кадр всё равно сохранится.
                gkVersion = json.int("gk_version") ?: 0,
                payload = payload,
                signature = signature,
                createdAtUnixMs = json.long("created_at_unix_ms") ?: 0,
                threadRoot = json.long("thread_root") ?: 0,
                replyTo = json.long("reply_to") ?: 0,
            )
        }
    }
}
