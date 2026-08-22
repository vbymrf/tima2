package io.tima.feature.chat

import io.tima.domain.chat.ChatLine
import io.tima.domain.chat.ObserveChat
import io.tima.domain.chat.SendMessage
import io.tima.domain.chat.SendMessageResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Состояние окна переписки — К4.4.
 *
 * **Что здесь есть и чего нет.** Есть правила поведения экрана: что делать с набранным
 * текстом при удаче и при отказе, что показывать человеку. Нет ни сети, ни базы, ни
 * криптографии — только два случая использования из `domain-chat`. Это проверяется
 * архитектурным правилом, а не договорённостью.
 *
 * **Главное правило здесь одно: набранное человеком не теряется.** При удаче поле ввода
 * очищается — сообщение уже видно в списке, и оставленный текст выглядел бы как второе.
 * При **любом** отказе текст остаётся в поле: он написан человеком, а не нами, и
 * восстановить его нам нечем. В v1 поле очищалось до подтверждения, и сообщение,
 * отвергнутое по размеру, исчезало вместе с набранным.
 */
class ChatStore(
    private val chatId: String,
    observe: ObserveChat,
    private val send: SendMessage,
    scope: CoroutineScope,
    /** Сколько строк держать на экране. Столько же просит и запрос к базе. */
    pageSize: Int = ObserveChat.DEFAULT_PAGE,
) {

    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()

    init {
        // Список приходит потоком: обновление от самой базы, а не по нажатию. Значит
        // пришедшее сообщение и смена состояния отправки появляются на экране сами.
        observe.page(chatId, pageSize)
            .onEach { строки -> _state.value = _state.value.copy(lines = строки) }
            .launchIn(scope)
    }

    /** Человек набирает текст. */
    fun draftChanged(text: String) {
        _state.value = _state.value.copy(draft = text, notice = null)
    }

    /**
     * Человек нажал «отправить».
     *
     * Возвращает исход, потому что вызывающему бывает нужно знать, состоялось ли
     * действие — например чтобы убрать клавиатуру. Состояние при этом уже обновлено.
     */
    fun sendPressed(): SendMessageResult {
        val текст = _state.value.draft
        val исход = send.send(chatId, текст)

        _state.value = when (исход) {
            // Принято: поле чистим — сообщение уже в списке.
            is SendMessageResult.Queued,
            is SendMessageResult.AlreadyQueued,
            -> _state.value.copy(draft = "", notice = null)

            // Нажатие мимо. Ни сообщения, ни жалобы: человек и сам видит, что поле пусто.
            SendMessageResult.Empty -> _state.value.copy(notice = null)

            // Текст ОСТАЁТСЯ. Сообщить надо, а отобрать написанное — нельзя.
            is SendMessageResult.TooLarge -> _state.value.copy(
                notice = ChatNotice.TooLarge(исход.bytes, исход.limit),
            )
        }
        return исход
    }

    /** Человек закрыл сообщение о беде. */
    fun noticeDismissed() {
        _state.value = _state.value.copy(notice = null)
    }
}

/** Что видно на экране переписки. */
data class ChatState(
    /** Новое сверху — так же, как отдаёт запрос к базе. */
    val lines: List<ChatLine> = emptyList(),
    /** Набранное, но не отправленное. Живёт до подтверждения постановки в очередь. */
    val draft: String = "",
    val notice: ChatNotice? = null,
)

/**
 * Сообщение человеку. Список короткий намеренно: то, что очередь решает сама
 * (повторы, ожидание сети), человеку сообщать нечем и незачем — это видно по
 * состоянию строки.
 */
sealed interface ChatNotice {
    /** Слишком большое. Числа в сообщении нужны: «слишком большое» без размера бесполезно. */
    data class TooLarge(val bytes: Int, val limit: Int) : ChatNotice
}
