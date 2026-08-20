package io.tima.core.model

// В общем коде kotlin.jvm.* не подставляется по умолчанию, в отличие от JVM-таргета:
// импорт нужен явно, иначе сборка iOS падает, а JVM собирается — то есть ошибка
// находится позже всего.
import kotlin.jvm.JvmInline

/**
 * Идентификаторы как отдельные типы, а не `String` и `Long`.
 *
 * Причина не в красоте: в v1 идентификаторы ездили строками, и `chatId`,
 * `peerUserId` и `deviceId` были одним типом — компилятор не мог отличить их друг
 * от друга и не поймал бы перестановку аргументов. Здесь это `value class`, то есть
 * на выходе те же строки без обёртки в рантайме.
 */

/** Чат: личный, групповой или канал. Единственный ключ разбиения данных. */
@JvmInline
value class ChatId(val value: String) {
    init { require(value.isNotBlank()) { "ChatId пуст" } }
    override fun toString(): String = value
}

/** Аккаунт собеседника. См. ADR-0014: аккаунт — цепочка личностей, это её голова. */
@JvmInline
value class UserId(val value: String) {
    init { require(value.isNotBlank()) { "UserId пуст" } }
    override fun toString(): String = value
}

/** Устройство внутри аккаунта: у каждого свои ключи. */
@JvmInline
value class DeviceId(val value: String) {
    init { require(value.isNotBlank()) { "DeviceId пуст" } }
    override fun toString(): String = value
}

/**
 * Идентификатор сообщения на сервере. Приходит только с подтверждением, поэтому
 * отдельного «нулевого» значения здесь нет: пока сервер не ответил, у сообщения
 * есть [LocalMessageId] и больше ничего.
 */
@JvmInline
value class ServerMessageId(val value: Long) {
    init { require(value > 0) { "ServerMessageId должен быть положительным: $value" } }
    override fun toString(): String = value.toString()
}

/** Идентификатор строки в локальной базе. Существует до отправки и после неё. */
@JvmInline
value class LocalMessageId(val value: Long) {
    init { require(value > 0) { "LocalMessageId должен быть положительным: $value" } }
    override fun toString(): String = value.toString()
}

/**
 * Ключ идемпотентности, который генерирует **клиент до первой попытки отправки**.
 *
 * В v1 он назывался `client_msg_id` и его уникальность защищала от двойной записи,
 * когда догон истории пересекался с тем, что уже пришло по live-каналу
 * (инвентарь поведения, пункт 8). Здесь та же роль, но названная своим именем:
 * без него повтор после обрыва даёт собеседнику дубль.
 */
@JvmInline
value class DedupKey(val value: String) {
    init { require(value.isNotBlank()) { "DedupKey пуст" } }
    override fun toString(): String = value
}
