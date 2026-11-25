package io.tima.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode
import io.tima.core.ui.words
import io.tima.domain.chat.ChatLine
import io.tima.domain.chat.ComposedDoc
import io.tima.domain.chat.ComposedItem
import io.tima.domain.chat.ComposedWidget
import io.tima.domain.chat.MessageDisplay
import io.tima.domain.chat.OutMedia
import io.tima.feature.chat.MessageSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Места, где пишется сообщение, кроме самой переписки (doc_mig/ПЛАН-(СЦ)-СОЦИУМА.md §1.13, СЦ24–СЦ27; заказчик
 * 2026-11-23: «Есть место — где можно написать сообщение, единый код»): «Написать», «Обращения», «Управление →
 * Сообщения», лента канала. Экран — тот же `ChatScreen`, что у переписки: пузыри, лист действий, строка ввода со скрепкой,
 * голосовым и редактором. Своё у места — откуда сообщения и куда уходит отправленное.
 */

/**
 * Вид ленты канала на этом устройстве (ПЛАН-(СЦ) §1.18, 8): «Лента» или «Статья» — свой у каждого канала (по умолчанию
 * «Лента»); «Показать кнопку в шапке» — одна на все каналы; последняя показанная запись — для черты «Новые записи».
 */
internal object ChannelLookKeys {
    const val MODE = "channel.look."
    const val BUTTON = "channel.look.button"
    const val SEEN = "channel.seen."
    const val ARTICLE = "article"
    const val FEED = "feed"
}

/** Редактор, открытый местом: правка, суперсообщение. [send] — `true`, ушло, окно закрывается. */
internal class EditorRequest(val doc: ComposedDoc, val title: String? = null, val send: suspend (ComposedDoc) -> Boolean)

/**
 * Лист действий у сообщения места; `null`-пункта нет. У ленты канала (ПЛАН-(СЦ) §1.18, 8) — как у групп: подписчику
 * «Поделиться», «Скопировать текст» («Сохранить как» у фото и файлов), «В указатели», «Выбрать несколько»; модератору
 * и выше — ещё «Изменить или удалить» (любую запись — `own` здесь значит «вправе править») и «Закрепить».
 */
internal class EntityMenu(
    val own: Boolean,
    val onEdit: (() -> Unit)? = null,
    val onDelete: (() -> Unit)? = null,
    val onSuper: (() -> Unit)? = null,
    val superNow: Boolean = false,
    val onShare: (() -> Unit)? = null,
    val onSave: (() -> Unit)? = null,
    val onPick: (() -> Unit)? = null,
    val onChoose: (() -> Unit)? = null,
    val onPin: (() -> Unit)? = null,
    val pinned: Boolean = false,
)

/**
 * Выбор нескольких в ленте канала (§1.18, 8, 1#): выбранные — по ключу строки; [bar] — полоса выбора на месте строки
 * ввода: сколько выбрано и что с ними сделать.
 */
internal class EntityChoosing(
    val chosen: Set<String>,
    val toggle: (ChatLine) -> Unit,
    val bar: @Composable () -> Unit,
    /** Выйти из выбора — системной «Назад». */
    val clear: () -> Unit = {},
)

/** Закреплённое места — полоса под шапкой (ПЛАН-(ЗО) ЗО3, у канала — §1.18, 8): номера, право, снять. */
internal class EntityPins(
    val ids: List<Long>,
    val may: Boolean,
    val onUnpin: (() -> Unit)? = null,
    val onRemove: ((List<Long>) -> Unit)? = null,
)

