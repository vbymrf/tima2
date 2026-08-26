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
class УстройстваStore(
    private val devices: MyDevices,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(УстройстваState(ждём = true))
    val state: StateFlow<УстройстваState> = _state.asStateFlow()

    init {
        обновить()
    }

    fun обновить() {
        _state.value = _state.value.copy(ждём = true, беда = null)
        scope.launch {
            _state.value = when (val шаг = devices.список()) {
                is DevicesStep.Devices -> _state.value.copy(
                    устройства = шаг.devices,
                    ждём = false,
                    беда = null,
                )
                is DevicesStep.Offline -> _state.value.copy(
                    ждём = false,
                    беда = "Нет связи — список показать не из чего",
                )
                is DevicesStep.Refused -> _state.value.copy(ждём = false, беда = шаг.reason)
            }
        }
    }

    /** Человек нажал «Отключить» у строки: спрашиваем. */
    fun спросить(deviceId: String) {
        _state.value = _state.value.copy(спрашиваем = deviceId, беда = null)
    }

    /** Передумал. */
    fun передумал() {
        _state.value = _state.value.copy(спрашиваем = null)
    }

    /** Подтвердил отключение. */
    fun отключить() {
        val id = _state.value.спрашиваем ?: return
        if (_state.value.ждём) return
        _state.value = _state.value.copy(ждём = true, беда = null)

        scope.launch {
            when (val шаг = devices.отключить(id)) {
                // Список перечитываем, а не правим на месте: сервер мог отозвать не только
                // это устройство (например, чужая привязка отвалилась), и правка по памяти
                // разошлась бы с действительностью.
                RevokeStep.Revoked, RevokeStep.Gone -> {
                    _state.value = _state.value.copy(спрашиваем = null, ждём = false)
                    обновить()
                }
                RevokeStep.LastDevice -> _state.value = _state.value.copy(
                    спрашиваем = null,
                    ждём = false,
                    беда = "Это единственное устройство аккаунта — отключить его нельзя",
                )
                is RevokeStep.Offline -> _state.value = _state.value.copy(
                    ждём = false,
                    беда = "Нет связи — устройство не отключено",
                )
                is RevokeStep.Refused -> _state.value = _state.value.copy(
                    спрашиваем = null,
                    ждём = false,
                    беда = шаг.reason,
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
data class УстройстваState(
    val устройства: List<AccountDevice> = emptyList(),
    val ждём: Boolean = false,
    val спрашиваем: String? = null,
    val беда: String? = null,
)
