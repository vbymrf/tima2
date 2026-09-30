package io.tima.core.database

import io.tima.core.outbox.IncomingState

/**
 * Что прочитано и что нет — для журнала уведомлений и копии аккаунта
 * (ПЛАН-ЖУРНАЛА-УВЕДОМЛЕНИЙ.md, ЖУ1, ЖУ9).
 *
 * Сверка при запуске спрашивает непрочитанное поимённо; копия аккаунта — отметки
 * «просмотрено до» и отметку прочитанным до такого-то времени.
 */
class SqlReadState(private val db: TimaDatabase) {

    private val stored = IncomingState.STORED.ordinal.toLong()
    private val read = IncomingState.READ.ordinal.toLong()

    /** Переписки с непрочитанным: переписка → группа ли. */
    fun unreadChats(): Map<String, Boolean> {
        val chats = db.messagesQueries.unreadChats(stored).executeAsList().map { it.chat_id }
        return chats.associateWith { chatId ->
            db.chatsQueries.chatById(chatId).executeAsOneOrNull()?.kind == 1L
        }
    }

    /** Непросмотренные пропущенные: звонок → звонивший. */
    fun missedUnseen(me: String): Map<String, String> =
        db.callLogQueries.missedUnseenRows(me).executeAsList().associate { it.call_id to it.initiator_id }

    /** Время последнего входящего в переписке — отметка «просмотрено до» (ЖУ9). `0` — входящих нет. */
    fun lastIncomingTs(chatId: String): Long =
        db.messagesQueries.lastIncomingTs(chatId).executeAsOne()

    /**
     * Прочитано на другом устройстве до [uptoMs] (ЖУ9). Позже отметки пришедшее остаётся
     * непрочитанным: там его ещё не видели.
     *
     * @return сколько сообщений отмечено.
     */
    fun markReadUpTo(chatId: String, uptoMs: Long): Int = db.transactionWithResult {
        db.messagesQueries.markChatReadUpTo(read = read, chatId = chatId, stored = stored, uptoMs = uptoMs)
        db.messagesQueries.changes().executeAsOne().toInt()
    }

    // ── Отметки «просмотрено до» (ЖУ9) ─────────────────────────────────────

    /** Своя отметка: только вперёд, к отправке в копию. */
    fun markLocal(chatId: String, uptoMs: Long) = db.readMarksQueries.markLocal(chatId, uptoMs)

    /** Пришедшая из копии: только вперёд, к отправке не помечается. */
    fun mergeRemote(chatId: String, uptoMs: Long) = db.readMarksQueries.mergeRemote(chatId, uptoMs)

    /** Все отметки: переписка → просмотрено до. */
    fun marks(): Map<String, Long> = db.readMarksQueries.all().executeAsList().associate { it.chat_id to it.upto_ms }

    /** Есть ли что отдать в копию. */
    fun dirty(): Boolean = db.readMarksQueries.dirtyCount().executeAsOne() > 0

    /** Отдано — снять пометку. */
    fun sent() = db.readMarksQueries.markSent()

    /** Отметка переписки; `null` — не было. */
    fun uptoOf(chatId: String): Long? = db.readMarksQueries.uptoOf(chatId).executeAsOneOrNull()
}
