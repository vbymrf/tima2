package io.tima.feature.chat

import io.tima.domain.chat.CarryStep
import io.tima.domain.chat.CarryToPage
import io.tima.domain.chat.CommentSwitches
import io.tima.domain.chat.SwitchComments
import io.tima.domain.chat.SwitchStep
import io.tima.domain.chat.PageEntry
import io.tima.domain.chat.PageStep
import io.tima.domain.chat.ReadPage
import io.tima.domain.chat.UserPages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Страница человека: своё и принесённое (ПЛАН-СОЦИУМА Г8).
 *
 * **Пустая страница и неудачная загрузка различаются.** Молчащий экран неотличим от
 * поломки, поэтому «ленты ещё нет» говорится словами, а не показывается пустотой.
 *
 * **Принесённое живёт от лица источника.** Экран берёт это из состояния, а не решает сам:
 * запись не становится своей от того, что её принесли, и подписать её хозяином страницы
 * значило бы присвоить чужой текст самим показом.
 */
class PageStore(
    private val pages: UserPages,
    private val scope: CoroutineScope,
    /**
     * Выключатели обсуждения (ADR-0024 §6). `null` — страница чужая: выключать чужое
     * нельзя, и кнопки быть не должно.
     */
    private val switches: CommentSwitches? = null,
    /** Чья страница. `me` — своя. */
    private val userId: String = ReadPage.PAGE_MINE,
) {

    private val read = ReadPage(pages)
    private val carry = CarryToPage(pages)

    private val _state = MutableStateFlow(PageState(mine = userId == ReadPage.PAGE_MINE))
    val state: StateFlow<PageState> = _state.asStateFlow()

    fun refresh() {
        scope.launch {
            _state.value = when (val outcome = read.page(userId)) {
                is PageStep.Page -> _state.value.copy(
                    entries = outcome.entries,
                    channelId = outcome.channelId,
                    commentsEnabled = outcome.commentsEnabled,
                    loaded = true,
                    trouble = null,
                )
                // Не ошибка и не тайна: страницу просто ещё не завели.
                PageStep.NoPage -> _state.value.copy(entries = emptyList(), loaded = true, trouble = null)
                is PageStep.Offline -> _state.value.copy(loaded = true, trouble = "Нет связи с сервером")
                is PageStep.Refused -> _state.value.copy(loaded = true, trouble = "Сервер отказал: ${outcome.reason}")
            }
        }
    }

    /**
     * Унести чужую запись к себе.
     *
     * Круг оригинала проверяется до запроса — тем же правилом, что и на сервере. Оно
     * живёт в домене, а не здесь: одно правило, два места применения.
     */
    fun carry(
        kind: String,
        containerId: String,
        messageId: Long,
        was: Int,
        level: Int = CarryToPage.LEVEL_EVERYONE,
    ) {
        if (messageId in _state.value.carrying) return
        _state.value = _state.value.copy(carrying = _state.value.carrying + messageId)
        scope.launch {
            val outcome = carry.carry(kind, containerId, messageId, was, level)
            _state.value = _state.value.copy(
                carrying = _state.value.carrying - messageId,
                carried = if (outcome is CarryStep.Carried) _state.value.carried + messageId else _state.value.carried,
                trouble = when (outcome) {
                    is CarryStep.Carried -> null
                    CarryStep.CannotCarry -> "Эту запись нельзя унести к себе"
                    CarryStep.NotFound -> "Записи больше нет"
                    is CarryStep.Offline -> "Нет связи с сервером"
                    is CarryStep.Refused -> "Сервер отказал: ${outcome.reason}"
                },
            )
            if (outcome is CarryStep.Carried) refresh()
        }
    }

    /** Убрать запись со своей страницы. Оригинала это не касается. */
    fun remove(postId: Long) {
        scope.launch {
            when (val outcome = pages.remove(postId)) {
                is CarryStep.Carried -> refresh()
                is CarryStep.Refused -> _state.value = _state.value.copy(trouble = "Сервер отказал: ${outcome.reason}")
                else -> _state.value = _state.value.copy(trouble = "Не удалось убрать запись")
            }
        }
    }

    /**
     * Выключить или включить обсуждения на своей странице целиком.
     *
     * Страница перечитывается после ответа сервера, а не меняется на месте: состояние
     * выключателя приходит оттуда же, откуда записи, и второй источник правды разошёлся
     * бы с первым при первом же отказе.
     */
    fun commentsOnPage(enabled: Boolean) {
        val switch = switches ?: return
        val channelId = _state.value.channelId
        if (channelId.isBlank()) return
        scope.launch {
            when (val outcome = SwitchComments(switch).channel(channelId, enabled)) {
                SwitchStep.Switched -> refresh()
                SwitchStep.NotAllowed ->
                    _state.value = _state.value.copy(trouble = "Обсуждения выключает владелец страницы")

                SwitchStep.NotFound -> _state.value = _state.value.copy(trouble = "Страницы больше нет")
                is SwitchStep.Offline -> _state.value = _state.value.copy(trouble = "Нет связи с сервером")
                is SwitchStep.Refused ->
                    _state.value = _state.value.copy(trouble = "Сервер отказал: ${outcome.reason}")
            }
        }
    }

    /** Закрыть или открыть обсуждение одной записи. */
    fun commentsOnPost(postId: Long, closed: Boolean) {
        val switch = switches ?: return
        val channelId = _state.value.channelId
        if (channelId.isBlank()) return
        scope.launch {
            when (val outcome = SwitchComments(switch).post(channelId, postId, closed)) {
                SwitchStep.Switched -> refresh()
                SwitchStep.NotAllowed ->
                    _state.value = _state.value.copy(trouble = "Обсуждение закрывает владелец или модератор")

                SwitchStep.NotFound -> _state.value = _state.value.copy(trouble = "Записи больше нет")
                is SwitchStep.Offline -> _state.value = _state.value.copy(trouble = "Нет связи с сервером")
                is SwitchStep.Refused ->
                    _state.value = _state.value.copy(trouble = "Сервер отказал: ${outcome.reason}")
            }
        }
    }

    fun troubleDismissed() {
        _state.value = _state.value.copy(trouble = null)
    }
}

/** Что видно на странице. */
data class PageState(
    val entries: List<PageEntry> = emptyList(),
    /**
     * Канал, которым эта страница является. Пусто — страницы ещё не читали.
     *
     * Нужен для одного: открыть разговор под записью. Комментарий адресуется каналом и
     * записью, и страница человека — такой же канал.
     */
    val channelId: String = "",
    /** Принимает ли страница обсуждения вообще. Второй выключатель — у каждой записи. */
    val commentsEnabled: Boolean = true,
    /** Своя ли страница: у чужой нельзя убирать записи. */
    val mine: Boolean = true,
    /**
     * Был ли ответ. Пусто и «не знаем» — разные состояния, и до первого ответа экран не
     * вправе говорить «здесь ничего нет».
     */
    val loaded: Boolean = false,
    /** Записи, которые сейчас уносим: второе нажатие не шлёт второй запрос. */
    val carrying: Set<Long> = emptySet(),
    /** Унесённые в этот заход — по ним у реплики гаснет кнопка. */
    val carried: Set<Long> = emptySet(),
    val trouble: String? = null,
)
