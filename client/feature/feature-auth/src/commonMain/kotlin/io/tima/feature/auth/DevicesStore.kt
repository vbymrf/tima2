package io.tima.feature.auth

import io.tima.domain.account.AccountDevice
import io.tima.domain.account.DevicesStep
import io.tima.domain.account.MyDevices
import io.tima.domain.account.RevokeStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Свои устройства: список и отключение.
 *
 * **Отключение спрашивает подтверждение.** Отозванное устройство обратно не вернуть — на
 * нём придётся заводиться заново, — а строки в списке похожи друг на друга: «Телефон» и
 * «Телефон». Нажатие без вопроса означает, что человек однажды выкинет то устройство, с
 * которого читает.
 */
class DevicesStore(
    private val devices: MyDevices,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(DevicesState(expect = true))
    val state: StateFlow<DevicesState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.value = _state.value.copy(expect = true, trouble = null)
        scope.launch {
            _state.value = when (val step = devices.list()) {
                is DevicesStep.Devices -> _state.value.copy(
                    devices = step.devices,
                    expect = false,
                    trouble = null,
                )
                is DevicesStep.Offline -> _state.value.copy(
                    expect = false,
                    trouble = "Нет связи — список показать не из чего",
                )
                is DevicesStep.Refused -> _state.value.copy(expect = false, trouble = step.reason)
            }
        }
    }

    /** Человек нажал «Отключить» у строки: спрашиваем. */
    fun ask(deviceId: String) {
        _state.value = _state.value.copy(ask = deviceId, trouble = null)
    }

    /** Передумал. */
    fun changedMind() {
        _state.value = _state.value.copy(ask = null)
    }

    /** Подтвердил отключение. */
    fun revoke() {
        val id = _state.value.ask ?: return
        if (_state.value.expect) return
        _state.value = _state.value.copy(expect = true, trouble = null)

        scope.launch {
            when (val step = devices.revoke(id)) {
                // Список перечитываем, а не правим на месте: сервер мог отозвать не только
                // это устройство (например, чужая привязка отвалилась), и правка по памяти
                // разошлась бы с действительностью.
                RevokeStep.Revoked, RevokeStep.Gone -> {
                    _state.value = _state.value.copy(ask = null, expect = false)
                    refresh()
                }
                RevokeStep.LastDevice -> _state.value = _state.value.copy(
                    ask = null,
                    expect = false,
                    trouble = "Это единственное устройство аккаунта — отключить его нельзя",
                )
                is RevokeStep.Offline -> _state.value = _state.value.copy(
                    expect = false,
                    trouble = "Нет связи — устройство не отключено",
                )
                is RevokeStep.Refused -> _state.value = _state.value.copy(
                    ask = null,
                    expect = false,
                    trouble = step.reason,
                )
            }
        }
    }
}

/**
 * Что видит человек в списке устройств.
 *
 * @param спрашиваем `device_id`, про который задан вопрос «отключить?». `null` — вопроса
 *   нет. Хранится здесь, а не в экране: вопрос — это состояние, и после поворота телефона
 *   он должен остаться тем же.
 */
data class DevicesState(
    val devices: List<AccountDevice> = emptyList(),
    val expect: Boolean = false,
    val ask: String? = null,
    val trouble: String? = null,
)
