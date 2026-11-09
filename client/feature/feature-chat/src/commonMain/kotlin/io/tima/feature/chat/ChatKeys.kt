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
 * Ключи недоступных сообщений переписки — делегат `ChatStore` (ПЛАН-(РФ) РФ5): просьба своим устройствам и
 * собеседнику (участникам), секретная фраза, окно «Ждём ключ…», открытие по фразе из копии. Перенесено без изменения
 * поведения; состояние — общее.
 */
internal class ChatKeys(
    private val chatId: String,
    private val requestKeys: RequestGroupKeys?,
    private val askChatKeys: (suspend (List<String>?, List<Long>) -> RequestKeysStep)?,
    private val lostKeys: (suspend () -> Set<Long>)?,
    private val autoAskAllowed: (suspend () -> Boolean)?,
    private val openByPhrase: (suspend (List<String>) -> PhraseOpen)?,
    private val fromPhoneMay: Boolean,
    private val scope: CoroutineScope,
    private val words: () -> Words,
    private val _state: MutableStateFlow<ChatState>,
) {
    /** Человек набирает секретную фразу — её просят только после отказа по подписи. */
    fun changedPhrase(text: String) {
        _state.value = _state.value.copy(phrase = text)
    }

    /**
     * Отправить просьбу с набранной фразой — после отказа по подписи. Слова берутся из поля и
     * дальше нигде не сохраняются: держать их значило бы отдать вместе с устройством и тот
     * заслон, ради которого фразу спрашивают.
     */
    fun requestKey() {
        if (requestKeys == null && askChatKeys == null) return
        if (_state.value.expectKey) return
        val words = PhraseWords.parse(_state.value.phrase).takeIf { it.isNotEmpty() }
        _state.value = _state.value.copy(expectKey = true, notice = null)
        scope.launch { askKeysNow(words, quiet = false) }
    }

    /**
     * «Сообщение недоступно, запросить» — нажатие на недоступное сообщение, в личной переписке
     * и в группе (2026-10-06). Сначала без фразы: заверенному устройству она не нужна. Не всякое
     * устройство можно заверить — тогда сервер отказывает по подписи, и открывается поле фразы.
     */
    fun askNow() {
        if (requestKeys == null && askChatKeys == null) return
        if (_state.value.expectKey) return
        _state.value = _state.value.copy(expectKey = true, notice = null)
        scope.launch { askKeysNow(null, quiet = false) }
    }

    /**
     * Нажатие на «Не расшифровано» (план КК, КК4, Р9) — окно «Ждём ключ…». Просьба своим устройствам и
     * собеседнику (участникам) уходит, если её не было за сутки; у «ключа не осталось» просить незачем —
     * окно сразу предлагает фразу.
     */
    fun unreadableTapped() {
        val lostAll = askable().isEmpty()
        _state.value = _state.value.copy(keyWait = KeyWait(lost = lostAll, phraseOpen = lostAll, fromPhone = fromPhoneMay))
        if (lostAll || _state.value.expectKey || (requestKeys == null && askChatKeys == null)) return
        scope.launch {
            if (autoAskAllowed?.invoke() == false) return@launch
            _state.value = _state.value.copy(expectKey = true)
            askKeysNow(null, quiet = false)
        }
    }

    fun keyWaitClosed() {
        _state.value = _state.value.copy(keyWait = null, phrase = "")
    }

    /** «Открыть через фразу» — поле фразы в окне. */
    fun phraseOpenStarted() {
        _state.value.keyWait?.let { _state.value = _state.value.copy(keyWait = it.copy(phraseOpen = true)) }
    }

    /**
     * «Открыть» с набранной фразой: ключи из копии (КК1, КК4) и, если устройство не заверено, та же фраза
     * подписывает просьбу своим и собеседнику. Слова дальше нигде не хранятся.
     */
    fun openByPhrasePressed() {
        val wait = _state.value.keyWait ?: return
        val words = PhraseWords.parse(_state.value.phrase).takeIf { it.isNotEmpty() } ?: return
        _state.value = _state.value.copy(keyWait = wait.copy(opening = true, said = null))
        scope.launch {
            if (_state.value.phraseWanted && !_state.value.expectKey) {
                _state.value = _state.value.copy(expectKey = true)
                askKeysNow(words, quiet = true)
            }
            val outcome = runCatching { openByPhrase?.invoke(words) }.getOrNull()
            val said = when (outcome) {
                is PhraseOpen.Opened -> words().chat.phraseOpened(outcome.count)
                PhraseOpen.WrongPhrase -> words().chat.phraseWrong
                PhraseOpen.NoCopy -> words().chat.copyMissing
                PhraseOpen.Offline, null -> words().chat.offlineRetryIn(5)
            }
            val now = _state.value.keyWait ?: return@launch
            _state.value = _state.value.copy(
                keyWait = now.copy(opening = false, said = said),
                phrase = if (outcome is PhraseOpen.Opened) "" else _state.value.phrase,
            )
        }
    }

    /** ПК, «Передать с телефона»: просьба своим устройствам сейчас, без суточного предела (КК4). */
    fun fromPhonePressed() {
        val wait = _state.value.keyWait ?: return
        _state.value = _state.value.copy(keyWait = wait.copy(said = words().chat.fromPhoneHint))
        if (_state.value.expectKey || (requestKeys == null && askChatKeys == null)) return
        _state.value = _state.value.copy(expectKey = true)
        scope.launch { askKeysNow(null, quiet = false) }
    }

    /** Недоступные сообщения, которые стоит просить: с номером сервера и не потерянные, новые первыми. */
    fun askable(): List<Long> {
        val lost = _state.value.lostKeys
        return _state.value.lines
            .filter { it.display == MessageDisplay.UNREADABLE && it.serverId > 0 && it.serverId !in lost }
            .map { it.serverId }
            .distinct()
            .sortedDescending()
    }

    /** Признак «запрос в пути» ставит вызывающий — до запуска, чтобы второе нажатие его видело. */
    suspend fun askKeysNow(words: List<String>?, quiet: Boolean) {
        val outcome = requestKeys?.request(chatId, words) ?: askChatKeys?.invoke(words, askable()) ?: return
        lostKeys?.let { _state.value = _state.value.copy(lostKeys = it()) }
        val needs = outcome == RequestKeysStep.NeedsSecretPhrase
        _state.value = _state.value.copy(
            // Отказ по подписи — в открытом окне сразу поле фразы (КК4): «Открыть» подпишет просьбу ею же.
            keyWait = _state.value.keyWait?.let { if (needs) it.copy(phraseOpen = true) else it },
            expectKey = false,
            // Фраза нужна — поле остаётся открытым, пока просьба не уйдёт.
            phraseWanted = needs || (_state.value.phraseWanted && outcome !is RequestKeysStep.Asked),
            // Набранная фраза живёт до успеха и стирается сразу после него: держать её на
            // экране дольше нужного незачем.
            phrase = if (outcome is RequestKeysStep.Asked) "" else _state.value.phrase,
            notice = if (quiet) _state.value.notice else noticeOf(outcome),
        )
    }

    private fun noticeOf(outcome: RequestKeysStep): ChatNotice = when (outcome) {
        is RequestKeysStep.Asked -> ChatNotice.KeysAsked(outcome.devices)
        RequestKeysStep.NoHelpers -> ChatNotice.KeysNoHelpers
        is RequestKeysStep.Lost -> ChatNotice.KeysLost(outcome.messages)
        RequestKeysStep.NothingMissing -> ChatNotice.KeysNothingMissing
        RequestKeysStep.NeedsSecretPhrase -> ChatNotice.KeysNeedPhrase
        RequestKeysStep.NotMember -> ChatNotice.KeysRefused(words().chat.notMemberAnyMore)
        is RequestKeysStep.Offline -> ChatNotice.KeysRefused(
            words().chat.offlineRetryIn((outcome.retryAfterMs / 1000).coerceAtLeast(1).toInt()),
        )
        is RequestKeysStep.Refused -> ChatNotice.KeysRefused(words().trouble.refused(outcome.reason))
    }
}
