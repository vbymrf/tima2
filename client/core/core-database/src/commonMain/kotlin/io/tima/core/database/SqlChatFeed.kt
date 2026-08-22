package io.tima.core.database

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import io.tima.core.outbox.IncomingState
import io.tima.core.outbox.OutboxState
import io.tima.domain.chat.ChatFeed
import io.tima.domain.chat.ChatLine
import io.tima.domain.chat.MessageDisplay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Переписка из базы — переходник к порту `domain-chat`.
 *
 * **Здесь живёт перевод состояния в то, что видит человек**, потому что здесь и только
 * здесь видно обе машины состояний. Слой `domain` их словаря не знает и знать не может:
 * `core-outbox` уже зависит от `domain-chat`, и обратная зависимость дала бы кольцо.
 *
 * **Самое опасное место — столбец `state`.** У исходящих и входящих **разные машины
 * состояний с совпадающей нумерацией**: `1` означает `SEALED` у исходящего и
 * `UNDECRYPTABLE` у входящего. Поэтому перевод обязан сначала смотреть на
 * `direction`, и только потом на `state`. Спутать их — значит показать человеку
 * «отправляется» на нечитаемом чужом сообщении.
 *
 * Порядок задаёт SQL (`chatPage`), а не этот код: правило «серверное время, если есть,
 * иначе местное; при равенстве — порядок появления» повторено индексом, иначе список
 * на нескольких тысячах сообщений сортируется в памяти.
 */
class SqlChatFeed(private val db: TimaDatabase) : ChatFeed {

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
        outgoing = direction == OUTGOING,
        // Серверное время, если сообщение дошло; иначе часы устройства. Они врут, но
        // других на момент составления нет.
        atMs = server_ts ?: client_ts,
        localId = local_id,
    )

    private companion object {
        const val OUTGOING = 0L

        /**
         * Перевод состояния в то, что имеет смысл показать.
         *
         * Различие «в очереди» и «конверт собран» для человека не существует — это одно
         * ожидание. А вот «не ушло» отличается от «отправляется» тем, что требует его
         * решения, и «получено, но не читается» — тем, что сообщение было, а прочесть
         * его нельзя.
         */
        fun displayOf(direction: Long, state: Long): MessageDisplay {
            val ordinal = state.toInt()
            return if (direction == OUTGOING) {
                when (OutboxState.entries.getOrNull(ordinal)) {
                    OutboxState.QUEUED, OutboxState.SEALED, OutboxState.SENDING -> MessageDisplay.PENDING
                    OutboxState.SENT -> MessageDisplay.SENT
                    OutboxState.DEAD -> MessageDisplay.FAILED
                    // Неизвестное состояние — это база, записанная версией новее нашей.
                    // Показать «ждёт» безопаснее, чем спрятать сообщение: спрятанное
                    // человек считает потерянным.
                    null -> MessageDisplay.PENDING
                }
            } else {
                when (IncomingState.entries.getOrNull(ordinal)) {
                    IncomingState.UNDECRYPTABLE -> MessageDisplay.UNREADABLE
                    IncomingState.RECEIVED, IncomingState.STORED, IncomingState.READ ->
                        MessageDisplay.RECEIVED
                    null -> MessageDisplay.RECEIVED
                }
            }
        }
    }
}
