package io.tima.core.database

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import io.tima.core.outbox.FieldCipher
import io.tima.core.outbox.OutboxState
import io.tima.domain.chat.ChatFeed
import io.tima.domain.chat.ChatLine
import io.tima.domain.chat.EditMessage
import io.tima.domain.chat.EditOriginal
import io.tima.domain.chat.MessageBodies
import io.tima.domain.chat.MessageBodyCodec
import io.tima.domain.chat.MessageDisplay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * Переписка из базы — переходник к порту `domain-chat`.
 *
 * Перевод состояния в то, что видит человек, живёт в [displayOf] — он общий со списком
 * переписок: список показывает состояние последнего сообщения теми же словами, что и сам
 * чат, и разойтись им негде.
 *
 * Порядок задаёт SQL (`chatPage`), а не этот код: правило «серверное время, если есть,
 * иначе местное; при равенстве — порядок появления» повторено индексом, иначе список
 * на нескольких тысячах сообщений сортируется в памяти.
 */
class SqlChatFeed(
    private val db: TimaDatabase,
    /**
     * Кодек тела — порт `domain-chat`, а не своя распаковка.
     *
     * Тело записано тем же кодеком, которым уходит на провод, и читать его чем-то
     * другим означало бы завести второе представление текста. Порт при этом остаётся
     * доменным: техника (zstd, protobuf) живёт в `core-encryption`, а сюда приезжает
     * готовой.
     */
    private val codec: MessageBodyCodec,
    /** Шифр покоя: в столбце лежат закрытые байты, и открыть их надо прежде разбора. */
    private val cipher: FieldCipher,
    /**
     * Кто я. Нужен ровно для одного: **сообщение со своего второго устройства — своё.**
     *
     * Оно приходит входящим (его принёс живой канал), но написал его человек сам, с
     * телефона, и на ПК он хочет видеть его своим — справа, без имени автора. Отличить
     * можно только по проверенному отправителю, поэтому он и записывается.
     */
    private val myUserId: String,
    /** Прежние личности аккаунта (М6): их сообщения в перенесённой истории — тоже свои. */
    private val priorIds: () -> Set<String> = { emptySet() },
) : ChatFeed, MessageBodies {

    override fun page(chatId: String, limit: Int): Flow<List<ChatLine>> =
        db.messagesQueries.chatPage(chatId, limit.toLong())
            // asFlow + mapToList: обновление приходит от самой базы, когда меняется
            // таблица. Опрос по таймеру давал бы в v1 и задержку, и лишние пробуждения.
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { lines ->
                // Правки — тем же проходом: строка правки в ленте не идёт, она ложится на
                // исходное (ADR-0045). Таблица одна, и новая правка будит этот же поток.
                val edits = editsOf(chatId)
                lines.map { it.toLine(edits) }
            }
            // Расшифровка ленты — не на главном потоке (см. SqlChatsFeed.list).
            .flowOn(Dispatchers.Default)

    override fun original(chatId: String, serverId: Long): EditOriginal? {
        val row = db.messagesQueries.byServerId(chatId, serverId).executeAsOneOrNull() ?: return null
        val body = openBody(row.direction, row.state, row.body_enc, row.system) ?: return null
        return EditOriginal(
            body = body,
            edits = row.editsFrom(editsOf(chatId)),
            level = row.level.toInt(),
            own = own(row.direction, row.sender_id),
        )
    }

    /** Правка, открытая ключом покоя: тело правки и кто её написал. */
    private class EditRow(val direction: Long, val senderId: String, val body: ByteArray)

    private fun editsOf(chatId: String): Map<Long, List<EditRow>> =
        db.messagesQueries.editsInChat(chatId, OutboxState.DEAD.ordinal.toLong()).executeAsList()
            .mapNotNull { e ->
                openBody(e.direction, e.state, e.body_enc, e.system)?.let { e.edit_of to EditRow(e.direction, e.sender_id, it) }
            }
            .groupBy({ it.first }, { it.second })

    /**
     * Правки этого сообщения, которым можно верить: того же автора — сервер проверяет это при
     * приёме, а здесь второй раз, потому что чужая правка поверх своего была бы подменой текста.
     * Не больше трёх: четвёртую сервер не принимает, и показывать её неоткуда.
     */
    private fun Messages.editsFrom(all: Map<Long, List<EditRow>>): List<ByteArray> {
        val id = server_id ?: return emptyList()
        val mine = own(direction, sender_id)
        return all[id].orEmpty()
            .filter { if (mine) own(it.direction, it.senderId) else it.senderId.isNotEmpty() && it.senderId == sender_id }
            .take(EditMessage.MAX_EDITS)
            .map { it.body }
    }

    private fun Messages.toLine(all: Map<Long, List<EditRow>>): ChatLine {
        // Два шага, и порядок обязателен: сначала открыть поле ключом покоя, потом
        // разобрать тело кодеком. Любой из шагов может не удаться, и тогда текста нет —
        // строка остаётся в переписке нечитаемой. Одна испорченная запись не должна
        // лишать человека всей истории. Разбор один — и текст, и оформление (ПЛАН-(РС) РС4).
        val open = openBody(direction, state, body_enc, system)
        val edits = if (open == null) emptyList() else editsFrom(all)
        val decoded = open?.let { if (edits.isEmpty()) codec.decodeLine(it) else codec.decodeEdited(it, edits) }
        return toLine(decoded).copy(edits = if (decoded == null) 0 else edits.size)
    }

    private fun Messages.toLine(decoded: io.tima.domain.chat.DecodedLine?) = ChatLine(
        dedupKey = dedup_key,
        chatId = chat_id,
        display = displayOf(direction, state, system),
        text = decoded?.text,
        rich = decoded?.rich,
        outgoing = own(direction, sender_id),
        // Серверное время, если сообщение дошло; иначе часы устройства. Они врут, но
        // других на момент составления нет.
        atMs = server_ts ?: client_ts,
        failReason = fail_reason,
        attempts = attempts.toInt(),
        nextAttemptAtMs = next_attempt_at ?: 0,
        level = level.toInt(),
        localId = local_id,
        // Ноль, а не null: «сервер его ещё не видел» и «идентификатор ноль» — одно и то
        // же состояние, и разделять их значило бы плодить проверку на пустоту у каждого
        // действия над сообщением.
        serverId = server_id ?: 0,
        // Автор берётся из столбца, куда он записан ПОСЛЕ проверки подписи. До разбора
        // там пусто, и это честнее выдуманного имени: пустое место видно, подделка нет.
        senderId = sender_id.ifBlank { null },
        threadRoot = thread_root,
        kind = kind.toInt(),
    )

    /**
     * Своё ли сообщение.
     *
     * Своё направление — очевидно. Но своим является и входящее от **себя же**: человек
     * написал его с другого своего устройства, и показывать его чужим значило бы разговор
     * с самим собой в чужих пузырях. Пустой `sender_id` — ещё не разобранное входящее;
     * автора у него пока нет, и своим оно не считается.
     */
    private fun own(direction: Long, senderId: String): Boolean =
        direction == OUTGOING || (senderId.isNotEmpty() && (senderId == myUserId || senderId in priorIds()))

    /**
     * Текст строки — или его отсутствие.
     *
     * У входящего в столбце лежит **конверт**, пока разбор не удался: тело появляется там
     * только после успешной расшифровки. Поэтому у непринятого и нечитаемого текста нет
     * по определению, и пытаться разобрать конверт кодеком незачем — это не тело.
     */
    private fun openBody(direction: Long, state: Long, field: ByteArray, system: Long): ByteArray? {
        // Служебная строка лежит уже разобранной: конверта у неё не было — её написало
        // само устройство, получив событие сервера.
        if (system == 0L && direction != OUTGOING && !incomingParsed(state)) return null
        return cipher.open(field)
    }
}
