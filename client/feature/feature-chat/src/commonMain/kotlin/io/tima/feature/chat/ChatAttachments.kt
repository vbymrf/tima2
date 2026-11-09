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
 * Вложения переписки — делегат `ChatStore` (ПЛАН-(РФ) РФ5): фото, фото из редактора, файл почтальоном, голосовое,
 * контакт, место, ссылка. Перенесено из `ChatStore` без изменения поведения; состояние — общее, `ChatState` один.
 */
internal class ChatAttachments(
    private val attach: SendAttachments?,
    private val chatId: String,
    private val peerId: String?,
    private val selfChat: Boolean,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val words: () -> Words,
    private val _state: MutableStateFlow<ChatState>,
) {
    /** Отказ вложению — журналу (FILE-SEND): причина словом кода, без имени файла. */
    var log: ((reason: String) -> Unit)? = null

    private fun attachResult(result: AttachResult, warn: String? = null) {
        when (result) {
            AttachResult.TooMany -> log?.invoke("too_many")
            is AttachResult.Refused -> log?.invoke(result.reason)
            else -> Unit
        }
        val w = words().chat
        val said = when (result) {
            is AttachResult.Queued -> warn
            AttachResult.Offline -> w.sendFailed
            AttachResult.TooMany -> w.photoTooMany
            is AttachResult.Refused -> w.fileRefused(result.reason)
        }
        _state.value = _state.value.copy(notice = said?.let { ChatNotice.Said(it) })
    }

    /** Фото: 1280 готовится здесь же, на фоне (ПЛАН-(ПФ) ПФ3); превью в теле нет (ADR-0052). */
    fun sendPhotos(images: List<Pair<ByteArray, String>>) {
        val port = attach ?: return
        _state.value = _state.value.copy(notice = ChatNotice.Said(words().chat.sending))
        scope.launch {
            // Сторона фото и сколько их за раз — по правилам сервера (ПЛАН-(ИФ) ИФ2).
            val rules = withContext(io) { port.rules() }
            if (images.size > rules.maxPhotos) {
                log?.invoke("too_many")
                _state.value = _state.value.copy(notice = ChatNotice.Said(words().chat.photoTooMany))
                return@launch
            }
            val result = withContext(io) {
                val photos = images.mapNotNull { (bytes, _) ->
                    PhotoPrep.prepare(bytes, side = rules.photoSide)?.let { SendAttachments.Photo(it.full, it.width, it.height) }
                }
                port.photos(chatId, _state.value.level, peerId, photos)
            }
            attachResult(result)
        }
    }

    /**
     * Одно фото после редактора (ADR-0052, заказчик 2026-10-22): [make] сводит слои на исходнике и
     * уменьшает до стороны из правил сервера — её он и получает. `null` — свести не вышло.
     */
    fun sendPhotoMade(make: suspend (side: Int) -> PhotoPrep.Prepared?) {
        val port = attach ?: return
        _state.value = _state.value.copy(notice = ChatNotice.Said(words().chat.sending))
        scope.launch {
            val rules = withContext(io) { port.rules() }
            val ready = withContext(io) { make(rules.photoSide) }
            if (ready == null) {
                attachResult(AttachResult.Refused("nothing"))
                return@launch
            }
            val result = withContext(io) {
                port.photos(chatId, _state.value.level, peerId, listOf(SendAttachments.Photo(ready.full, ready.width, ready.height)))
            }
            attachResult(result)
        }
    }

    /** Файл почтальоном (личная переписка): запреты и предупреждения — по имени (Решения §6). */
    fun sendFile(bytes: ByteArray, mime: String, name: String) {
        val port = attach ?: return
        val peer = peerId ?: return
        if (FileRules.forbidden(name)) {
            attachResult(AttachResult.Refused("type"))
            return
        }
        _state.value = _state.value.copy(notice = ChatNotice.Said(words().chat.sending))
        val warn = if (FileRules.warned(name)) words().chat.fileRefused("archive") else null
        scope.launch {
            val result = withContext(io) { port.file(chatId, _state.value.level, peer, selfChat, bytes, mime, name) }
            attachResult(result, warn)
        }
    }

    /**
     * Голосовое (ПЛАН-(ВМ) ВМ3): в личной — файл на срок для собеседника, в группе — для её
     * участников ([chatId] — номер группы). В канале голосовых нет (Р19): экран кнопки не даёт.
     */
    fun sendVoice(ogg: ByteArray, durationMs: Long, wave: ByteArray) {
        val port = attach ?: return
        _state.value = _state.value.copy(notice = ChatNotice.Said(words().chat.sending))
        scope.launch {
            val result = withContext(io) {
                port.voice(chatId, _state.value.level, peerId, if (peerId == null) chatId else null, ogg, durationMs, wave)
            }
            attachResult(result)
        }
    }

    fun sendContact(name: String, phone: String) {
        val port = attach ?: return
        if (name.isBlank() && phone.isBlank()) return
        attachResult(port.contact(chatId, _state.value.level, name, phone))
    }

    fun sendPlace(lat: Double, lon: Double, label: String = "") {
        val port = attach ?: return
        attachResult(port.place(chatId, _state.value.level, lat, lon, label))
    }

    fun sendLink(url: String, title: String = "", cloudKind: String = "") {
        val port = attach ?: return
        val clean = url.trim().let { if ("://" in it) it else "https://$it" }
        if (clean.length < 10) return
        attachResult(port.link(chatId, _state.value.level, clean, title, cloudKind))
    }
}