@Composable
internal fun EntityChat(
    title: String,
    caption: String?,
    lines: List<ChatLine>,
    environment: Environment,
    uploads: SealedUploads,
    scope: CoroutineScope,
    sink: MessageSink,
    sendText: suspend (String) -> Boolean,
    sendDoc: suspend (ComposedDoc) -> Boolean,
    onBack: () -> Unit,
    canWrite: Boolean = true,
    filesByLink: Boolean = false,
    voiceMic: () -> String? = { null },
    onHeader: (() -> Unit)? = null,
    onMore: (() -> Unit)? = null,
    aboveInput: (@Composable () -> Unit)? = null,
    threadLabel: ((ChatLine) -> String?)? = null,
    onThread: ((Long) -> Unit)? = null,
    menu: ((ChatLine) -> EntityMenu?)? = null,
    /** Редактор, открытый снаружи (суперсообщение, правка); [onEditorClosed] — закрыт. */
    editorRequest: EditorRequest? = null,
    onEditorClosed: () -> Unit = {},
    notice: String? = null,
    banner: (@Composable () -> Unit)? = null,
    /** Открыть на сообщении ([io.tima.feature.chat.JumpPlace.jumpTo]) — «Найти» ведёт к записи канала (2026-11-23). */
    jumpPlace: io.tima.feature.chat.JumpPlace? = null,
    /** Лента канала без пузыря — «Лента» или «Статья» (ПЛАН-(СЦ) §1.18, СЦ37–СЦ38); `null` — пузырями. */
    records: io.tima.feature.chat.RecordLook? = null,
    /** Значок в шапке перед «•••» — переключение «Лента» ↔ «Статья» по настройке. */
    headerExtra: (@Composable () -> Unit)? = null,
    /** Выбор нескольких; `null` — выбора нет. */
    choosing: EntityChoosing? = null,
    /** Закреплённое; `null` — полосы нет. */
    pins: EntityPins? = null,
    /** Первая непрочитанная — черта «Новые записи» и число новых (§1.18, 10); 0 — нет. */
    firstUnread: Long = 0,
    /** Открытое поверх места снаружи («Оценка», пересылка) — закрыть системной «Назад»; `true` — закрыто. */
    onInnerBack: (() -> Boolean)? = null,
) {
    var draft by remember { mutableStateOf("") }
    var ownEditor by remember { mutableStateOf<EditorRequest?>(null) }
    var menuLine by remember { mutableStateOf<ChatLine?>(null) }
    var sent by remember { mutableStateOf<String?>(null) }
    val active = editorRequest ?: ownEditor
    val closeEditor: () -> Unit = {
        if (ownEditor != null) ownEditor = null else onEditorClosed()
    }
    // Системная «Назад» сначала закрывает открытое внутри места — редактор, лист действий, «Оценку», выбор, — и только
    // потом уводит из окна (живьём на realme, 282, 2026-11-25: «Назад» с открытым листом закрывал канал).
    val innerBack by androidx.compose.runtime.rememberUpdatedState<() -> Boolean>({
        when {
            active != null -> { closeEditor(); true }
            menuLine != null -> { menuLine = null; true }
            onInnerBack?.invoke() == true -> true
            choosing != null && choosing.chosen.isNotEmpty() -> { choosing.clear(); true }
            else -> false
        }
    })
    DisposableEffect(Unit) {
        SystemBack.inner = { innerBack() }
        onDispose { SystemBack.inner = null }
    }
    val panel = rememberPanelAttach(sink, filesByLink = filesByLink)
    if (active != null) {
        val model = remember(active) { io.tima.feature.chat.EditorModel(active.doc, io.tima.core.encryption.TypedOverQuickMarkdown) }
        var editorNotice by remember(active) { mutableStateOf<String?>(null) }
        val failed = io.tima.core.ui.Tima.words.showcase.superFailed
        io.tima.feature.chat.EditorScreen(
            model = model,
            levels = emptyList(),
            defaultLevel = 0,
            preview = io.tima.core.encryption.ComposedBodiesOverKodium::preview,
            problems = io.tima.core.encryption.ComposedBodiesOverKodium::problems,
            onBack = { closeEditor() },
            onSend = { doc ->
                scope.launch {
                    if (active.send(doc)) closeEditor() else editorNotice = failed
                }
            },
            notice = editorNotice,
            title = active.title,
        )
        return
    }
    val chooseNow = choosing != null && choosing.chosen.isNotEmpty()
    Box(Modifier.fillMaxSize()) {
        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
        androidx.compose.runtime.CompositionLocalProvider(
            io.tima.feature.chat.LocalChatSelection provides choosing?.let { io.tima.feature.chat.ChatSelection(it.chosen, it.toggle) },
        ) {
        io.tima.feature.chat.ChatScreen(
            modifier = Modifier.weight(1f),
            state = io.tima.feature.chat.ChatState(
                lines = lines,
                draft = draft,
                notice = (sent ?: notice)?.let { io.tima.feature.chat.ChatNotice.Said(it) },
                firstUnread = firstUnread,
                pinned = pins?.ids?.singleOrNull() ?: 0L,
                pinnedIds = pins?.ids.orEmpty(),
                pinMay = pins?.may ?: false,
            ),
            peer = title,
            caption = caption,
            onSet = { draft = it; sent = null },
            onSend = {
                val text = draft.trim()
                if (text.isNotEmpty()) scope.launch { if (sendText(text)) draft = "" }
            },
            onBack = onBack,
            onEditor = if (canWrite) ({ ownEditor = EditorRequest(ComposedDoc(items = listOf(ComposedItem.Text(draft))), send = sendDoc) }) else null,
            onAttach = if (canWrite) panel.onAttach else null,
            attachItems = panel.items,
            voice = if (canWrite) panelVoice(voiceMic, sink::sendVoice) else null,
            onShowcase = onHeader,
            onMore = onMore,
            aboveInput = aboveInput,
            // Идёт выбор — полоса выбора на месте строки ввода, как в группе.
            inputHidden = !canWrite || chooseNow,
            threadLabel = threadLabel,
            onThread = onThread,
            onMenu = menu?.let { { line -> menuLine = line } },
            banner = banner,
            jumpPlace = jumpPlace,
            // Длительное нажатие и «⋯» открывают лист действий этого места — тот же, что пункт меню.
            records = records?.let { r ->
                io.tima.feature.chat.RecordLook(r.reactions, r.vote, r.voting, r.comments, r.onEmotions, r.onComments, r.onVote,
                    onMenu = { line -> menuLine = line }, article = r.article)
            },
            headerExtra = headerExtra,
            onUnpin = pins?.onUnpin,
            onPinsRemove = pins?.onRemove,
        )
        }
        if (chooseNow) choosing!!.bar()
        }
        menuLine?.let { line ->
            val m = menu?.invoke(line)
            val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
            val copy: (String) -> Unit = { s -> clipboard.setText(androidx.compose.ui.text.AnnotatedString(s)) }
            io.tima.feature.chat.MessageMenuSheet(
                text = line.text,
                own = m?.own ?: false,
                onClose = { menuLine = null },
                onEdit = m?.onEdit?.let { e -> { menuLine = null; e() } },
                onDeleteForAll = m?.onDelete?.let { d -> { menuLine = null; d() } },
                // У записи с фото и файлами — «Сохранить как» вместо «Скопировать текст» (заказчик 2026-10-22).
                onCopy = if (m?.onSave != null) null else line.text?.takeIf { it.isNotBlank() }?.let { t -> { menuLine = null; copy(t) } },
                onSave = m?.onSave?.let { s -> { menuLine = null; s() } },
                onShare = m?.onShare?.let { s -> { menuLine = null; s() } },
                onPick = m?.onPick?.let { p -> { menuLine = null; p() } },
                onChoose = m?.onChoose?.let { c -> { menuLine = null; c() } },
                onPin = m?.onPin?.let { p -> { menuLine = null; p() } },
                pinned = m?.pinned ?: false,
                onSuper = m?.onSuper?.let { s -> { menuLine = null; s() } },
                superNow = m?.superNow ?: false,
            )
        }
        PanelAttachDialogs(panel, sink, environment, uploads, scope)
    }
}

