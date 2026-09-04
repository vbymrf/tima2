package io.tima.core.database

import io.tima.core.outbox.IncomingState
import io.tima.core.outbox.OutboxState
import io.tima.domain.chat.MessageDisplay

/**
 * Перевод состояния строки в то, что видит человек.
 *
 * **Живёт здесь и только здесь**, потому что здесь видно обе машины состояний: слой
 * `domain` их словаря не знает и знать не может — `core-outbox` уже зависит от
 * `domain-chat`, и обратная зависимость дала бы кольцо.
 *
 * **Самое опасное место — столбец `state`.** У исходящих и входящих разные машины с
 * совпадающей нумерацией: `1` означает `SEALED` у исходящего и `UNDECRYPTABLE` у
 * входящего. Поэтому перевод обязан сначала смотреть на направление, и только потом на
 * состояние. Спутать — значит показать «отправляется» на нечитаемом чужом сообщении.
 *
 * Общая функция, а не копия в каждом переходнике: список переписок показывает состояние
 * последнего сообщения теми же словами, что и сам чат, и разойтись им негде.
 */
internal const val OUTGOING = 0L

internal fun displayOf(direction: Long, state: Long, system: Long = 0): MessageDisplay {
    // Служебная строка решается раньше состояния: состояние у неё формальное, а показать
    // её обычным входящим значило бы приписать ей автора, которого нет.
    if (system != 0L) return MessageDisplay.SYSTEM
    val ordinal = state.toInt()
    return if (direction == OUTGOING) {
        when (OutboxState.entries.getOrNull(ordinal)) {
            OutboxState.QUEUED, OutboxState.SEALED, OutboxState.SENDING -> MessageDisplay.PENDING
            OutboxState.SENT -> MessageDisplay.SENT
            OutboxState.DEAD -> MessageDisplay.FAILED
            // Неизвестное состояние — это база, записанная версией новее нашей. Показать
            // «ждёт» безопаснее, чем спрятать сообщение: спрятанное человек считает
            // потерянным.
            null -> MessageDisplay.PENDING
        }
    } else {
        when (IncomingState.entries.getOrNull(ordinal)) {
            IncomingState.UNDECRYPTABLE -> MessageDisplay.UNREADABLE
            IncomingState.RECEIVED, IncomingState.STORED, IncomingState.READ -> MessageDisplay.RECEIVED
            null -> MessageDisplay.RECEIVED
        }
    }
}

/** Разобрано ли входящее: до разбора в столбце лежит конверт, а не тело. */
internal fun incomingParsed(state: Long): Boolean =
    when (IncomingState.entries.getOrNull(state.toInt())) {
        IncomingState.STORED, IncomingState.READ -> true
        else -> false
    }
