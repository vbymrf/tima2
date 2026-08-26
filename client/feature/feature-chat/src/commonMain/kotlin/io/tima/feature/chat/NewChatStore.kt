package io.tima.feature.chat

import io.tima.domain.chat.StartChatResult
import io.tima.domain.chat.StartPersonalChat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Новая переписка: номер телефона → найденный человек → переписка.
 *
 * Правила те же, что у входа, и по той же причине — это ввод: набранный номер не теряется
 * при отказе, второе нажатие не посылает второй запрос, отказ называется словами.
 *
 * **«Такого номера в TIMA нет» — не отказ.** Человека надо позвать, и текст об этом
 * говорит именно так. Сообщение «ошибка» на этом месте заставляет человека проверять свой
 * номер вместо того, чтобы отправить приглашение.
 */
class NewChatStore(
    private val start: StartPersonalChat,
    private val myUserId: String,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(NewChatState())
    val state: StateFlow<NewChatState> = _state.asStateFlow()

    fun changedNumber(text: String) {
        _state.value = _state.value.copy(number = text, trouble = null, invite = false)
    }

    /** Человек нажал «Найти». */
    fun find() {
        val current = _state.value
        if (current.expect) return
        _state.value = current.copy(expect = true, trouble = null, invite = false)

        scope.launch {
            _state.value = when (val outcome = start.byPhone(myUserId, current.number)) {
                is StartChatResult.Started -> current.copy(expect = false, started = outcome.chatId)

                // Отдельное состояние, а не текст беды: экран предлагает позвать человека,
                // а не сообщает об ошибке.
                StartChatResult.NotFound -> current.copy(expect = false, invite = true)

                StartChatResult.Myself -> current.copyWithTrouble("Это ваш собственный номер")
                is StartChatResult.BadPhone -> current.copyWithTrouble("Номер не тот: ${outcome.reason}")
                is StartChatResult.Offline -> current.copyWithTrouble(
                    "Нет связи с сервером — повторим через ${(outcome.retryAfterMs / 1000).coerceAtLeast(1)} с",
                )
                is StartChatResult.Refused -> current.copyWithTrouble(outcome.reason)
            }
        }
    }

    /** Экран закрыт: следующее открытие начинается с пустого поля. */
    fun reset() {
        _state.value = NewChatState()
    }
}

/** Что видно на экране новой переписки. */
data class NewChatState(
    val number: String = "",
    val trouble: String? = null,
    val expect: Boolean = false,
    /** Переписка начата: её идентификатор. Приложение открывает её и закрывает этот экран. */
    val started: String? = null,
    /** Номера нет в TIMA: предложить позвать человека. */
    val invite: Boolean = false,
) {
    fun copyWithTrouble(text: String) = copy(trouble = text, expect = false)
}
