package io.tima.feature.chat

import io.tima.core.ui.CurrentWords
import io.tima.core.ui.Words
import io.tima.core.ui.RussianWords
import io.tima.core.ui.ChatWords
import io.tima.domain.chat.CommentEntry
import io.tima.domain.chat.CommentStep
import io.tima.domain.chat.CommentsStep
import io.tima.domain.chat.PostComments
import io.tima.domain.chat.ReadComments
import io.tima.domain.chat.WriteComment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Подокно «Комментарии» (ADR-0024, ПЛАН-КАНАЛОВ К5; макет `подокна/комментарии.html`).
 *
 * **Закрытое обсуждение и пустое различаются.** «Здесь пока никто не написал» и «обсуждение
 * закрыто» — разные вещи, и вторая обязана быть сказана словами: пустой список вместо
 * ответа читается как поломка.
 *
 * **Круг разговора не свой — он у корня.** Экран показывает его строкой один раз и не
 * предлагает менять: у комментария своего круга нет вовсе.
 *
 * **Предупреждения «уйдёт в канал» на экране нет** — решение заказчика 2026-09-08: это и
 * так ясно из того, что человек комментирует чужую запись.
 */
class CommentsStore(
    comments: PostComments,
    private val scope: CoroutineScope,
    /** Канал или лента, где лежит корень. */
    private val channelId: String,
    /** Запись, под которой идёт разговор. */
    private val rootId: Long,
    /**
     * Словарь надписей — **ссылкой, а не значением** (ПЛАН-ЯЗЫКА, Я2-беды).
     *
     * Store не `@Composable`, и `Tima.words` ему недоступен. Лямбда зовётся в момент
     * беды, поэтому язык всегда текущий: переданный значением, он запомнился бы на всю
     * жизнь store, и после смены языка беда пришла бы на прежнем.
     */
    private val words: () -> Words = { CurrentWords.value },
) {

    private val read = ReadComments(comments)
    private val write = WriteComment(comments)

    private val _state = MutableStateFlow(CommentsState())
    val state: StateFlow<CommentsState> = _state.asStateFlow()

    fun refresh() {
        scope.launch {
            _state.value = when (val outcome = read.under(channelId, rootId)) {
                is CommentsStep.Conversation ->
                    _state.value.copy(
                        entries = outcome.entries,
                        level = outcome.level,
                        loaded = true,
                        trouble = null,
                    )

                // Корня нет — и разговора нет. Не тайна и не поломка: запись убрали или
                // её нам не показывали, и различить эти случаи мы не должны.
                CommentsStep.NoRoot ->
                    _state.value.copy(entries = emptyList(), loaded = true, gone = true, trouble = null)

                is CommentsStep.Offline -> _state.value.copy(loaded = true, trouble = words().trouble.offline)
                is CommentsStep.Refused ->
                    _state.value.copy(loaded = true, trouble = words().trouble.refused(outcome.reason))
            }
        }
    }

    /** Правка поля ввода. Черновик живёт в состоянии: разговор перечитывается, поле — нет. */
    fun draft(text: String) {
        _state.value = _state.value.copy(draft = text)
    }

    /**
     * «Ответить»: подставить «@имя» в поле.
     *
     * Вложения третьего уровня нет — ответ цепляется к тому же корню (ADR-0024 §7).
     */
    fun reply(name: String) {
        _state.value = _state.value.copy(draft = WriteComment.mention(name, _state.value.draft))
    }

    fun send() {
        val text = _state.value.draft
        if (_state.value.sending) return
        _state.value = _state.value.copy(sending = true)
        scope.launch {
            val outcome = write.write(channelId, rootId, text)
            _state.value = when (outcome) {
                is CommentStep.Written -> _state.value.copy(sending = false, draft = "", trouble = null)
                CommentStep.Closed -> _state.value.copy(sending = false, closed = true, trouble = null)
                CommentStep.NoRoot -> _state.value.copy(sending = false, gone = true, trouble = null)
                // Пустое поле — не беда и не сообщение: человек ещё ничего не написал.
                CommentStep.Empty -> _state.value.copy(sending = false)
                CommentStep.TooLong ->
                    _state.value.copy(sending = false, trouble = words().chat.tooLong(WriteComment.MAX_CHARS))

                is CommentStep.Offline -> _state.value.copy(sending = false, trouble = words().trouble.offline)
                is CommentStep.Refused ->
                    _state.value.copy(
                        sending = false,
                        trouble = words().trouble.refused(outcome.reason),
                    )
            }
            if (outcome is CommentStep.Written) refresh()
        }
    }

    fun closeTrouble() {
        _state.value = _state.value.copy(trouble = null)
    }
}

/** Состояние подокна комментариев. */
data class CommentsState(
    val entries: List<CommentEntry> = emptyList(),
    /** Круг корня; он же круг разговора. */
    val level: Int = 1,
    /** Загружали ли хоть раз. Пустой список до загрузки и после — разные вещи. */
    val loaded: Boolean = false,
    /** Обсуждение выключено: новых не принимают, старые видны. */
    val closed: Boolean = false,
    /** Корня нет: удалён или не показан нам. */
    val gone: Boolean = false,
    val draft: String = "",
    val sending: Boolean = false,
    val trouble: String? = null,
)
