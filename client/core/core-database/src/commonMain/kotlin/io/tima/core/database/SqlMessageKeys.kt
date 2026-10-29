package io.tima.core.database

import io.tima.core.outbox.FieldCipher

/**
 * Ключи личных сообщений на устройстве (план КК, КК9; модель Р5 «ключи на устройствах, обёртки до доставки»).
 *
 * Источник ключа для своих: просьба другого своего устройства, дозаливка копии, передача истории по QR — всё
 * это раньше перезаворачивало ключ из обёртки на сервере, а обёртка живёт не дольше срока. Ключ под шифром
 * покоя, рядом с текстом сообщения.
 */
class SqlMessageKeys(db: TimaDatabase, private val cipher: FieldCipher) {

    private val q = db.messageKeysQueries

    /** Записать, если ещё нет: ключ сообщения не меняется. */
    fun put(chatId: String, messageId: Long, key: ByteArray) {
        q.putMessageKey(chatId, messageId, cipher.seal(key))
    }

    fun get(chatId: String, messageId: Long): ByteArray? =
        q.messageKey(chatId, messageId).executeAsOneOrNull()?.let { cipher.open(it) }

    /** Все ключи переписки: номер → ключ. */
    fun of(chatId: String): Map<Long, ByteArray> =
        q.messageKeysOf(chatId).executeAsList().mapNotNull { row -> cipher.open(row.key_enc)?.let { row.server_id to it } }.toMap()

    /** Переписки, ключи которых есть на устройстве. */
    fun chats(): List<String> = q.messageKeyChats().executeAsList()
}
