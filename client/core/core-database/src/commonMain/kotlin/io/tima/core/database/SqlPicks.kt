package io.tima.core.database

import io.tima.domain.chat.PickStore

/** Подборка сообщений на устройстве (Решения §11.2). */
class SqlPicks(private val db: TimaDatabase) : PickStore {
    override fun has(level: String, chatId: String, serverId: Long): Boolean =
        db.picksQueries.hasPick(level, chatId, serverId).executeAsOne() > 0

    override fun count(level: String): Int = db.picksQueries.countPicks(level).executeAsOne().toInt()

    override fun add(level: String, chatId: String, serverId: Long, atMs: Long) {
        db.picksQueries.addPick(level, chatId, serverId, atMs)
    }
}
