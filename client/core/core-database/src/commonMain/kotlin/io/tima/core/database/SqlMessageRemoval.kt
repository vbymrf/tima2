package io.tima.core.database

import io.tima.domain.chat.LocalDelete

/**
 * Удаление в базе устройства (Решения §8): у себя — строки нет; у всех — «Сообщение удалено».
 * Правки уходят в обоих случаях (ADR-0045): без исходного им не на что ложиться.
 */
class SqlMessageRemoval(private val db: TimaDatabase) : LocalDelete {

    override fun forMe(chatId: String, dedupKey: String, serverId: Long) = db.transaction {
        db.messagesQueries.deleteForMe(chatId, dedupKey)
        if (serverId > 0) db.messagesQueries.deleteEditsOf(chatId, serverId)
    }

    override fun markDeleted(chatId: String, serverId: Long) = db.transaction {
        db.messagesQueries.markDeleted(chatId, serverId)
        db.messagesQueries.deleteEditsOf(chatId, serverId)
    }
}
