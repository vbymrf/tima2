package io.tima.shared

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Поручение окну про звонок из строки уведомления — ВЗ1, ВЗ2.
 *
 * Окно может ещё не существовать, когда человек нажал «Принять» на замке: система
 * поднимает его заново, и звонок `Root` узнаёт позже, чем поручение. Поэтому поручение
 * лежит здесь, пока его не заберёт тот, кто держит звонок.
 *
 * @param accept `true` — «Принять»; `false` — только показать окно входящего.
 */
data class CallOrder(val callId: String, val accept: Boolean)

object CallRequests {
    private val _order = MutableStateFlow<CallOrder?>(null)
    val order: StateFlow<CallOrder?> = _order.asStateFlow()

    /** Окно получило намерение из строки звонка. */
    fun post(order: CallOrder) {
        _order.value = order
    }

    /** Поручение исполнено или устарело — убрать, чтобы не исполнить дважды. */
    fun done(callId: String) {
        if (_order.value?.callId == callId) _order.value = null
    }
}
