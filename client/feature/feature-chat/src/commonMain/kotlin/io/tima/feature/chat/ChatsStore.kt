package io.tima.feature.chat

import io.tima.domain.chat.ChatKind
import io.tima.domain.chat.ChatSummary
import io.tima.domain.chat.ObserveChats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
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
) {

    private val _state = MutableStateFlow(ChatsState())
    val state: StateFlow<ChatsState> = _state.asStateFlow()

    init {
        observe.list(pageSize)
            .onEach { list -> _state.value = ChatsState(chats = list, read = true) }
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
    val personal: List<ChatSummary> get() = chats.filter { it.kind == ChatKind.Personal }

    /** Групповые переписки — вкладка «Группы» окна 5. */
    val groups: List<ChatSummary> get() = chats.filter { it.kind == ChatKind.Group }
}
