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
class NewGroupStore(
    private val creation: CreateGroupChat,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(NewGroupState())
    val state: StateFlow<NewGroupState> = _state.asStateFlow()

    fun changedTitle(text: String) {
        _state.value = _state.value.copy(title = text, trouble = null)
    }

    fun changedNumber(text: String) {
        _state.value = _state.value.copy(number = text, trouble = null)
    }

    /**
     * Добавить набранный номер в список приглашаемых.
     *
     * Номера накапливаются до создания, а не после: группу создают один раз, и звать в неё
     * по одному, каждый раз через сеть, — это ротация ключа на каждого приглашённого.
     */
    fun addNumber() {
        val current = _state.value
        val number = current.number.trim()
        if (number.isEmpty()) return
        if (number in current.numbers) {
            // Молча проглотить повтор нельзя: человек будет жать снова, думая, что не
            // сработало. Сказать словами — дешевле.
            _state.value = current.copy(number = "", trouble = "Этот номер уже в списке")
            return
        }
        _state.value = current.copy(numbers = current.numbers + number, number = "", trouble = null)
    }

    fun removeNumber(number: String) {
        _state.value = _state.value.copy(numbers = _state.value.numbers - number)
    }

    /** Человек нажал «Создать». */
    fun create() {
        val current = _state.value
        if (current.expect) return
        _state.value = current.copy(expect = true, trouble = null)

        scope.launch {
            _state.value = when (val outcome = creation.create(current.title, current.numbers)) {
                is CreateGroupStep.Created -> current.copy(
                    expect = false,
                    created = outcome.groupId,
                    notInvited = outcome.notInvited,
                )
                is CreateGroupStep.BadTitle -> current.copyWithTrouble(outcome.reason)
                is CreateGroupStep.Offline -> current.copyWithTrouble(
                    "Нет связи с сервером — повторим через ${(outcome.retryAfterMs / 1000).coerceAtLeast(1)} с",
                )
                is CreateGroupStep.Refused -> current.copyWithTrouble(outcome.reason)
            }
        }
    }

    /** Экран закрыт: следующее открытие начинается с пустого. */
    fun reset() {
        _state.value = NewGroupState()
    }
}

/** Что видно на экране новой группы. */
data class NewGroupState(
    val title: String = "",
    val number: String = "",
    /** Кого зовут: накопленные номера. */
    val numbers: List<String> = emptyList(),
    val trouble: String? = null,
    val expect: Boolean = false,
    /** Группа создана: её идентификатор. Приложение открывает её и закрывает этот экран. */
    val created: String? = null,
    /** Номера, которых нет в TIMA. Группа при этом создана. */
    val notInvited: List<String> = emptyList(),
) {
    fun copyWithTrouble(text: String) = copy(trouble = text, expect = false)
}
