package io.tima.core.database

import io.tima.core.outbox.FieldCipher
import io.tima.core.outbox.InboxStore
import io.tima.core.outbox.IncomingEntry
import io.tima.core.outbox.IncomingState

/**
 * [InboxStore] поверх той же таблицы `messages`.
 *
 * **Одна таблица на два направления, и это не экономия.** Переписка — один список, и
 * читается он одним запросом с одной сортировкой. Держать входящие отдельно значило
 * бы склеивать два набора при каждом открытии чата и заново решать, как их
 * упорядочить между собой.
 *
 * Различает направления столбец `direction`. Он обязателен, а не выводится из данных:
 * у машин исходящих и входящих **разные состояния с совпадающей нумерацией**, и без
 * направления состояние `1` означало бы одновременно `SEALED` у исходящего и
 * `UNDECRYPTABLE` у входящего. Очередь отправки тогда забирала бы чужие
 * нерасшифрованные сообщения и посылала их обратно на сервер.
 *
 * **Ключ уникальности — тот же `dedup_key`**, в который для входящего кладётся
 * `"chatId/messageId"`. Идентификатор назначает отправитель, и он входит в подпись:
 * подделать нельзя, значит по нему и опознаётся повтор. Уникальный индекс, который уже
 * есть, тем самым обслуживает и требование инвентаря о догоне истории — без второго
 * индекса и без второй проверки в коде.
 */
class SqlInboxStore(
    private val db: TimaDatabase,
    /** Шифр покоя: и конверт, и разобранное тело лежат в столбце закрытыми. */
    private val cipher: FieldCipher,
) : InboxStore {

    private val q get() = db.messagesQueries

    override fun putIfAbsent(entry: IncomingEntry): Boolean = db.transactionWithResult {
        q.insertIncoming(
            dedup_key = keyOf(entry.chatId, entry.messageId),
            server_id = entry.messageId,
            chat_id = entry.chatId,
            // Автор известен только после разбора конверта: до расшифровки у нас есть
            // байты и метаданные сервера, но не проверенный отправитель. Пусто — а не
            // выдуманное значение, которое потом придётся отличать от настоящего.
            sender_id = "",
            client_ts = entry.receivedAtMs,
            state = entry.state.ordinal.toLong(),
            attempts = entry.attempts.toLong(),
            body_enc = cipher.seal(entry.envelope),
        )
        q.changes().executeAsOne() > 0
    }

    override fun byKey(chatId: String, messageId: Long): IncomingEntry? =
        q.byDedupKey(keyOf(chatId, messageId)).executeAsOneOrNull()?.toIncoming()

    override fun nextReceived(): IncomingEntry? =
        q.nextReceived(IncomingState.RECEIVED.ordinal.toLong()).executeAsOneOrNull()?.toIncoming()

    override fun undecryptable(): List<IncomingEntry> =
        q.undecryptable(IncomingState.UNDECRYPTABLE.ordinal.toLong())
            .executeAsList().map { it.toIncoming() }

    override fun update(entry: IncomingEntry) {
        q.updateIncoming(
            state = entry.state.ordinal.toLong(),
            attempts = entry.attempts.toLong(),
            undecryptable_reason = entry.undecryptableReason,
            dedup_key = keyOf(entry.chatId, entry.messageId),
        )
    }

    /**
     * Разобранное тело и проверенный автор на место конверта.
     *
     * Конверт до этого нужен: по нему идёт повтор разбора, когда появится ключ. После
     * успешного разбора он больше не нужен, а тело — нужно: его показывает экран. Пока
     * этой записи не было, состояние `STORED` означало «разобрано и потеряно».
     */
    override fun storeParsed(chatId: String, messageId: Long, body: ByteArray, senderId: String, level: Int) {
        q.updateParsed(
            body_enc = cipher.seal(body),
            sender_id = senderId,
            // Круг сообщения записывается вместе с телом: метку у реплики рисовать не по
            // чему, если уровень остался только в пришедшем кадре.
            level = level.toLong(),
            dedup_key = keyOf(chatId, messageId),
        )
    }

    override fun markChatRead(chatId: String): Int = db.transactionWithResult {
        q.markChatRead(
            read = IncomingState.READ.ordinal.toLong(),
            chatId = chatId,
            stored = IncomingState.STORED.ordinal.toLong(),
        )
        q.changes().executeAsOne().toInt()
    }

    override fun pending(): List<IncomingEntry> = q.incomingPending(
        state = IncomingState.RECEIVED.ordinal.toLong(),
        state_ = IncomingState.UNDECRYPTABLE.ordinal.toLong(),
    ).executeAsList().map { it.toIncoming() }

    private fun Messages.toIncoming() = IncomingEntry(
        chatId = chat_id,
        messageId = requireNotNull(server_id) {
            "входящее без server_id: строка $dedup_key записана не как входящее"
        },
        // Не открылось — отдаём как есть: разбор такого конверта провалится и строка
        // станет нечитаемой, что и есть правда о ней. Падать здесь нельзя: одна чужая
        // строка не должна лишать человека всей переписки.
        envelope = cipher.open(body_enc) ?: ByteArray(0),
        state = incomingStateOf(state),
        attempts = attempts.toInt(),
        receivedAtMs = client_ts,
        undecryptableReason = undecryptable_reason,
    )

    private companion object {
        fun keyOf(chatId: String, messageId: Long) = "$chatId/$messageId"

        private val byOrdinal = IncomingState.entries.associateBy { it.ordinal.toLong() }

        /**
         * Число из базы в состояние.
         *
         * Неизвестное значение — **ошибка, а не «сойдёт за RECEIVED»**: строка,
         * записанная более новой версией приложения, не должна попасть в разбор и
         * снова уйти в очередь на расшифровку.
         */
        fun incomingStateOf(raw: Long): IncomingState = byOrdinal[raw]
            ?: error("неизвестное состояние входящего: $raw — строка от более новой версии?")
    }
}
