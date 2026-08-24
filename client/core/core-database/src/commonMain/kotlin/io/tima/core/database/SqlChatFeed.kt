package io.tima.core.database

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import io.tima.core.outbox.FieldCipher
import io.tima.domain.chat.ChatFeed
import io.tima.domain.chat.ChatLine
import io.tima.domain.chat.MessageBodyCodec
import io.tima.domain.chat.MessageDisplay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
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
) : ChatFeed {

    override fun page(chatId: String, limit: Int): Flow<List<ChatLine>> =
        db.messagesQueries.chatPage(chatId, limit.toLong())
            // asFlow + mapToList: обновление приходит от самой базы, когда меняется
            // таблица. Опрос по таймеру давал бы в v1 и задержку, и лишние пробуждения.
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { строки -> строки.map { it.toLine() } }

    private fun Messages.toLine() = ChatLine(
        dedupKey = dedup_key,
        chatId = chat_id,
        display = displayOf(direction, state),
        // Два шага, и порядок обязателен: сначала открыть поле ключом покоя, потом
        // разобрать тело кодеком. Любой из шагов может не удаться, и тогда текста нет —
        // строка остаётся в переписке нечитаемой. Одна испорченная запись не должна
        // лишать человека всей истории.
        text = текстСтроки(direction, state, body_enc),
        outgoing = своё(direction, sender_id),
        // Серверное время, если сообщение дошло; иначе часы устройства. Они врут, но
        // других на момент составления нет.
        atMs = server_ts ?: client_ts,
        localId = local_id,
        // Автор берётся из столбца, куда он записан ПОСЛЕ проверки подписи. До разбора
        // там пусто, и это честнее выдуманного имени: пустое место видно, подделка нет.
        senderId = sender_id.ifBlank { null },
    )

    /**
     * Своё ли сообщение.
     *
     * Своё направление — очевидно. Но своим является и входящее от **себя же**: человек
     * написал его с другого своего устройства, и показывать его чужим значило бы разговор
     * с самим собой в чужих пузырях. Пустой `sender_id` — ещё не разобранное входящее;
     * автора у него пока нет, и своим оно не считается.
     */
    private fun своё(direction: Long, senderId: String): Boolean =
        direction == ИСХОДЯЩЕЕ || (senderId.isNotEmpty() && senderId == myUserId)

    /**
     * Текст строки — или его отсутствие.
     *
     * У входящего в столбце лежит **конверт**, пока разбор не удался: тело появляется там
     * только после успешной расшифровки. Поэтому у непринятого и нечитаемого текста нет
     * по определению, и пытаться разобрать конверт кодеком незачем — это не тело.
     */
    private fun текстСтроки(direction: Long, state: Long, поле: ByteArray): String? {
        if (direction != ИСХОДЯЩЕЕ && !входящееРазобрано(state)) return null
        val открытое = cipher.open(поле) ?: return null
        return codec.decodeText(открытое)
    }
}
