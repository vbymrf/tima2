package io.tima.core.database

import io.tima.domain.chat.ContinuationMarks

/**
 * «Только продолжение» у своего исходящего (Решения §11.3) — столбец той же строки очереди: пометка
 * переживает перезапуск вместе с сообщением, и отправка группы читает её там же, где тело.
 */
class SqlContinuationMarks(private val db: TimaDatabase) : ContinuationMarks {
    override fun mark(dedupKey: String) {
        db.messagesQueries.markContinuation(dedupKey)
    }

    override fun marked(dedupKey: String): Boolean =
        db.messagesQueries.isContinuation(dedupKey).executeAsOneOrNull() == 1L
}
