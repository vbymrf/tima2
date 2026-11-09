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
 * Удаление сообщений переписки — делегат `ChatStore` (ПЛАН-(РФ) РФ5): у себя — сразу, у всех — с вопросом, выбранные.
 * Перенесено без изменения поведения; удаление правимого отменяет правку ([cancelEdit]).
 */
internal class ChatDeletion(
    private val deleter: DeleteMessage?,
    private val chatId: String,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val words: () -> Words,
    private val _state: MutableStateFlow<ChatState>,
    private val cancelEdit: () -> Unit,
) {
    /** «Удалить у себя» — только на устройстве, без вопросов: у остальных всё остаётся. */
    fun forMe(line: ChatLine) {
        val port = deleter ?: return
        if (_state.value.editing == line.serverId) cancelEdit()
        scope.launch { withContext(io) { port.forMe(chatId, line.dedupKey, line.serverId) } }
    }

    /** «Удалить у всех» — сначала вопрос: вернуть нельзя. */
    fun forAllAsked(line: ChatLine) {
        if (deleter == null || line.serverId <= 0) return
        _state.value = _state.value.copy(notice = ChatNotice.DeleteAsk(line.serverId))
    }

    fun forAllConfirmed() {
        val ask = _state.value.notice as? ChatNotice.DeleteAsk ?: return
        val port = deleter ?: return
        _state.value = _state.value.copy(notice = null)
        val ids = listOf(ask.serverId) + ask.more
        if (_state.value.editing in ids) cancelEdit()
        scope.launch {
            val w = words().chat
            var said: String? = null
            for (id in ids) {
                val result = withContext(io) { port.forAll(chatId, id, _state.value.group) }
                said = said ?: when (result) {
                    DeleteResult.Deleted -> null
                    DeleteResult.NotSent -> w.deleteRefused("not_sent")
                    DeleteResult.Offline -> w.deleteOffline
                    is DeleteResult.Refused -> w.deleteRefused(result.code)
                }
            }
            if (said != null) _state.value = _state.value.copy(notice = ChatNotice.Said(said))
        }
    }

    /** «Удалить у себя» для выбранных. */
    fun forMeMany(lines: List<ChatLine>) = lines.forEach(::forMe)

    /** «Удалить у всех» для выбранных — сначала вопрос: вернуть нельзя. Только своё отправленное. */
    fun forAllManyAsked(lines: List<ChatLine>) {
        if (deleter == null) return
        val ids = lines.filter { it.outgoing && it.serverId > 0 }.map { it.serverId }
        if (ids.isEmpty()) return
        _state.value = _state.value.copy(notice = ChatNotice.DeleteAsk(ids.first(), ids.drop(1)))
    }
}