/** Строка для `ChatScreen` из сообщения места: номер, текст, оформление, своё ли, автор, время. */
internal fun entityLine(
    chatId: String,
    serverId: Long,
    text: String?,
    rich: io.tima.domain.chat.RichContent?,
    outgoing: Boolean,
    atMs: Long,
    senderId: String? = null,
    replies: Int = 0,
    level: Int = -1,
) = ChatLine(
    dedupKey = "entity:$chatId:$serverId",
    chatId = chatId,
    display = MessageDisplay.SENT,
    text = text,
    rich = rich,
    outgoing = outgoing,
    atMs = atMs,
    localId = serverId,
    serverId = serverId,
    senderId = senderId,
    replies = replies,
    level = level,
)

/**
 * Отправка телом документом (СЦ17, СЦ22, СЦ25): лента с сущностью, канал, описание канала и сообщества. Файлы закрыты своим
 * ключом, ключ — в теле; голосовое — на срок у сущности (§1.14, 5). [post] — тело и строка для списков; `true` — ушло.
 */
internal class BodySink(
    private val scope: CoroutineScope,
    private val uploads: SealedUploads,
    private val entityKind: String,
    private val entityId: String,
    private val photoWord: String,
    private val voiceWord: String,
    private val failWord: String,
    private val onSaid: (String) -> Unit,
    private val post: suspend (body: ByteArray, preview: String) -> Boolean,
) : MessageSink {
    private val bodies = io.tima.core.encryption.AttachmentBodiesOverKodium

    private fun go(what: String, make: suspend () -> Pair<ByteArray, String>?) {
        scope.launch {
            val got = make()
            val ok = got != null && post(got.first, got.second)
            Journal.note(LogCode.FILE_SEND, "место: отправлено", "что" to what, "сущность" to entityKind, "вышло" to ok)
            if (!ok) onSaid(failWord)
        }
    }

    override fun sendPhotos(images: List<Pair<ByteArray, String>>) = go("фото") {
        val sent = images.mapNotNull { (bytes, _) ->
            val bmp = io.tima.core.media.decodeImage(bytes) ?: return@mapNotNull null
            val prep = io.tima.core.media.PhotoPrep.fit(bmp)
            val (id, key) = uploads.upload(prep.full, "image/jpeg", null, null) ?: return@mapNotNull null
            OutMedia(id, key, "image/jpeg", prep.width, prep.height, prep.full.size.toLong())
        }
        if (sent.isEmpty()) null else bodies.album(sent) to photoWord
    }

    override fun sendFile(bytes: ByteArray, mime: String, name: String) = go("файл") {
        uploads.upload(bytes, mime, null, null)?.let { (id, key) -> bodies.file(OutMedia(id, key, mime, size = bytes.size.toLong(), name = name)) to name }
    }

    override fun sendVoice(ogg: ByteArray, durationMs: Long, wave: ByteArray) = go("голосовое") {
        uploads.uploadEntityVoice(ogg, entityKind, entityId)?.let { (id, key) ->
            bodies.voice(OutMedia(id, key, io.tima.domain.chat.SendAttachments.VOICE_MIME, size = ogg.size.toLong(), voice = true, durationMs = durationMs, wave = wave)) to voiceWord
        }
    }

    override fun sendContact(name: String, phone: String) = go("контакт") { bodies.contact(name, phone, "") to name }

    override fun sendPlace(lat: Double, lon: Double, label: String) = go("место") { bodies.place(lat, lon, label) to label.ifBlank { "$lat, $lon" } }

    override fun sendLink(url: String, title: String, cloudKind: String) = go("ссылка") { bodies.link(url, title, cloudKind) to title.ifBlank { url } }

    override fun sendWidget(widget: ComposedWidget): Any? {
        go("виджет") {
            val doc = ComposedDoc(items = listOf(ComposedItem.Widget(widget)))
            io.tima.core.encryption.ComposedBodiesOverKodium.encode(doc, null)?.let {
                it to io.tima.core.encryption.ComposedBodiesOverKodium.content(doc).displayText().take(120)
            }
        }
        return null
    }

    override fun said(text: String) = onSaid(text)
}

