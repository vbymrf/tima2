package io.tima.core.database

import io.tima.core.outbox.OutboxEntry
import io.tima.core.outbox.FieldCipher
import io.tima.core.outbox.OutboxState
import io.tima.core.outbox.OutboxStore

/**
 * [OutboxStore] поверх таблицы `messages`.
 *
 * **Почему не своя таблица.** По плану (§3.4.2) очередь исходящих — это столбцы
 * `messages`: `state`, `attempts`, `next_attempt_at`, `dedup_key`. Отдельная таблица
 * дала бы у одного сообщения два источника правды, а это ровно тот дефект v1, из-за
 * которого список чатов расходился между SQLite и `chats.json`.
 *
 * Состояние хранится числом — порядковым номером [OutboxState]. Это осознанный
 * компромисс: числа компактны и индексируются, но **переупорядочивание значений
 * enum молча переименует состояния уже записанных строк**. Поэтому порядок
 * [OutboxState] менять нельзя, а добавлять новые значения — только в конец. Проверка
 * этого — тест [io.tima.core.database.OutboxStateOrdinalTest].
 */
class SqlOutboxStore(
    private val db: TimaDatabase,
    /**
     * Шифр покоя. Обязателен: столбец `body_enc` **всегда** закрыт, и «пока без
     * шифрования» тут не бывает — ровно так обещание и теряется.
     */
    private val cipher: FieldCipher,
    /**
     * Нашлась запись, тело которой не открывается ключом покоя (беда 2026-11-20): номер записи. Запись уже
     * отложена — «мёртвая» с причиной [UNREADABLE]; здесь — только сказать журналу.
     */
    private val onUnreadable: (String) -> Unit = {},
) : OutboxStore {

    private val q get() = db.messagesQueries

    override fun putIfAbsent(entry: OutboxEntry): Boolean = db.transactionWithResult {
        q.insertQueued(
            dedup_key = entry.dedupKey,
            chat_id = entry.chatId,
            // Отправитель на этом слое не известен: очередь не знает, кто мы. Пишется
            // при постановке из репозитория; пока — пусто, а не выдуманный ноль.
            sender_id = "",
            client_ts = entry.createdAtMs,
            state = entry.state.ordinal.toLong(),
            attempts = entry.attempts.toLong(),
            next_attempt_at = entry.nextAttemptAtMs,
            reply_to = null,
            level = entry.level.toLong(),
            // Корень ветки: 0 у обычного сообщения. Кладётся при постановке, потому что
            // отправка бывает после перезапуска, когда экрана с веткой уже нет.
            thread_root = entry.threadRoot,
            body_enc = cipher.seal(entry.body),
            // Вид едет в подписанные метаданные конверта, а запечатывание бывает после
            // перезапуска: решать тогда будет нечем.
            kind = entry.kind.toLong(),
            // Правка (ADR-0045): номер исправляемого уходит в подписанные метаданные.
            edit_of = entry.editOf,
        )
        // INSERT OR IGNORE молчит при конфликте, поэтому «поставили» отличается от
        // «уже было» только числом затронутых строк. Без этой проверки повторная
        // постановка выглядела бы как успешная и сбрасывала бы счётчик попыток.
        q.changes().executeAsOne() > 0
    }

    override fun byDedupKey(dedupKey: String): OutboxEntry? =
        q.byDedupKey(dedupKey).executeAsOneOrNull()?.readable()

    // Нечитаемая запись откладывается, и выбор повторяется: иначе очередь вставала бы на ней навсегда.
    override fun nextQueued(nowMs: Long): OutboxEntry? = firstReadable {
        q.nextQueued(OutboxState.QUEUED.ordinal.toLong(), nowMs).executeAsOneOrNull()
    }

    override fun nextQueued(chatId: String, nowMs: Long): OutboxEntry? = firstReadable {
        q.nextQueuedInChat(chatId, OutboxState.QUEUED.ordinal.toLong(), nowMs).executeAsOneOrNull()
    }

    override fun claimSealed(): OutboxEntry? = db.transactionWithResult {
        // Выбор и перевод — в одной транзакции: между ними нельзя оказаться, иначе
        // два вызова возьмут одну запись и сообщение уйдёт дважды.
        val entry = firstReadable { q.nextSealed(OutboxState.SEALED.ordinal.toLong()).executeAsOneOrNull() }
            ?: return@transactionWithResult null
        val claimed = entry.copy(state = OutboxState.SENDING)
        writeState(claimed)
        claimed
    }

    /** Первая читаемая из того, что отдаёт [next]; нечитаемые по пути откладываются. Предел — от круга. */
    private fun firstReadable(next: () -> Messages?): OutboxEntry? {
        repeat(MAX_SKIPS) {
            val row = next() ?: return null
            row.readable()?.let { return it }
        }
        return null
    }

    /**
     * Запись — или `null`, если тело не открывается ключом покоя: база (или эта строка) записана чужим ключом.
     * Прежде здесь было громкое падение — пустое тело ушло бы на сервер как сообщение. Отправлять его по-прежнему
     * нельзя, но и ронять приложение незачем (беда 2026-11-20: переход на аккаунт с такой строкой ронял приложение
     * при каждом проходе очереди): строка становится «мёртвой» с причиной [UNREADABLE] и остаётся в базе.
     */
    private fun Messages.readable(): OutboxEntry? {
        if (cipher.open(body_enc) != null) return toEntry()
        q.updateState(
            state = OutboxState.DEAD.ordinal.toLong(),
            attempts = attempts,
            next_attempt_at = next_attempt_at,
            sealed_epoch = sealed_epoch,
            server_id = server_id,
            fail_reason = UNREADABLE,
            dedup_key = dedup_key,
        )
        onUnreadable(dedup_key)
        return null
    }

    override fun update(entry: OutboxEntry) = writeState(entry)

    override fun requeueStuck(): Int = db.transactionWithResult {
        q.requeueStuck(
            state = OutboxState.QUEUED.ordinal.toLong(),
            state_ = OutboxState.SENDING.ordinal.toLong(),
            state__ = OutboxState.SEALED.ordinal.toLong(),
        )
        q.changes().executeAsOne().toInt()
    }

    override fun pending(): List<OutboxEntry> = q.pending(
        state = OutboxState.QUEUED.ordinal.toLong(),
        state_ = OutboxState.SEALED.ordinal.toLong(),
        state__ = OutboxState.SENDING.ordinal.toLong(),
    ).executeAsList().mapNotNull { it.readable() }

    private fun writeState(entry: OutboxEntry) {
        q.updateState(
            state = entry.state.ordinal.toLong(),
            attempts = entry.attempts.toLong(),
            next_attempt_at = entry.nextAttemptAtMs,
            sealed_epoch = entry.sealedForEpoch,
            server_id = entry.serverMessageId,
            fail_reason = entry.failReason,
            dedup_key = entry.dedupKey,
        )
        if (entry.serverAtMs > 0) q.setOwnServerTs(entry.serverAtMs, entry.dedupKey)
    }

    override fun retryDead(dedupKey: String, nowMs: Long, level: Int): Boolean = db.transactionWithResult {
        q.retryDead(
            queued = OutboxState.QUEUED.ordinal.toLong(),
            now = nowMs,
            level = level.toLong(),
            dedupKey = dedupKey,
            dead = OutboxState.DEAD.ordinal.toLong(),
        )
        q.changes().executeAsOne() > 0
    }

    override fun deleteUnsent(dedupKey: String): Boolean = db.transactionWithResult {
        q.deleteUnsent(dedupKey, OutboxState.DEAD.ordinal.toLong(), OutboxState.QUEUED.ordinal.toLong())
        q.changes().executeAsOne() > 0
    }

    private fun Messages.toEntry() = OutboxEntry(
        dedupKey = dedup_key,
        chatId = chat_id,
        // Не открылось — значит база не наша: ключ покоя выводится из секрета устройства,
        // и чужой секрет означает чужую установку. Здесь падаем громко, а не отдаём пустое
        // тело: пустое ушло бы на сервер как сообщение. У переписки (SqlChatFeed) правило
        // обратное — там одна нечитаемая строка не должна лишать человека всей истории.
        body = requireNotNull(cipher.open(body_enc)) {
            "тело $dedup_key не открывается ключом покоя: база принадлежит другой установке"
        },
        state = stateOf(state),
        attempts = attempts.toInt(),
        nextAttemptAtMs = next_attempt_at ?: 0,
        createdAtMs = client_ts,
        serverMessageId = server_id,
        sealedForEpoch = sealed_epoch,
        level = level.toInt(),
        threadRoot = thread_root,
        editOf = edit_of,
        failReason = fail_reason,
        kind = kind.toInt(),
    )

    private companion object {
        private val byOrdinal = OutboxState.entries.associateBy { it.ordinal.toLong() }

        /** Причина «мёртвой» строки, тело которой не открывается ключом покоя (беда 2026-11-20). */
        const val UNREADABLE = "unreadable_body"

        /** Сколько нечитаемых отложить за один выбор, прежде чем сказать «нечего» — от круга. */
        const val MAX_SKIPS = 50

        /**
         * Число из базы в состояние.
         *
         * Неизвестное значение — **ошибка, а не «сойдёт за QUEUED»**: строка,
         * записанная более новой версией приложения, не должна тихо попасть в
         * очередь и уйти повторно.
         */
        fun stateOf(raw: Long): OutboxState = byOrdinal[raw]
            ?: error("неизвестное состояние очереди: $raw — строка от более новой версии?")
    }
}
