package io.tima.feature.chat

import io.tima.domain.chat.CopyWatch
import io.tima.domain.chat.DraftSteps
import io.tima.domain.chat.FoundMessage
import io.tima.domain.chat.GroupKind
import io.tima.domain.chat.MessageFinder
import io.tima.domain.chat.PhraseOpen
import io.tima.domain.chat.references
import io.tima.domain.chat.ChatPins
import io.tima.domain.chat.PinStep
import io.tima.core.words.CurrentWords
import io.tima.domain.account.PhraseWords
import io.tima.core.words.Words
import io.tima.core.words.RussianWords
import io.tima.core.words.ChatWords
import io.tima.core.media.FileRules
import io.tima.core.media.PhotoPrep
import io.tima.domain.chat.AttachResult
import io.tima.domain.chat.ChatLine
import io.tima.domain.chat.ComposedDoc
import io.tima.domain.chat.ComposedItem
import io.tima.domain.chat.ComposedWidget
import io.tima.domain.chat.ComposedResult
import io.tima.domain.chat.SendComposed
import io.tima.domain.chat.SendAttachments
import io.tima.domain.chat.DeleteMessage
import io.tima.domain.chat.DeleteResult
import io.tima.domain.chat.EditMessage
import io.tima.domain.chat.EditResult
import io.tima.domain.chat.MessagePicks
import io.tima.domain.chat.PickResult
import io.tima.domain.chat.NickReply
import io.tima.domain.chat.ReplyTarget
import io.tima.domain.chat.HealGroupKey
import io.tima.domain.chat.HealStep
import io.tima.domain.chat.MessageCircle
import io.tima.domain.chat.NarrowMessageLevel
import io.tima.domain.chat.NarrowStep
import io.tima.domain.chat.MarkRead
import io.tima.domain.chat.ObserveChat
import io.tima.domain.chat.ChatFaces
import io.tima.domain.chat.ChatPeople
import io.tima.domain.chat.DeadMessages
import io.tima.domain.chat.ChatPerson
import io.tima.core.media.decodeImage
import androidx.compose.ui.graphics.ImageBitmap
import io.tima.domain.chat.RequestGroupKeys
import io.tima.domain.chat.MessageDisplay
import io.tima.domain.chat.RequestKeysStep
import io.tima.domain.chat.SendMessage
import io.tima.domain.chat.SendMessageResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.datetime.toLocalDateTime
import kotlinx.coroutines.withContext

/**
 * Закреплённое переписки — делегат `ChatStore` (ПЛАН-(РФ) РФ5): право, закрепить, снять, журнал. Перенесено без
 * изменения поведения; состояние — общее.
 */
internal class ChatPinning(
    private val pins: ChatPins?,
    private val chatId: String,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val words: () -> Words,
    private val _state: MutableStateFlow<ChatState>,
) {
    /** Право закреплять стало известно позже открытия: роль приезжает со списком групп. */
    fun allowed(may: Boolean) {
        if (pins != null) _state.value = _state.value.copy(pinMay = may)
    }

    /** Закрепить (номер) или снять (0) — одно на переписку, прежнее заменяется (ПЛАН-(ЗО) ЗО3). */
    fun change(serverId: Long) {
        val board = pins ?: return
        if (serverId < 0 || (serverId == 0L && _state.value.pinned == 0L)) return
        val group = _state.value.group
        scope.launch {
            val step = withContext(io) { if (serverId > 0) board.pin(chatId, group, serverId) else board.unpin(chatId, group) }
            val w = words().showcase
            when (step) {
                PinStep.Done -> Unit
                is PinStep.Refused -> _state.value = _state.value.copy(notice = ChatNotice.Said(w.pinRefused(step.code)))
                PinStep.Offline -> _state.value = _state.value.copy(notice = ChatNotice.Said(w.pinRefused("offline")))
            }
            log?.invoke(serverId > 0, step)
        }
    }

    /** Журнал закрепления — у вызывающего: модуль окна журнала не знает (core-diag). */
    var log: ((on: Boolean, step: PinStep) -> Unit)? = null
}
