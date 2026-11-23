package io.tima.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.testTag
import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode
import io.tima.core.ui.Tima
import io.tima.core.ui.words
import io.tima.feature.chat.AttachItem
import io.tima.feature.chat.MessageSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Панель ввода — общей частью (doc_mig/ПЛАН-(СЦ)-СОЦИУМА.md §1.13, СЦ24; заказчик 2026-11-23: «Есть место — где можно
 * написать сообщение, единый код»). Скрепка, галерея, камера, файлы, контакт, место, ссылка и «Из облака» — те же, что в
 * личной переписке; куда уходит — портом [MessageSink]: переписка — своё хранилище, «Написать», «Обращения», «Сообщения»,
 * лента канала — свои отправки. Перенесено из `Chat` без изменения поведения.
 */
internal class PanelAttach(
    val onAttach: (AttachItem) -> Unit,
    val items: List<AttachItem>,
    internal val asking: MutableState<AttachAsk?>,
    internal val askTrouble: MutableState<String?>,
    internal val linkAsk: MutableState<Boolean>,
)

/**
 * Обработчик скрепки и галереи. [filesByLink] — файлы только ссылкой на облако (группа, Решения §2). [onOnePhoto] — одно
 * фото сначала в редактор фото (переписка); `null` — уходит сразу. Окна вопросов рисует [PanelAttachDialogs] — поверх
 * экрана, последним.
 */
@Composable
internal fun rememberPanelAttach(
    sink: MessageSink,
    filesByLink: Boolean,
    onOnePhoto: ((ByteArray, String) -> Unit)? = null,
): PanelAttach {
    val asking = remember { mutableStateOf<AttachAsk?>(null) }
    val askTrouble = remember { mutableStateOf<String?>(null) }
    val linkAsk = remember { mutableStateOf(false) }
    val one: (ByteArray, String) -> Unit = { bytes, mime -> onOnePhoto?.invoke(bytes, mime) ?: sink.sendPhotos(listOf(bytes to mime)) }
    val photosPicker = io.tima.core.media.rememberPhotosPicker(io.tima.domain.chat.SendAttachments.MAX_PHOTOS) { list ->
        when (list.size) {
            0 -> Unit
            1 -> one(list.single().bytes, list.single().mime)
            else -> sink.sendPhotos(list.map { it.bytes to it.mime })
        }
    }
    val camera = io.tima.core.media.rememberCameraShot { shot -> shot?.let { one(it.bytes, it.mime) } }
    val onFile: (io.tima.core.media.PickedFile?) -> Unit = { f -> f?.let { sink.sendFile(it.bytes, it.mime, it.name) } }
    val anyFile = io.tima.core.media.rememberFilePicker(io.tima.core.media.FileKind.ANY, onFile)
    val documentFile = io.tima.core.media.rememberFilePicker(io.tima.core.media.FileKind.DOCUMENT, onFile)
    val audioFile = io.tima.core.media.rememberFilePicker(io.tima.core.media.FileKind.AUDIO, onFile)
    val videoFile = io.tima.core.media.rememberFilePicker(io.tima.core.media.FileKind.VIDEO, onFile)
    val contactPicker = io.tima.core.media.rememberContactPicker { c -> c?.let { sink.sendContact(it.name, it.phone) } }
    val placeNoAccess = Tima.words.chat.placeNoAccess
    val myPlace = io.tima.core.media.rememberMyPlace { p ->
        if (p != null) sink.sendPlace(p.first, p.second) else { askTrouble.value = placeNoAccess; asking.value = AttachAsk.Place }
    }
    // В группе файлы — только ссылкой (Решения §2): верхние «Файл», «Документ», «Аудио», «Видео» там убраны —
    // их делает раздел «Из облака» (заказчик 2026-10-24, 1г#).
    val byLinkOnly = setOf(AttachItem.FILE, AttachItem.DOCUMENT, AttachItem.AUDIO, AttachItem.VIDEO)
    val items = AttachItem.entries.filter { (it != AttachItem.CAMERA || camera != null) && !(filesByLink && it in byLinkOnly) }
    val handler: (AttachItem) -> Unit = { item ->
        fun fileOr(kind: String, pick: () -> Unit) = if (filesByLink) asking.value = AttachAsk.Cloud(kind) else pick()
        when (item) {
            AttachItem.PICTURE -> photosPicker()
            AttachItem.CAMERA -> camera?.invoke()
            AttachItem.FILE -> fileOr("file", anyFile)
            AttachItem.DOCUMENT -> fileOr("document", documentFile)
            AttachItem.AUDIO -> fileOr("audio", audioFile)
            AttachItem.VIDEO -> fileOr("video", videoFile)
            AttachItem.CONTACT -> contactPicker?.invoke() ?: run { asking.value = AttachAsk.Contact }
            AttachItem.PLACE -> myPlace?.invoke() ?: run { asking.value = AttachAsk.Place }
            // Ссылка — тем же окном, что «Вставить ссылку» в редакторе (заказчик 2026-10-19): вид,
            // название, цвет; уходит отдельным сообщением в своём пузыре.
            AttachItem.LINK -> linkAsk.value = true
            AttachItem.CLOUD_FILE -> asking.value = AttachAsk.FromCloud("file")
            AttachItem.CLOUD_VIDEO -> asking.value = AttachAsk.FromCloud("video")
            AttachItem.CLOUD_AUDIO -> asking.value = AttachAsk.FromCloud("audio")
        }
    }
    return PanelAttach(handler, items, asking, askTrouble, linkAsk)
}

