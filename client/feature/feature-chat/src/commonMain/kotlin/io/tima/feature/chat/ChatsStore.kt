package io.tima.feature.chat

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
            .onEach { список -> _state.value = ChatsState(chats = список, прочитано = true) }
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
    val прочитано: Boolean = false,
)
