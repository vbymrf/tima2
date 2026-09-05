package io.tima.feature.auth

import io.tima.domain.account.VirtualAccount
import io.tima.domain.account.VirtualsApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Свои виртуальные аккаунты — ПЛАН-КОНТАКТОВ.md, Д10, Д11, Д12.
 *
 * **Список спрашивается у сервера, а не берётся с устройства.** Они не совпадают, и это
 * не сбой: аккаунт заводили на другом телефоне либо на этом же, но приложение
 * переустановили. Показать здесь местный список значило бы потерять аккаунт из виду
 * ровно тогда, когда его ищут.
 *
 * Что с ним делают отсюда: заводят новый, передают существующий, принимают чужой.
 */
class VirtualsStore(
    private val api: VirtualsApi,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(VirtualsState())
    val state: StateFlow<VirtualsState> = _state.asStateFlow()

    fun refresh() {
        if (_state.value.working) return
        scope.launch {
            _state.value = _state.value.copy(working = true, trouble = null)
            val list = api.mine()
            _state.value = if (list == null) {
                // Пустой список и «не дошли до сервера» — разные вещи: первое означает
                // «их нет», второе «мы не знаем». Показать второе как первое значит
                // сказать человеку, что его аккаунты исчезли.
                _state.value.copy(working = false, trouble = "Список не дошёл — нет связи с сервером")
            } else {
                _state.value.copy(working = false, accounts = list, asked = true)
            }
        }
    }
}

data class VirtualsState(
    val accounts: List<VirtualAccount> = emptyList(),
    /** Спрашивали ли вообще: до первого ответа пустота ничего не означает. */
    val asked: Boolean = false,
    val working: Boolean = false,
    val trouble: String? = null,
)