/** Окна вопросов скрепки: ссылка, облако, место, контакт. Рисуются поверх экрана — звать последним. */
@Composable
internal fun PanelAttachDialogs(
    panel: PanelAttach,
    sink: MessageSink,
    environment: Environment,
    uploads: SealedUploads,
    scope: CoroutineScope,
) {
    if (panel.linkAsk.value) {
        val settingsNow by environment.settings.all().collectAsState(initial = emptyMap())
        val linkCardsHere = remember { io.tima.core.network.LinkCardsOverHttp() }
        EditorAskSheet(
            ask = "link",
            scope = scope,
            cards = linkCardsHere,
            cardsOn = settingsNow[LINK_CARDS_KEY] != "0",
            linkColor = settingsNow[LINK_COLOR_KEY].orEmpty(),
            onLinkColor = { c -> scope.launch { environment.settings.put(LINK_COLOR_KEY, c) } },
            cardImage = { bytes ->
                val prepared = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { io.tima.core.media.PhotoPrep.inline(bytes) }
                val up = prepared?.let { uploads.upload(it.full, "image/jpeg", null, null) }
                if (prepared != null && up != null) {
                    io.tima.domain.chat.OutMedia(up.first, up.second, "image/jpeg", prepared.width, prepared.height, prepared.full.size.toLong())
                } else null
            },
            put = { widget ->
                sink.sendWidget(widget)
                Journal.note(LogCode.EDITOR, "ссылка из скрепки", "вид" to ((widget as? io.tima.domain.chat.ComposedWidget.Link)?.mode ?: "?"))
            },
            onNotice = { it?.let(sink::said) },
            onClose = { panel.linkAsk.value = false },
        )
    }
    panel.asking.value?.let { ask ->
        val w = Tima.words.chat
        val (title, fields) = when (ask) {
            is AttachAsk.Cloud -> w.linkPlace to listOf(w.linkPlace)
            is AttachAsk.FromCloud -> (w.attachCloud + " · " + w.attachItem("cloud." + ask.kind)) to listOf(w.linkPlace, w.cloudTitleHint)
            AttachAsk.Place -> w.attachItem("place") to listOf(w.placeAsk)
            AttachAsk.Contact -> w.attachItem("contact") to listOf(Tima.words.chat.contact, "+7")
        }
        io.tima.feature.chat.AskSheet(
            title = title,
            fields = fields,
            ok = w.ok,
            cancel = w.cancel,
            trouble = panel.askTrouble.value,
            // «Из облака»: ушли с поля ссылки — что по ней (видео сайта, файл, вид файла), без предупреждений.
            extra = if (ask is AttachAsk.FromCloud) ({ now ->
                    val url = now.values.firstOrNull().orEmpty()
                    if (0 in now.left && url.isNotBlank()) {
                        val full = if ("://" in url) url else "https://$url"
                        val said = if (ask.kind == "file") {
                            w.cloudFileIs(w.fileKindName(io.tima.domain.chat.FileKinds.ofName(io.tima.domain.chat.FileKinds.nameOf(full)).key))
                        } else {
                            io.tima.feature.chat.linkKindText(full, Tima.words.editor, w)
                                ?: w.cloudFileIs(w.fileKindName(io.tima.domain.chat.FileKinds.ofName(io.tima.domain.chat.FileKinds.nameOf(full)).key))
                        }
                        io.tima.core.ui.Tertiary(said, modifier = androidx.compose.ui.Modifier.testTag(CLOUD_KIND_TAG))
                    }
            }) else null,
            onCancel = { panel.asking.value = null; panel.askTrouble.value = null },
            onDone = { values ->
                when (ask) {
                    is AttachAsk.Cloud -> { sink.sendLink(values[0], cloudKind = ask.kind); panel.asking.value = null }
                    // Отдельным сообщением: видео и звук, что играют у нас, — виджетом, без меток (2а#).
                    is AttachAsk.FromCloud -> if (values[0].isNotBlank()) {
                        sink.sendWidget(io.tima.feature.chat.cloudAttachWidget(values[0], values.getOrElse(1) { "" }, ask.kind))
                        panel.asking.value = null
                    }
                    AttachAsk.Place -> {
                        val point = io.tima.core.media.PlaceText.parse(values[0])
                        if (point == null) panel.askTrouble.value = w.placeAsk else {
                            sink.sendPlace(point.first, point.second); panel.asking.value = null; panel.askTrouble.value = null
                        }
                    }
                    AttachAsk.Contact -> { sink.sendContact(values[0], values[1]); panel.asking.value = null }
                }
            },
        )
    }
}

/** Голосовое панели (ПЛАН-(ВМ) §2.5): разрешение — как у звонка, без камеры; уходит в [send]. */
internal fun panelVoice(micName: () -> String?, send: (ByteArray, Long, ByteArray) -> Unit) = io.tima.feature.chat.VoiceInput(
    ask = { done -> io.tima.core.call.askCallAccess(false) { done(it) } },
    micName = micName,
    send = { clip ->
        Journal.note(LogCode.VOICE, "отправка", "секунд" to clip.durationMs / 1000, "размер" to clip.ogg.size)
        send(clip.ogg, clip.durationMs, clip.wave)
    },
    onEvent = { what, value ->
        if (what == io.tima.feature.chat.VoiceEvent.RECORD_STARTED) Journal.note(LogCode.VOICE, voiceWord(what), "кГц" to value)
        else Journal.note(LogCode.VOICE, voiceWord(what), "секунд" to value)
    },
)
