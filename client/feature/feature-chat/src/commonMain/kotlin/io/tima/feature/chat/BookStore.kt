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
class BookStore(
    contacts: ObserveContacts,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(BookState())
    val state: StateFlow<BookState> = _state.asStateFlow()

    init {
        scope.launch {
            contacts.list().collect { people ->
                _state.value = _state.value.copy(all = people)
            }
        }
    }

    fun changedSearch(line: String) {
        _state.value = _state.value.copy(search = line)
    }
}

/**
 * @param все всё, что пришло из базы.
 * @param поиск строка фильтра. Пустая — показываются все.
 */
data class BookState(
    val all: List<Contact> = emptyList(),
    val search: String = "",
) {
    /**
     * Что показать.
     *
     * Ищется и по имени, и по идентификатору: у человека без записанного имени
     * искать больше нечего, а исключать его из поиска значило бы прятать того, кто
     * в списке есть.
     */
    val visible: List<Contact>
        get() {
            val request = search.trim()
            if (request.isEmpty()) return all
            return all.filter { person ->
                person.name?.contains(request, ignoreCase = true) == true ||
                    person.userId.contains(request, ignoreCase = true)
            }
        }

    /** Список пуст потому, что ничего не нашлось, а не потому, что переписок нет. */
    val notFoundNothing: Boolean get() = all.isNotEmpty() && visible.isEmpty()
}
