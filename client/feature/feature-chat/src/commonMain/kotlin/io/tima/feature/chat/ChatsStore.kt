package io.tima.feature.chat

import io.tima.domain.chat.ChatKind
import io.tima.domain.chat.ChatSummary
import io.tima.domain.chat.ObserveChats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Состояние окна переписок — «окно 1» из канона.
 *
 * Решений здесь мало, и это правильно: список приходит потоком из базы, а порядок и
 * счётчики считает запрос. Единственное настоящее решение — различать **«ещё не
 * прочитали»** и **«переписок нет»**: пустой список означает и то и другое, а показывать
 * человеку «переписок нет» в первую секунду после запуска — врать ему.
 */
class ChatsStore(
    observe: ObserveChats,
    scope: CoroutineScope,
    pageSize: Int = ObserveChats.DEFAULT_PAGE,
    /**
     * Кого человек заблокировал — их переписки в окне «Телефон» не показываются (Л8).
     *
     * Потоком, а не разовым списком: разблокировали — переписка обязана вернуться сама,
     * без перезахода в окно.
     */
    blocked: Flow<Set<String>> = flowOf(emptySet()),
) {

    private val _state = MutableStateFlow(ChatsState())
    val state: StateFlow<ChatsState> = _state.asStateFlow()

    init {
        observe.list(pageSize)
            .onEach { list -> _state.value = _state.value.copy(chats = list, read = true) }
            .launchIn(scope)
        blocked
            .onEach { ids -> _state.value = _state.value.copy(blocked = ids) }
            .launchIn(scope)
    }
}

/** Что видно в окне переписок. */
data class ChatsState(
    /** Новое сверху — так же, как отдаёт запрос. */
    val chats: List<ChatSummary> = emptyList(),
    /**
     * База уже ответила.
     *
     * `false` — окно только открылось и о переписках пока ничего не известно. Пустой
     * список в этом состоянии не означает «переписок нет».
     */
    val read: Boolean = false,
    /**
     * Заблокированные — по `user_id` собеседника (Л8).
     *
     * Отбор здесь, а не в запросе: блокировка живёт в книге контактов, а не в таблице
     * переписок, и join между ними означал бы, что список переписок знает про книгу.
     * Скрытых переписок единицы, а список и так уже в памяти.
     */
    val blocked: Set<String> = emptySet(),
) {
    /**
     * Личные переписки — то, что показывает окно 1 «Телефон».
     *
     * **Деление по роду, а не по списку исключений.** Группы, каналы, сообщества и
     * голосовые комнаты живут в каталоге окна 2 и на своей вкладке окна 5 (решение
     * заказчика, записано в `ИНТЕРФЕЙС/ПРАВИЛО.md`); в окне 1 — только личные. Пока из
     * этого ряда существуют одни группы, но условие написано по роду, чтобы следующий
     * род не пришлось разыскивать по окнам заново.
     */
    val personal: List<ChatSummary>
        get() = chats.filter { it.kind == ChatKind.Personal && it.peerId?.let(blocked::contains) != true }

    /** Групповые переписки — вкладка «Группы» окна 5. */
    val groups: List<ChatSummary> get() = chats.filter { it.kind == ChatKind.Group }
}
