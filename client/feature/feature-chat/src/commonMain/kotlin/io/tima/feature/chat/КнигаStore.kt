package io.tima.feature.chat

import io.tima.domain.chat.Contact
import io.tima.domain.chat.ObserveContacts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Книга — вкладка людей окна «Телефон».
 *
 * Тот же принцип, что у [ChatsStore]: список приходит **потоком из базы**, а не
 * запросом по кнопке. Новая переписка появляется в книге сама.
 *
 * Поиск по строке фильтрует уже полученный список, а не ходит в базу заново: людей,
 * с которыми есть переписка, столько, сколько переписок, — это десятки, а не тысячи,
 * и второй запрос на каждую букву был бы работой ради работы.
 */
class КнигаStore(
    contacts: ObserveContacts,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(КнигаState())
    val state: StateFlow<КнигаState> = _state.asStateFlow()

    init {
        scope.launch {
            contacts.list().collect { люди ->
                _state.value = _state.value.copy(все = люди)
            }
        }
    }

    fun поискИзменён(строка: String) {
        _state.value = _state.value.copy(поиск = строка)
    }
}

/**
 * @param все всё, что пришло из базы.
 * @param поиск строка фильтра. Пустая — показываются все.
 */
data class КнигаState(
    val все: List<Contact> = emptyList(),
    val поиск: String = "",
) {
    /**
     * Что показать.
     *
     * Ищется и по имени, и по идентификатору: у человека без записанного имени
     * искать больше нечего, а исключать его из поиска значило бы прятать того, кто
     * в списке есть.
     */
    val видимые: List<Contact>
        get() {
            val запрос = поиск.trim()
            if (запрос.isEmpty()) return все
            return все.filter { человек ->
                человек.name?.contains(запрос, ignoreCase = true) == true ||
                    человек.userId.contains(запрос, ignoreCase = true)
            }
        }

    /** Список пуст потому, что ничего не нашлось, а не потому, что переписок нет. */
    val ничегоНеНашлось: Boolean get() = все.isNotEmpty() && видимые.isEmpty()
}
