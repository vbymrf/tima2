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
class НоваяПерепискаStore(
    private val start: StartPersonalChat,
    private val myUserId: String,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(НоваяПерепискаState())
    val state: StateFlow<НоваяПерепискаState> = _state.asStateFlow()

    fun номерИзменён(текст: String) {
        _state.value = _state.value.copy(номер = текст, беда = null, позвать = false)
    }

    /** Человек нажал «Найти». */
    fun найти() {
        val текущее = _state.value
        if (текущее.ждём) return
        _state.value = текущее.copy(ждём = true, беда = null, позвать = false)

        scope.launch {
            _state.value = when (val исход = start.byPhone(myUserId, текущее.номер)) {
                is StartChatResult.Started -> текущее.copy(ждём = false, начата = исход.chatId)

                // Отдельное состояние, а не текст беды: экран предлагает позвать человека,
                // а не сообщает об ошибке.
                StartChatResult.NotFound -> текущее.copy(ждём = false, позвать = true)

                StartChatResult.Myself -> текущее.копияСБедой("Это ваш собственный номер")
                is StartChatResult.BadPhone -> текущее.копияСБедой("Номер не тот: ${исход.reason}")
                is StartChatResult.Offline -> текущее.копияСБедой(
                    "Нет связи с сервером — повторим через ${(исход.retryAfterMs / 1000).coerceAtLeast(1)} с",
                )
                is StartChatResult.Refused -> текущее.копияСБедой(исход.reason)
            }
        }
    }

    /** Экран закрыт: следующее открытие начинается с пустого поля. */
    fun сброс() {
        _state.value = НоваяПерепискаState()
    }
}

/** Что видно на экране новой переписки. */
data class НоваяПерепискаState(
    val номер: String = "",
    val беда: String? = null,
    val ждём: Boolean = false,
    /** Переписка начата: её идентификатор. Приложение открывает её и закрывает этот экран. */
    val начата: String? = null,
    /** Номера нет в TIMA: предложить позвать человека. */
    val позвать: Boolean = false,
) {
    fun копияСБедой(текст: String) = copy(беда = текст, ждём = false)
}
