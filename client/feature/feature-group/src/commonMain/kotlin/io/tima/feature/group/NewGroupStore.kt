package io.tima.feature.group

import io.tima.domain.chat.CreateGroupChat
import io.tima.domain.chat.CreateGroupStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Новая группа: название и номера тех, кого зовут.
 *
 * Правила ввода те же, что у новой переписки, и по той же причине: набранное не теряется
 * при отказе, второе нажатие не посылает второй запрос, отказ называется словами.
 *
 * **Непозванные — не ошибка.** Из десяти номеров один может не пользоваться TIMA, и группа
 * при этом создаётся: терять её из-за одного номера человек не согласится. Экран поэтому
 * показывает такие номера отдельным списком с предложением позвать человека — не красным
 * текстом про сбой.
 */
class НоваяГруппаStore(
    private val создание: CreateGroupChat,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(НоваяГруппаState())
    val state: StateFlow<НоваяГруппаState> = _state.asStateFlow()

    fun названиеИзменено(текст: String) {
        _state.value = _state.value.copy(название = текст, беда = null)
    }

    fun номерИзменён(текст: String) {
        _state.value = _state.value.copy(номер = текст, беда = null)
    }

    /**
     * Добавить набранный номер в список приглашаемых.
     *
     * Номера накапливаются до создания, а не после: группу создают один раз, и звать в неё
     * по одному, каждый раз через сеть, — это ротация ключа на каждого приглашённого.
     */
    fun добавитьНомер() {
        val текущее = _state.value
        val номер = текущее.номер.trim()
        if (номер.isEmpty()) return
        if (номер in текущее.номера) {
            // Молча проглотить повтор нельзя: человек будет жать снова, думая, что не
            // сработало. Сказать словами — дешевле.
            _state.value = текущее.copy(номер = "", беда = "Этот номер уже в списке")
            return
        }
        _state.value = текущее.copy(номера = текущее.номера + номер, номер = "", беда = null)
    }

    fun убратьНомер(номер: String) {
        _state.value = _state.value.copy(номера = _state.value.номера - номер)
    }

    /** Человек нажал «Создать». */
    fun создать() {
        val текущее = _state.value
        if (текущее.ждём) return
        _state.value = текущее.copy(ждём = true, беда = null)

        scope.launch {
            _state.value = when (val исход = создание.создать(текущее.название, текущее.номера)) {
                is CreateGroupStep.Created -> текущее.copy(
                    ждём = false,
                    создана = исход.groupId,
                    непозванные = исход.непозванные,
                )
                is CreateGroupStep.BadTitle -> текущее.копияСБедой(исход.reason)
                is CreateGroupStep.Offline -> текущее.копияСБедой(
                    "Нет связи с сервером — повторим через ${(исход.retryAfterMs / 1000).coerceAtLeast(1)} с",
                )
                is CreateGroupStep.Refused -> текущее.копияСБедой(исход.reason)
            }
        }
    }

    /** Экран закрыт: следующее открытие начинается с пустого. */
    fun сброс() {
        _state.value = НоваяГруппаState()
    }
}

/** Что видно на экране новой группы. */
data class НоваяГруппаState(
    val название: String = "",
    val номер: String = "",
    /** Кого зовут: накопленные номера. */
    val номера: List<String> = emptyList(),
    val беда: String? = null,
    val ждём: Boolean = false,
    /** Группа создана: её идентификатор. Приложение открывает её и закрывает этот экран. */
    val создана: String? = null,
    /** Номера, которых нет в TIMA. Группа при этом создана. */
    val непозванные: List<String> = emptyList(),
) {
    fun копияСБедой(текст: String) = copy(беда = текст, ждём = false)
}
