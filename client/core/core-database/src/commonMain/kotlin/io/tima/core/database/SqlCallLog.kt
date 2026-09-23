package io.tima.core.database

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import io.tima.domain.chat.CallLog
import io.tima.domain.chat.CallRecord
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Журнал звонков в местной базе.
 *
 * **Открытым текстом, и это не поблажка.** Здесь нет ни одной строки текста — только
 * идентификаторы, времена и состояния. Имя и лицо приезжают из книги в момент показа;
 * положи их сюда — и у имени станет два источника правды.
 */
class SqlCallLog(
    private val db: TimaDatabase,
    private val io: CoroutineDispatcher = Dispatchers.Default,
) : CallLog {

    override fun page(limit: Int): Flow<List<CallRecord>> =
        db.callLogQueries.page(limit.toLong()).asFlow().mapToList(io)
            .map { rows -> rows.map(::record) }

    override fun missed(me: String): Flow<Int> =
        db.callLogQueries.missedUnseen(me).asFlow().mapToOne(io).map { it.toInt() }

    override suspend fun remember(records: List<CallRecord>): Unit = withContext(io) {
        if (records.isEmpty()) return@withContext
        db.transaction {
            records.forEach { r ->
                // Сначала UPDATE, потом INSERT OR IGNORE — тот же порядок, что в
                // `Book.sq` и `Settings.sq`, и по той же причине: диалект SQLite 3.18,
                // `ON CONFLICT DO UPDATE` появился в 3.24.
                //
                // `INSERT OR REPLACE` одним запросом был бы короче и **стирал бы `seen`**:
                // он удаляет строку и вставляет заново. Просмотренные звонки после каждого
                // обновления журнала снова становились бы непросмотренными, и счётчик
                // пропущенных зажигался бы сам по себе.
                db.callLogQueries.update(
                    video = if (r.video) 1 else 0,
                    state = r.state,
                    initiatorId = r.initiatorId,
                    peerId = r.peerId,
                    endedBy = r.endedBy,
                    createdAt = r.createdAt,
                    answeredAt = r.answeredAt,
                    endedAt = r.endedAt,
                    callId = r.callId,
                )
                db.callLogQueries.insert(
                    call_id = r.callId,
                    video = if (r.video) 1 else 0,
                    state = r.state,
                    initiator_id = r.initiatorId,
                    peer_id = r.peerId,
                    ended_by = r.endedBy,
                    created_at = r.createdAt,
                    answered_at = r.answeredAt,
                    ended_at = r.endedAt,
                )
            }
        }
    }

    override suspend fun markSeen(): Unit = withContext(io) {
        // Отметка только когда есть что отмечать. Причина не в экономии: SQLDelight
        // оповещает слушателей таблицы по факту выполнения изменяющего запроса, а не по
        // числу изменённых строк. Безусловная отметка внутри сборщика ленты будила бы
        // запрос, запрос звал бы отметку снова — и приложение ушло бы в круг. Ровно это
        // ловилось на realme 2026-09-17 в списке переписок.
        if (db.callLogQueries.unseen().executeAsOne() > 0) {
            db.callLogQueries.markSeen()
        }
    }

    override suspend fun prune(olderThanMs: Long, keepRows: Int): Unit = withContext(io) {
        // Убирается копия, а не журнал: сервер строки звонков не удаляет вовсе. Поэтому
        // ошибиться здесь можно только в сторону «убрали лишнее», и это чинится запросом.
        db.transaction {
            if (olderThanMs > 0) db.callLogQueries.pruneByAge(olderThanMs)
            if (keepRows > 0) db.callLogQueries.pruneByCount(keepRows.toLong())
        }
    }

    private fun record(row: Call_log) = CallRecord(
        callId = row.call_id,
        video = row.video != 0L,
        state = row.state,
        initiatorId = row.initiator_id,
        peerId = row.peer_id,
        endedBy = row.ended_by,
        createdAt = row.created_at,
        answeredAt = row.answered_at,
        endedAt = row.ended_at,
        seen = row.seen != 0L,
    )
}