/**
 * Уровень 0 группы (СЦ25): то же, что уходит из переписки, — очередью отправки с подписью автора; файлы в группе —
 * ссылкой на облако (Решения §2). [after] — ушло в очередь: номер ждёт вызывающий.
 */
internal class GroupLevelZeroSink(
    private val scope: CoroutineScope,
    private val groupId: String,
    private val attach: io.tima.domain.chat.SendAttachments,
    private val onSaid: (String) -> Unit,
    private val failWord: String,
) : MessageSink {
    private fun result(r: io.tima.domain.chat.AttachResult?) {
        if (r == null || r is io.tima.domain.chat.AttachResult.Refused) onSaid(failWord)
    }

    override fun sendPhotos(images: List<Pair<ByteArray, String>>) {
        scope.launch {
            val photos = images.mapNotNull { (bytes, _) ->
                val bmp = io.tima.core.media.decodeImage(bytes) ?: return@mapNotNull null
                val prep = io.tima.core.media.PhotoPrep.fit(bmp)
                io.tima.domain.chat.SendAttachments.Photo(prep.full, prep.width, prep.height)
            }
            result(attach.photos(groupId, 0, null, photos))
        }
    }

    override fun sendFile(bytes: ByteArray, mime: String, name: String) {
        scope.launch { result(attach.file(groupId, 0, "", false, bytes, mime, name)) }
    }

    override fun sendVoice(ogg: ByteArray, durationMs: Long, wave: ByteArray) {
        scope.launch { result(attach.voice(groupId, 0, null, groupId, ogg, durationMs, wave)) }
    }

    override fun sendContact(name: String, phone: String) = result(attach.contact(groupId, 0, name, phone))
    override fun sendPlace(lat: Double, lon: Double, label: String) = result(attach.place(groupId, 0, lat, lon, label))
    override fun sendLink(url: String, title: String, cloudKind: String) = result(attach.link(groupId, 0, url, title, cloudKind))

    override fun sendWidget(widget: ComposedWidget): Any? {
        val w = widget as? ComposedWidget.Link
        if (w != null) sendLink(w.url, w.title) else onSaid(failWord)
        return null
    }

    override fun said(text: String) = onSaid(text)
}

/** Одно действие листом снизу — «Заблокировать человека» в «⋮» ленты с сущностью у команды (СЦ26). */
@Composable
internal fun OneActionSheet(title: String, action: String, onAction: () -> Unit, onClose: () -> Unit) = io.tima.core.ui.ProvidePlace(io.tima.core.ui.TextPlace.MENU) {
    val colors = io.tima.core.ui.Tima.colors
    Box(
        Modifier.fillMaxSize()
            .background(colors.text.copy(alpha = 0.45f))
            .clickable(onClick = onClose),
        contentAlignment = androidx.compose.ui.Alignment.BottomCenter,
    ) {
        androidx.compose.foundation.layout.Column(
            Modifier.fillMaxWidth().background(colors.surface).clickable(enabled = false) {},
        ) {
            io.tima.core.ui.ListLine(middle = { io.tima.core.ui.Secondary(title) }, right = { io.tima.core.ui.IconButton(glyph = "✕", onClick = onClose) })
            io.tima.core.ui.ListLine(
                modifier = Modifier.testTag(io.tima.feature.group.ThreadTags.BLOCK),
                onClick = onAction,
                middle = { io.tima.core.ui.Name(action) },
            )
        }
    }
}
