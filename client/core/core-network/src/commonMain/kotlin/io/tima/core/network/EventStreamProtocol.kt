package io.tima.core.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Разбор кадров живого канала — К4.5. **Без сокета**, намеренно.
 *
 * Здесь живут решения, в которых легко ошибиться; в обвязке сокета их нет вовсе.
 * Проверить их можно только отдельно от сети: `MockEngine` в Ktor 3 вебсокеты не
 * изображает, а поднимать настоящий сервер ради проверки правила «подтверждать после
 * записи» — значит проверять сервер.
 *
 * Контракт снят с `internal/api/ws.go` (каталог API описывает не всё — сверка Д3):
 *
 * ```
 * → {"token":"…"}                              первый кадр, иначе разрыв
 * ← {"event":"ok","device_id":"…"}
 * → {"event":"sync.pull","cursor":N|null,"limit":N}
 * ← {"event":"message.new","event_id":N,"chat_id":"…","message_id":N,"envelope":"…"}
 * ← {"event":"sync.done","count":N,"next_cursor":N,"more":bool}
 * ← {"event":"sync.gap","next_cursor":N}
 * → {"event":"ack","event_id":N}
 * ```
 *
 * **Три правила, каждое закрывает свой способ потерять сообщение.**
 *
 * 1. **Подтверждать после записи, никогда до.** `ack` двигает серверный курсор:
 *    подтвердил и умер — сообщение не придёт больше никогда. Поэтому [ackFrame]
 *    отправляет тот, кто писал, и только после записи: разбор кадров сам его никогда
 *    не предлагает — среди решений подтверждения нет вовсе.
 *
 * 2. **`sync.gap` — не «продолжаем с этого места».** Он означает, что события до
 *    `next_cursor` сервер уже удалил по сроку хранения, и живой канал их не принесёт.
 *    Молча продолжить — значит навсегда потерять переписку за этот промежуток.
 *    Единственный верный ход — догон историей через REST, и он приходит наружу
 *    отдельным решением.
 *
 * 3. **Незнакомый кадр двигает курсор.** Соблазн — не подтверждать непонятное, «чтобы
 *    не потерять». Но тогда курсор не двигается никогда, и та же партия приходит
 *    вечно: канал встаёт целиком из-за одного кадра, которого мы всё равно не умеем
 *    прочитать.
 */
class EventStreamProtocol {

    /** Что делать по разобранному кадру. */
    sealed interface Decision {
        /** Кадр требует записи: сообщение отдаётся вызывающему. */
        data class Deliver(val event: IncomingEvent) : Decision

        /**
         * Догон закончен. `more = true` означает «есть ещё», и вызывающий обязан
         * попросить следующую страницу — иначе остаток истории не приедет.
         */
        data class SyncDone(val count: Int, val nextCursor: Long, val more: Boolean) : Decision

        /** Промежуток невосстановим по каналу: нужен догон историей через REST. */
        data class NeedHistory(val fromCursor: Long) : Decision

        /** Соединение установлено: сервер подтвердил токен. */
        data class Ready(val deviceId: String) : Decision

        /** Сервер сообщил о своей беде. Не наша: повторить позже. */
        data class ServerTrouble(val code: String) : Decision

        /** Кадр не наш или испорчен — пропускаем, но курсор двигаем (правило 3). */
        data class Skip(val reason: String, val eventId: Long?) : Decision
    }

    /** Событие, которое надо записать. */
    data class IncomingEvent(
        val eventId: Long,
        val chatId: String,
        /** Идентификатор, назначенный **отправителем**: по нему опознаётся повтор. */
        val messageId: Long,
        val envelope: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean = other is IncomingEvent &&
            eventId == other.eventId && chatId == other.chatId &&
            messageId == other.messageId && envelope.contentEquals(other.envelope)

        override fun hashCode(): Int {
            var h = eventId.hashCode()
            h = 31 * h + chatId.hashCode()
            h = 31 * h + messageId.hashCode()
            h = 31 * h + envelope.contentHashCode()
            return h
        }
    }

    /** Первый кадр: токен устройства. */
    fun authFrame(token: String): String {
        require(token.isNotBlank()) { "токен пустой" }
        return """{"token":"$token"}"""
    }

    /**
     * Запрос догона.
     *
     * @param cursor `null` — взять серверную копию курсора. Так и надо на первом
     *   подключении: своя копия может быть старше, и тогда часть событий приедет
     *   дважды. Дубли безвредны (входящая машина идемпотентна), но платить трафиком
     *   незачем.
     */
    fun pullFrame(cursor: Long?, limit: Int = DEFAULT_LIMIT): String {
        require(limit in 1..MAX_LIMIT) { "предел страницы вне 1..$MAX_LIMIT: $limit" }
        val курсор = cursor?.toString() ?: "null"
        return """{"event":"sync.pull","cursor":$курсор,"limit":$limit}"""
    }

    /** Подтверждение. Вызывать **после** записи. */
    fun ackFrame(eventId: Long): String {
        require(eventId > 0) { "event_id обязан быть положительным: $eventId" }
        return """{"event":"ack","event_id":$eventId}"""
    }

    /** Разбирает кадр сервера. Исключений не бросает: вход недоверенный. */
    fun decide(frame: String): Decision {
        val json = runCatching { Json.parseToJsonElement(frame) as JsonObject }.getOrNull()
            ?: return Decision.Skip("кадр не разобран", null)

        val eventId = json["event_id"]?.jsonPrimitive?.longOrNull
        return when (val event = json.string("event")) {
            "ok" -> Decision.Ready(json.string("device_id") ?: "")

            "message.new" -> {
                val chatId = json.string("chat_id")
                val messageId = json["message_id"]?.jsonPrimitive?.longOrNull
                val envelope = json.string("envelope")?.let { decodeBase64Url(it) }
                if (eventId == null || chatId == null || messageId == null || envelope == null) {
                    // Кадр нашего типа, но неполный: записывать нечего, а курсор двигать
                    // надо — иначе он застрянет на испорченном событии навсегда.
                    Decision.Skip("message.new без обязательных полей", eventId)
                } else {
                    Decision.Deliver(IncomingEvent(eventId, chatId, messageId, envelope))
                }
            }

            "sync.done" -> Decision.SyncDone(
                count = json["count"]?.jsonPrimitive?.longOrNull?.toInt() ?: 0,
                nextCursor = json["next_cursor"]?.jsonPrimitive?.longOrNull ?: 0,
                more = json["more"]?.jsonPrimitive?.content == "true",
            )

            "sync.gap" -> Decision.NeedHistory(
                fromCursor = json["next_cursor"]?.jsonPrimitive?.longOrNull ?: 0,
            )

            "error" -> Decision.ServerTrouble(json.string("code") ?: "без кода")

            else -> Decision.Skip("незнакомый кадр «$event»", eventId)
        }
    }

    private fun JsonObject.string(name: String): String? =
        runCatching { this[name]?.jsonPrimitive?.content }.getOrNull()

    companion object {
        /** Как у сервера: `0` он трактует как 100. Пишем явно, чтобы не гадать. */
        const val DEFAULT_LIMIT: Int = 100

        /** Предел сервера. Больше он всё равно урежет до 100 — молча. */
        const val MAX_LIMIT: Int = 500
    }
}
