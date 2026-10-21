package io.tima.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.Color
import io.tima.core.ui.IconButton
import io.tima.core.ui.authorStrip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.tima.core.ui.MarkKind
import io.tima.core.ui.ChipKind
import io.tima.core.ui.Button
import io.tima.core.ui.ButtonCircle
import io.tima.core.ui.Mark
import io.tima.core.ui.Field
import io.tima.core.ui.Caption
import io.tima.core.ui.Bubble
import io.tima.core.ui.Side
import io.tima.core.ui.Arrow
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.TimaZones
import io.tima.core.ui.ProvidePlace
import io.tima.core.ui.TextPlace
import io.tima.core.ui.Tima
import io.tima.core.ui.words
import io.tima.core.ui.Tertiary
import io.tima.core.ui.SubwindowHeader
import io.tima.core.ui.Chip
import io.tima.domain.chat.PersonField
import io.tima.domain.chat.PersonLook
import io.tima.domain.chat.RichContent
import kotlinx.coroutines.launch
import io.tima.domain.chat.letter
import io.tima.domain.chat.line
import io.tima.domain.chat.CarryToPage
import io.tima.domain.chat.MessageCircle
import io.tima.domain.chat.ChatLine
import io.tima.domain.chat.KIND_SYSTEM
import io.tima.domain.chat.MessageDisplay

/**
 * Метка телефонной кнопки в шапке переписки — для живых сценариев (`maestro/`).
 *
 * **Назначена, а не выведена из ключа** — в отличие от `window:`, `tab:` и
 * `settings:`, которые берутся прямо из перечислений. Перечисления кнопок в шапке
 * нет, и заводить его ради одной метки значило бы строить лестницу к одной ступеньке.
 *
 * **Метка нужна именно здесь, а не вообще везде.** Глиф у этой кнопки тот же, что у
 * окна «Телефон» в рейке, — отбор по видимому тексту нашёл бы два узла и ушёл бы то в
 * один, то в другой. Где надпись однозначна, метка не нужна.
 */
const val CALL_BUTTON_TAG: String = "chat:call"

/**
 * Метки поля ввода и кнопки отправки — для сценария пачки сообщений (`send-burst.yaml`).
 *
 * У кнопки нет надписи (стрелка нарисована), у поля подсказка в дерево экрана не попадает:
 * без меток сценарий бил точками и отбором «правее текста» — и попадал в аватар шапки
 * (PH-42 навыка телефонов, 2026-10-09).
 */
const val CHAT_INPUT_TAG: String = "chat:input"
const val CHAT_SEND_TAG: String = "chat:send"

/**
 * Окно переписки — К5.2, первый экран.
 *
 * **Экран — чистый рендер состояния.** Он ничего не решает: что делать с набранным
 * текстом при отказе, когда чистить поле, что показать человеку — всё уже решено в
 * [ChatStore]. Здесь только то, как это выглядит. Поэтому экран проверяется снимком: ему
 * дают состояние и смотрят, что нарисовалось.
 *
 * **Первым сделан чат, а не список чатов** — порядок из дорожной карты: экраны идут по
 * частоте использования. Чат открывают чаще всего, и он же тяжелее всех: пузыри, полосы
 * автора, состояния отправки, ввод.
 *
 * Личная переписка. Продолжение серии определяется **направлением**, потому что в личной
 * переписке участников двое; в группе для этого понадобится отправитель, а его в
 * [ChatLine] нет — это придёт с группами (К6), и тогда правило станет другим.
 */
@Composable
fun ChatScreen(
    /** Отправители, чья личность отменена владельцем (ДУ6, Р30). */
    cancelledSenders: Set<String> = emptySet(),
    /** Строка ввода: знаки серым, текст оформлен (ПЛАН-(РС) РС5); `null` — обычное поле. */
    /** Набранное со знаками ↔ поле без знаков (ADR-0048). */
    source: io.tima.domain.chat.TypedSource = io.tima.domain.chat.PlainTyped,
    state: ChatState,
    /** Имя собеседника в шапке. Над сообщениями его нет: в личной переписке подписей нет. */
    peer: String,
    onSet: (String) -> Unit,
    onSend: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Подпись под именем: «в сети», «был вчера», «печатает…». */
    caption: String? = null,
    /** «Доставлено» и «прочитано» своих сообщений (ПЛАН-(ОП)); `null` — не знаем. */
    receipt: ChatReceipt? = null,
    onCloseMessage: () -> Unit = {},
    /**
     * Человек просит недостающий ключ группы. Кнопка появляется только у групповой
     * переписки с нечитаемыми сообщениями: у личной просить нечего и не у кого.
     */
    onRequestKey: () -> Unit = {},
    /** Человек набирает секретную фразу для подписи запроса. */
    onPhrase: (String) -> Unit = {},
    /**
     * Открыть состав группы. `null` — переписка личная: состава у неё нет, и кнопки быть
     * не должно.
     */
    onMembers: (() -> Unit)? = null,
    /**
     * Круг следующего сообщения (ADR-0019) и его смена. `null` — переписка личная: круга
     * у неё нет, всё зашифровано и адресовано одному человеку.
     *
     * Слова «уровень» на экране нет: человек видит круг — «Всем», «Своим», «Зашифровано».
     */
    circle: MessageCircle? = null,
    onCircle: ((MessageCircle) -> Unit)? = null,
    /** Какие круги предлагать — по виду группы; по умолчанию все (вид не известен). */
    circles: List<MessageCircle> = MessageCircle.entries,
    /**
     * Сузить круг у уже отправленной реплики: `(messageId, было, стало)`.
     *
     * `null` — сужать нечем: личная переписка либо мы не распоряжаемся содержимым.
     * **Расширения здесь нет и быть не может** — экран предлагает только круги уже
     * нынешнего, и это не украшение, а само правило (ADR-0019 §6): предложить расширение
     * значило бы довести человека до отказа сервера.
     */
    onNarrow: ((Long, Int, Int) -> Unit)? = null,
    /** Человек подтвердил сужение, о последствиях которого его предупредили. */
    onNarrowConfirm: () -> Unit = {},
    /** Подтвердил «Удалить у всех» (Решения §8). */
    onDeleteConfirm: () -> Unit = {},
    /** Отменил правку — полоса «Изменение» уходит (ADR-0045). `null` — полосы нет. */
    onEditCancel: (() -> Unit)? = null,
    /** Не отвечать — полоса ответа уходит. `null` — полосы нет. */
    onReplyCancel: (() -> Unit)? = null,
    /** Кнопка редактора слева от строки ввода (Решения §9); `null` — кнопки нет. */
    onEditor: (() -> Unit)? = null,
    /** Собрано в редакторе — чип над строкой; `null` — не собрано. */
    composed: ComposedLook? = null,
    onComposedDrop: (() -> Unit)? = null,
    onComposedRestore: (() -> Unit)? = null,
    /** Переход внутри переписки — `(что, длина цепочки)`: для журнала (ПЛАН-(ЖС) §5). */
    onJump: ((String, Int) -> Unit)? = null,
    /** Галерея и Скрепка; `null` — кнопок нет. */
    onAttach: ((AttachItem) -> Unit)? = null,
    /** Эмоджи при пустой строке — отдельным сообщением. */
    onEmojiSend: ((String) -> Unit)? = null,
    /** Открыть фото и сохранить файл из сообщения (Решения §3, §6). */
    attachments: AttachmentActions = AttachmentActions(),
    /** Плитки скрепки на этой платформе. */
    attachItems: List<AttachItem> = AttachItem.entries,
    /**
     * Унести чужую запись к себе на страницу: `(messageId, круг записи)`.
     *
     * `null` — уносить некуда или незачем: личная переписка. У **своих** реплик кнопки нет
     * никогда — своё и так своё, — и у тех, чей круг не выносится: «по разрешению» отдано
     * поимённо, шифр читают по ключу.
     */
    onCarry: ((Long, Int) -> Unit)? = null,
    /** «Сообщение недоступно, запросить» — личная переписка и группа (2026-10-06). `null` — не нажимается. */
    onAskChatKeys: (() -> Unit)? = null,
    /** Где аватар в группе (пробы «вид группы», 2026-10-07): «Вид» в «Социуме». */
    avatarLook: io.tima.core.ui.AvatarLook = io.tima.core.ui.AvatarLook.Free,
    /** Нажатие на аватар автора — его страница, во всех видах. `null` — не нажимается. */
    onAuthor: ((String) -> Unit)? = null,
    /** Меню «•••» в шапке. `null` — кнопки нет (личная переписка). */
    onMore: (() -> Unit)? = null,
    /**
     * Показывать ли доступность у реплик. `null` — переписка личная: там показывать нечего.
     *
     * Переключатель в шапке, а не в настройках приложения: он относится к этой группе и к
     * этому занятию — разобраться, кто что видит. Уйдя в настройки, он превратился бы в
     * забытый флаг, о котором вспоминают, когда метки уже мешают.
     */
    onCircles: ((Boolean) -> Unit)? = null,
    /**
     * Открыть ветку под сообщением (ADR-0024, К6). `null` — веток нет: личная переписка.
     *
     * Строка «ветка · N ответов» стоит под сообщением, а сами ответы в общем списке не
     * показываются: прочитать разговор дважды — в списке и в ветке — значит удвоить его.
     */
    onThread: ((Long) -> Unit)? = null,
    /**
     * Нажали на неотправленное — отказанное (крестик) или ждущее (круговая стрелка):
     * подокно с причиной. У отказанного там же повтор, у ждущего его нет — очередь
     * повторяет сама (заказчик 2026-09-19).
     */
    onFailed: ((ChatLine) -> Unit)? = null,
    /** Нажатие на отправленное — меню сообщения (Решения §11.1). `null` — меню нет. */
    onMenu: ((ChatLine) -> Unit)? = null,
    /** Долгое нажатие — выделить часть и скопировать (Решения §11.1). `null` — нет. */
    onSelect: ((ChatLine) -> Unit)? = null,
    /** Как называть авторов в группе — «Вид» набора сообществ. */
    authorLook: PersonLook = PersonLook.DEFAULT,
    /** Владелец группы — его полоса салатовая, темы. `null` — неизвестен: все по порядку. */
    ownerId: String? = null,
    /** Выбранные участниками номера оттенков (сервер 0053): человек → 0…99. */
    hues: Map<String, Int> = emptyMap(),
    /**
     * Нажали на аватар или имя в шапке — личная страница собеседника (заказчик
     * 2026-09-19). `null` — у группы: за её шапкой не один человек.
     */
    onPerson: (() -> Unit)? = null,
    /** Картинка аватара собеседника в шапке; `null` — буквы, как было. */
    peerFace: String? = null,
    /**
     * Группа звонка (заказчик 2026-10-02): имя создателя отдельной строкой под названием и
     * «ГЗ» на аватаре — поверх его картинки, а без неё буквами. `null` — группа обычная.
     */
    callCreator: String? = null,
    callGroup: Boolean = false,
    /**
     * Позвонить собеседнику — телефонная кнопка в шапке (макет `03-personal-chat.md`).
     *
     * `null` — звонить нечем или некому: у группы, на платформе без движка. Кнопки
     * тогда нет вовсе, а не погашена: погашенная спрашивает «почему», отсутствующая — нет.
     */
    onCall: (() -> Unit)? = null,
    /**
     * Полоса над лентой — «Идёт звонок · Присоединиться» в группе (ПЛАН-(ГЗ)-ГРУППОВЫХ-ЗВОНКОВ,
     * решение 3а). `null` — полосы нет.
     */
    banner: (@Composable () -> Unit)? = null,
    /**
     * Приглашение в групповой звонок вместо пузыря: что с ним сейчас. `null` у реплики —
     * обычное сообщение.
     */
    invite: (ChatLine) -> CallInvite? = { null },
    onInvite: (ChatLine) -> Unit = {},
    /** Снять закрепление ✕ в полосе (ПЛАН-(ЗО) ЗО3); `null` — права нет, крестика нет. */
    onUnpin: (() -> Unit)? = null,
    /** Нажатие на текст шапки группы — окно «Описание» (ПЛАН-(ЗО) ЗО4); `null` — у личной. */
    onShowcase: (() -> Unit)? = null,
    /**
     * Переход к сообщению, которого нет в ленте: `(вид, переписка, номер)` — спросить и показать
     * одно (Решения §11.4). `null` — переход только внутри загруженного.
     */
    onPeek: ((String, String, Long) -> Unit)? = null,
    onPeekClose: () -> Unit = {},
    /** Из показанного одним — открыть его переписку (другая переписка). `null` — кнопки нет. */
    onOpenChat: ((String) -> Unit)? = null,
) {
    val cancelledMark = Tima.words.auth.identityCancelledMark
    val colors = Tima.colors
    val words = Tima.words.chat
    androidx.compose.runtime.CompositionLocalProvider(LocalChatReceipt provides receipt) {
    Column(modifier.fillMaxSize().background(colors.surface)) {
        SubwindowHeader(
            title = peer,
            onBack = onBack,
            caption = caption,
            // Аватар собеседника — по макету подокна чата. Буквы, а не картинка: картинки
            // приезжают вместе с медиа, а место под них тут то же самое.
            avatar = if (callGroup) Tima.words.groupCall.badge else peerLetters(peer),
            avatarPicture = peerFace,
            avatarOverlay = if (callGroup) Tima.words.groupCall.badge else null,
            subcaption = callCreator,
            // Чипы «Доступность» и «Участники» из шапки убраны (решение заказчика
            // 2026-09-17: это про группу, а не про переписку). Они живут в меню «•••».
            onMore = onMore,
            onPerson = onPerson ?: onShowcase,
            right = onCall?.let { call ->
                {
                    IconButton(
                        glyph = "\uD83D\uDCDE",
                        onClick = call,
                        // Метка не для красоты: тот же глиф носит окно «Телефон» в
                        // рейке, и живой сценарий, выбирающий по надписи, попадал бы
                        // то в кнопку, то в рейку — через раз и молча.
                        modifier = Modifier.testTag(CALL_BUTTON_TAG),
                    )
                }
            },
        )

        // Полоса «ключа нет вовсе» — НАД лентой, а не над вводом: она не про отдельные
        // сообщения, а про то, что список пуст не потому, что в группе не пишут.
        if (state.noGroupKey) {
            NoKeyYet(expect = state.expectKey, phrase = state.phrase, onPhrase = onPhrase, onRequest = onRequestKey)
        }
        banner?.invoke()

        // Блок ответа и вставленное — тем же способом, что в просмотре редактора (заказчик 2026-10-20).
        val (replyLook, quoteLook) = chatLooks(state, peer, authorLook, words.someone)
        // Переходы по ответу, вставленному и «Продолжить чтение» — внутри переписки (§11.4).
        val jumps = remember { FeedJumps() }
        jumps.onJump = onJump
        val openMessage: (String, String, Long) -> Unit = { kind, chat, number ->
            if (state.lines.any { it.serverId == number && it.chatId == chat }) jumps.go(number)
            else onPeek?.invoke(kind, chat, number)
        }
        // Закреплённое — полоса под шапкой: строка сообщения, нажатие — переход к нему (§11.4),
        // ✕ — снять, у кого право (ПЛАН-(ЗО) ЗО3). Не загружено — заглушка.
        if (state.pinned > 0) {
            val found = state.lines.firstOrNull { it.serverId == state.pinned }
            PinnedBar(
                text = found?.takeUnless { it.deleted }?.text,
                onOpen = { if (found != null) jumps.go(state.pinned) },
                onUnpin = onUnpin?.takeIf { state.pinMay },
            )
        }
        androidx.compose.runtime.CompositionLocalProvider(
            LocalReplyLook provides replyLook,
            LocalQuoteLook provides quoteLook,
            LocalAttachmentActions provides attachments,
            LocalOpenMessage provides openMessage,
        ) {
        Feed(
            jumps = jumps,
            lines = state.lines,
            // **В личной переписке подписи нет вовсе.** Так в макете: у чужой реплики
            // остаётся полоса автора, но ни аватара, ни имени внутри пузыря нет
            // (`Layout-UI-light/телефон/подокна/чат.html`, первый кадр). Собеседник один
            // и назван в шапке — подпись у каждого пузыря повторяла бы её.
            // В группе имя берётся по отправителю; неизвестное не выдумываем:
            // «Участник» честнее чужого имени.
            authorName = { line ->
                // Личность отменена владельцем (Р30) — это и подпись, и предупреждение: в
                // личной переписке тоже, хотя обычно там подписи нет.
                if (line.senderId != null && line.senderId in cancelledSenders) cancelledMark
                else if (!state.group) null
                else state.names[line.senderId]?.line(authorLook) ?: words.someone
            },
            authorLetter = { line -> state.names[line.senderId]?.letter() ?: "+" },
            ownerId = if (state.group) ownerId else null,
            hues = hues,
            // Полосы авторов — только в группе: в личной переписке собеседник один.
            stripes = state.group,
            authorFace = { line -> if (state.group) state.faces[line.senderId] else null },
            modifier = Modifier.weight(1f),
            showCircles = state.showCircles,
            onNarrow = onNarrow,
            onCarry = onCarry,
            onAskChatKeys = onAskChatKeys?.takeIf { state.chatKeysMay || state.keyAskMay },
            avatarLook = avatarLook,
            onAuthor = onAuthor,
            lostKeys = state.lostKeys,
            onThread = onThread,
            onFailed = onFailed,
            onMenu = onMenu,
            onSelect = onSelect,
            invite = invite,
            onInvite = onInvite,
        )
        }

        // Полоса недоступной истории — над вводом и ОДНА на экран, а не у каждой строки.
        // Запрос уходит сразу за все недостающие версии; кнопка у каждого сообщения
        // обещала бы точность, которой в механизме нет.
        // В личной переписке полосы нет, пока не понадобилась фраза: просят нажатием на само
        // сообщение.
        val phraseWanted = state.phraseWanted || state.notice is ChatNotice.KeysNeedPhrase
        if ((state.keyAskMay || (state.chatKeysMay && phraseWanted)) &&
            state.lines.any { it.display == MessageDisplay.UNREADABLE }
        ) {
            StoryUnavailable(
                expect = state.expectKey,
                // Заверенному фраза не нужна (заказчик 2026-10-06): первое нажатие просит без
                // неё, отказ по подписи открывает поле, и следующее нажатие подписывает фразой.
                phraseInputNeeded = phraseWanted,
                phrase = state.phrase,
                onPhrase = onPhrase,
                onAsk = onAskChatKeys ?: onRequestKey,
                onRequest = onRequestKey,
            )
        }

        state.notice?.let {
            Trouble(
                trouble = it,
                onClose = onCloseMessage,
                // Подтверждение появляется только у предупреждения: у остальных заметок
                // подтверждать нечего, и кнопка там означала бы действие без действия.
                onConfirm = when (it) {
                    is ChatNotice.NarrowWarning -> onNarrowConfirm
                    is ChatNotice.DeleteAsk -> onDeleteConfirm
                    else -> null
                },
            )
        }

        // Полоса правки над строкой ввода (ADR-0045): видно, что отправка исправит, а не напишет.
        state.replyTo?.let { reply ->
            if (onReplyCancel != null) {
                val name = if (!state.group) peer else state.names[reply.userId]?.line(authorLook) ?: words.someone
                ReplyBar(look = ReplyLook(name, reply.preview), onCancel = onReplyCancel)
            }
        }
        if (state.editing > 0 && onEditCancel != null) {
            EditBar(onCancel = onEditCancel)
        }

        // Выбора уровня над строкой нет (Решения §9): быстрая отправка — −1 или 2, другой — в
        // редакторе. Круг по-прежнему приходит параметром — его покажет и сменит редактор.
        InputLine(
            typed = state.draft,
            onSet = onSet,
            onSend = onSend,
            source = source,
            // «Знаки md» выключены — набранный знак остаётся текстом; при правке своего — включены (ADR-0045).
            marks = state.marks || state.editing > 0,
            onEditor = onEditor,
            composed = composed,
            onComposedDrop = onComposedDrop,
            onComposedRestore = onComposedRestore,
            onAttach = onAttach,
            onEmojiSend = onEmojiSend,
            attachItems = attachItems,
        )
    }
    }
}

/**
 * Лента реплик.
 *
 * `reverseLayout`: список приходит **новым сверху** — так его отдаёт запрос к базе, — а на
 * экране новое внизу, и открывается переписка на последнем сообщении. Переворачивать
 * список в коде значило бы делать в памяти то, что уже сделано в SQL, и заодно потерять
 * бесплатное «прокрутка начинается снизу».
 */
@Composable
private fun Feed(
    /** Переходы внутри переписки (§11.4); `null` — их нет (ветка). */
    jumps: FeedJumps? = null,
    lines: List<ChatLine>,
    authorName: (ChatLine) -> String?,
    authorLetter: (ChatLine) -> String = { "+" },
    authorFace: (ChatLine) -> ImageBitmap? = { null },
    ownerId: String? = null,
    hues: Map<String, Int> = emptyMap(),
    stripes: Boolean = false,
    modifier: Modifier = Modifier,
    /** Показывать ли метку круга у реплик. По умолчанию нет — см. [ChatState.showCircles]. */
    showCircles: Boolean = false,
    onNarrow: ((Long, Int, Int) -> Unit)? = null,
    onCarry: ((Long, Int) -> Unit)? = null,
    onAskChatKeys: (() -> Unit)? = null,
    /** Где аватар в группе (пробы «вид группы», 2026-10-07): «Вид» в «Социуме». */
    avatarLook: io.tima.core.ui.AvatarLook = io.tima.core.ui.AvatarLook.Free,
    /** Нажатие на аватар автора — его страница, во всех видах. `null` — не нажимается. */
    onAuthor: ((String) -> Unit)? = null,
    /** Номера сообщений, к которым ключа не осталось ни у кого: «запросить» у них нет. */
    lostKeys: Set<Long> = emptySet(),
    onThread: ((Long) -> Unit)? = null,
    onFailed: ((ChatLine) -> Unit)? = null,
    /** Нажатие на отправленное — меню сообщения (Решения §11.1). `null` — меню нет. */
    onMenu: ((ChatLine) -> Unit)? = null,
    /** Долгое нажатие — выделить часть и скопировать (Решения §11.1). `null` — нет. */
    onSelect: ((ChatLine) -> Unit)? = null,
    invite: (ChatLine) -> CallInvite? = { null },
    onInvite: (ChatLine) -> Unit = {},
) =
    // Сообщения — своя группа размера (ПЛАН-(Ш)-ШРИФТОВ Ш3): её укрупняют чаще всего, и
    // от списка чатов она не зависит.
    ProvidePlace(TextPlace.MESSAGES) {
    // ── К НОВОМУ СООБЩЕНИЮ (заказчик 2026-10-09: «прокручиваем») ──────────────
    //
    // Список держит позицию по ключу строки: новое сообщение встаёт на место 0, а на экране
    // остаётся прежнее — новое оказывается за нижним краем. Особенно при открытой клавиатуре:
    // отправил — и не видишь отправленного (realme, PH-47 навыка телефонов).
    //
    // Своё — к нему всегда: его только что написали. Входящее — только если человек и так был
    // внизу (на месте 0 или 1 — после вставки прежнее нижнее съезжает на 1): читающего историю
    // новое сообщение сдёргивать не должно.
    val listState = rememberLazyListState()
    val newest = lines.firstOrNull()
    LaunchedEffect(newest?.dedupKey) {
        if (newest != null && (newest.outgoing || listState.firstVisibleItemIndex <= 1)) {
            listState.animateScrollToItem(0)
        }
    }
    // Просьба перейти — от блока ответа и вставленного; ведёт лента: прокрутка у неё.
    // Прокрутка — своей задачей, как у «назад» и «вперёд»: `taken()` меняет ключ эффекта, и прокрутка
    // внутри него отменялась на следующем кадре — переход писался в журнал, но не случался (2026-10-20).
    if (jumps != null) {
        val jumpScope = androidx.compose.runtime.rememberCoroutineScope()
        val feed by androidx.compose.runtime.rememberUpdatedState(lines)
        LaunchedEffect(jumps.request) {
            val target = jumps.request ?: return@LaunchedEffect
            jumps.taken()
            jumpScope.launch { jumps.perform(target, feed, listState) }
        }
    }
    val flashColor = Tima.colors.activity.copy(alpha = 0.22f)
    // Список в сообщении раскрыли или свернули — лента сдвигается, строка пункта стоит на месте (2026-10-21).
    val nudge: (Float) -> Unit = remember(listState) { { d -> listState.dispatchRawDelta(d) } }
    Box(modifier.fillMaxWidth()) {
    androidx.compose.runtime.CompositionLocalProvider(LocalFeedNudge provides nudge) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        reverseLayout = true,
        contentPadding = PaddingValues(
            horizontal = TimaSpacing.about3,
            vertical = TimaSpacing.about4,
        ),
        // Зазор между репликами — вдвое меньше прежнего (заказчик 2026-10-07), везде; над репликой
        // с аватаром — прежний: аватар выступает в него вверх (см. отступ у реплики ниже).
        verticalArrangement = Arrangement.spacedBy(TimaSpacing.about3 / 2, Alignment.Bottom),
    ) {
        // Порядок появления авторов — по первому сообщению в переписке (заказчик 2026-09-19):
        // список идёт новым сверху, поэтому считается с конца. Свои и владелец в счёт не идут —
        // у своих полосы нет, у владельца она салатовая. На двух устройствах одного аккаунта
        // порядок совпадает: сообщения выстроены по времени написания.
        // Выбравшие цвет сами в порядок не входят: у них номер свой, а автомат обходит их
        // номера (`taken`), чтобы не подсадить новичка на чужой цвет.
        val order: Map<String, Int> = if (stripes) {
            lines.asReversed().asSequence()
                .filter { !it.outgoing && it.senderId != null && it.senderId != ownerId && it.senderId !in hues }
                .map { it.senderId!! }.distinct()
                .withIndex().associate { (at, who) -> who to at }
        } else {
            emptyMap()
        }
        val taken = hues.values.toSet()
        items(lines, key = { it.dedupKey }) { line ->
            val index = lines.indexOf(line)
            // Предыдущее по времени лежит НИЖЕ в списке: список идёт новым сверху.
            val previous = lines.getOrNull(index + 1)
            // Приглашение в групповой звонок — карточкой словами читающего (решение 3а).
            val called = invite(line)
            if (called != null) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = if (line.outgoing) Arrangement.End else Arrangement.Start,
                ) { CallInviteCard(called, onJoin = { onInvite(line) }) }
                return@items
            }
            // Переход сюда — коротко подсвечено (В34).
            Box(if (jumps != null && line.serverId > 0 && jumps.highlight == line.serverId) Modifier.background(flashColor, androidx.compose.foundation.shape.RoundedCornerShape(10.dp)) else Modifier) {
            Reply(
                line = line,
                author = authorName(line),
                letter = authorLetter(line),
                face = authorFace(line),
                strip = if (stripes) authorStrip(order[line.senderId], owner = line.senderId == ownerId, hue = hues[line.senderId], taken = taken) else null,
                // Смена автора разрывает цепочку, даже когда обе реплики чужие: иначе в
                // группе два человека подряд слились бы в одного, и имя второго не
                // показалось бы вовсе.
                continuation = previous?.outgoing == line.outgoing &&
                    previous?.senderId == line.senderId,
                showCircle = showCircles,
                onNarrow = onNarrow,
                onCarry = onCarry,
                onAskChatKeys = onAskChatKeys,
                lostKeys = lostKeys,
                onThread = onThread,
                avatarLook = avatarLook,
                onAuthor = onAuthor,
                onFailed = onFailed,
                onMenu = onMenu,
                onSelect = onSelect,
            )
            }
        }
    }
    }
    if (jumps != null) JumpButtons(jumps, lines, listState, Modifier.align(Alignment.BottomEnd).padding(TimaSpacing.about3))
    }
    }

/** Стандартный открытый уровень в группе — цифра у него не рисуется (В29). */
private const val STANDARD_OPEN_LEVEL = 2

/** Метка цифры уровня — для сценариев. */
const val LEVEL_DIGIT_TAG = "chat:level"

/**
 * Уровень у реплики — цифра в кружке перед временем (Решения, В29). Цвет говорит быстрее цифры:
 * зелёная — можно унести (0, 1), янтарная — по разрешению (3), тихая — своим (2). Можно сузить —
 * цифра нажимается и открывает список кругов уже нынешнего.
 */
@Composable
private fun LevelDigit(level: Int, onClick: (() -> Unit)?) {
    val colors = Tima.colors
    val fill = when (level) {
        0, 1 -> colors.confirmed
        3 -> colors.activity
        else -> colors.border
    }
    androidx.compose.foundation.layout.Box(
        Modifier
            .size(18.dp)
            .background(fill, androidx.compose.foundation.shape.CircleShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .testTag(LEVEL_DIGIT_TAG),
        contentAlignment = Alignment.Center,
    ) {
        Caption(level.toString(), fontSize = TimaType.sz6, weight = FontWeight.Bold, color = if (level == 3) colors.onAmber else colors.onAccent)
    }
}

/**
 * Одна реплика: пузырь, время и отметка; в группе — ещё имя и аватар автора.
 *
 * `автор == null` означает «подписывать нечем и незачем» — личная переписка. Аватар без
 * имени не бывает: обе метки отвечают на один вопрос «кто это», и в личной переписке он
 * не задаётся.
 */
@Composable
private fun Reply(
    line: ChatLine,
    author: String?,
    continuation: Boolean,
    face: ImageBitmap? = null,
    letter: String = "+",
    /** Цвет полосы автора; `null` — салатовый темы, как было. */
    strip: Color? = null,
    showCircle: Boolean = false,
    onNarrow: ((Long, Int, Int) -> Unit)? = null,
    onCarry: ((Long, Int) -> Unit)? = null,
    onAskChatKeys: (() -> Unit)? = null,
    /** Где аватар в группе (пробы «вид группы», 2026-10-07): «Вид» в «Социуме». */
    avatarLook: io.tima.core.ui.AvatarLook = io.tima.core.ui.AvatarLook.Free,
    /** Нажатие на аватар автора — его страница, во всех видах. `null` — не нажимается. */
    onAuthor: ((String) -> Unit)? = null,
    /** Номера сообщений, к которым ключа не осталось ни у кого: «запросить» у них нет. */
    lostKeys: Set<Long> = emptySet(),
    onThread: ((Long) -> Unit)? = null,
    onFailed: ((ChatLine) -> Unit)? = null,
    /** Нажатие на отправленное — меню сообщения (Решения §11.1). `null` — меню нет. */
    onMenu: ((ChatLine) -> Unit)? = null,
    /** Долгое нажатие — выделить часть и скопировать (Решения §11.1). `null` — нет. */
    onSelect: ((ChatLine) -> Unit)? = null,
) {
    // Служебная строка — не реплика: у неё нет автора, стороны и времени отправки. Пузырь
    // приписал бы ей всё это разом, поэтому она идёт полосой по центру.
    if (line.display == MessageDisplay.SYSTEM) {
        SystemLine(line.text.orEmpty())
        return
    }
    // ── СИСТЕМНОЕ РИСУЕТСЯ СЛОВАМИ ЧИТАЮЩЕГО (Л15) ──────────────────────────
    //
    // Присланный текст не показывается **вовсе**, и это не бережливость: иначе фраза
    // уехала бы на языке отправителя — русский заблокировал испанца, и тот получил бы
    // русский текст. Вид едет в подписанных метаданных, подделать его нельзя.
    //
    // Полосой по центру, как служебная: автор у неё есть, но писал её не человек, и
    // пузырь приписал бы ей разговор, которого не было.
    if (line.kind == KIND_SYSTEM) {
        SystemLine(Tima.words.chat.blockedYou)
        return
    }
    // Пузырь и строка ветки — один элемент списка, поэтому столбец: два соседа в
    // элементе ленивого списка легли бы друг на друга.
    // Неотправленное нажимается целиком — крестик мал для пальца, а вопрос «что с ним
    // делать» относится ко всему сообщению.
    val failed = (line.display == MessageDisplay.FAILED || line.display == MessageDisplay.PENDING) &&
        line.outgoing && onFailed != null
    // Над репликой с аватаром — прежний зазор 12: аватар выступает в него вверх (2026-10-07).
    val withAvatar = !line.outgoing && !continuation && author != null
    // Отправленное — меню (Решения §11.1): своё и чужое, кроме удалённого и нечитаемого — с ними
    // делать нечего. Номер обязателен: все пункты меню ссылаются на сообщение по нему.
    val menu = !failed && onMenu != null && line.serverId > 0 && !line.deleted &&
        line.display != MessageDisplay.UNREADABLE && line.text != null
    // Выбор нескольких (заказчик 2026-10-19): долгое нажатие ставит галочку; пока выбрано хоть одно,
    // обычное нажатие ставит и снимает её вместо меню.
    val selection = LocalChatSelection.current
    val canSelect = selection != null && selectable(line)
    val choosing = selection?.active == true
    val chosen = selection?.keys?.contains(line.dedupKey) == true
    // Нажатие по самому сообщению (заказчик 2026-10-20) — только то, что в него встроено: ссылки,
    // ответ, подборка, фото, файл. Меню действий — нажатием рядом с пузырём и по дате; долгое нажатие
    // по пузырю — выбор, как раньше. Неотправленное — целиком, как было.
    val longPress: (() -> Unit)? = if (canSelect) ({ selection!!.toggle(line) }) else onSelect?.let { { it(line) } }
    val quiet = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val bubbleTap = when {
        failed -> Modifier
        choosing && canSelect -> Modifier.clickable(interactionSource = quiet, indication = null) { selection!!.toggle(line) }
        else -> Modifier.combinedClickable(interactionSource = quiet, indication = null, onClick = {}, onLongClick = longPress)
    }
    val timeTap: (() -> Unit)? = if (menu && !choosing) ({ onMenu!!(line) }) else null
    Row(verticalAlignment = Alignment.CenterVertically) {
    if (choosing && canSelect && line.outgoing) SelectMark(chosen)
    Column(
        (
            if (choosing && canSelect) {
                Modifier.clickable { selection!!.toggle(line) }
            } else if (failed) {
                Modifier.clickable { onFailed!!(line) }
            } else if (menu) {
                // Нажатие — меню; долгое — выбор нескольких (2026-10-19); правая кнопка мыши (ПК) — меню (§11.1).
                Modifier.secondaryClick { onMenu!!(line) }
                    .combinedClickable(
                        onClick = { onMenu!!(line) },
                        onLongClick = if (canSelect) ({ selection!!.toggle(line) }) else onSelect?.let { { it(line) } },
                    )
            } else if (canSelect) {
                Modifier.combinedClickable(onClick = {}, onLongClick = { selection!!.toggle(line) })
            } else {
                Modifier
            }
        )
            .weight(1f)
            .then(if (withAvatar) Modifier.padding(top = TimaSpacing.about3 / 2) else Modifier),
    ) {
        // Только эмоджи — крупно и без пузыря (Решения §2): это не текст, а знак.
        val emoji = line.text?.takeIf { line.rich == null && !line.deleted && isEmojiOnly(it) }
        val rich = line.rich
        androidx.compose.runtime.CompositionLocalProvider(LocalBubbleTap provides bubbleTap, LocalTimeTap provides timeTap) {
        if (rich != null && !line.deleted && rich.isOutside()) {
            // Фото без текста и файлы — вне пузыря (Решения §2).
            OutsideBubble(line) { AttachmentsView(rich) }
        } else if (emoji != null) {
            OutsideBubble(line) { Caption(emoji.trim(), fontSize = 44.sp) }
        } else {
            Bubbled(
                line, author, letter, face, strip, continuation, showCircle, onNarrow, onCarry, onAskChatKeys, line.serverId in lostKeys,
                look = avatarLook,
                onAvatar = line.senderId?.let { who -> onAuthor?.let { open -> { open(who) } } },
            )
        }
        }
        // «Ветка · N ответов» — под сообщением, отдельной строкой, а не внутри пузыря:
        // это не часть сказанного, а вход в разговор о нём (ADR-0024 §7).
        //
        // Строки нет, когда ответов нет: пустая ветка обещала бы разговор, которого не
        // было.
        if (onThread != null && line.replies > 0 && line.serverId > 0) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = TimaSpacing.about1),
                horizontalArrangement = if (line.outgoing) Arrangement.End else Arrangement.Start,
            ) {
                Chip(
                    Tima.words.chat.thread(line.replies),
                    kind = ChipKind.Quiet,
                    onClick = { onThread(line.serverId) },
                )
            }
        }
    }
    if (choosing && canSelect && !line.outgoing) SelectMark(chosen)
    }
}

/**
 * Как назвать ответ и вставленное: автор и строка по этой переписке, из другой — «Сообщение из другой
 * переписки» до получения по номеру (ПЛАН-(РС) РС7). Одно на ленту и просмотр редактора (заказчик
 * 2026-10-20: «Показать сообщение» — как в чате).
 */
fun chatLooks(
    state: ChatState,
    peer: String,
    authorLook: PersonLook,
    someone: String,
): Pair<(io.tima.domain.chat.RichBlock.Reply) -> ReplyLook, (String, String, Long) -> QuoteLook> {
    val remote: (String, Long) -> ChatLine? = { chat, number ->
        (state.found["$chat/$number"] as? io.tima.domain.chat.FoundMessage.Found)?.line
    }
    val replyLook: (io.tima.domain.chat.RichBlock.Reply) -> ReplyLook = { reply ->
        val found = state.lines.firstOrNull { it.serverId == reply.messageId } ?: remote(reply.chatId, reply.messageId)
        val name = when {
            !state.group -> peer
            else -> state.names[reply.userId]?.line(authorLook) ?: someone
        }
        ReplyLook(name = name, preview = found?.text, deleted = found?.deleted == true)
    }
    // Вставленное и подкладка — по этой же переписке; из другой — «Сообщение из другой переписки»
    // до получения по номеру (ПЛАН-(РС) РС7).
    val quoteLook: (String, String, Long) -> QuoteLook = { _, chat, number ->
        // Продолжения в ленте нет, но подкладка показывает именно его (Решения §11.3).
        val found = (state.lines.firstOrNull { it.serverId == number && it.chatId == chat }
            ?: state.continuations.firstOrNull { it.serverId == number && it.chatId == chat }
            ?: remote(chat, number))
        if (found == null) {
            // Сервер ответил «нет такого» — так и сказать, а не «из другой переписки».
            val stub = state.found["$chat/$number"] as? io.tima.domain.chat.FoundMessage.Unavailable
            QuoteLook(null, null, deleted = stub?.reason == "missing", why = stub?.reason?.takeIf { it != "missing" }, whyLevel = stub?.level)
        } else QuoteLook(
            name = when {
                found.outgoing -> null
                !state.group && found.chatId == state.lines.firstOrNull()?.chatId -> peer
                else -> state.names[found.senderId]?.line(authorLook) ?: someone
            },
            text = found.text,
            deleted = found.deleted,
            rich = found.rich?.takeUnless { found.deleted },
        )
    }
    return replyLook to quoteLook
}

/** Ответ и вставленное по переписке [state] — для того, что показывается вне ленты (просмотр редактора). */
@Composable
fun ChatLooks(state: ChatState, peer: String, authorLook: PersonLook, content: @Composable () -> Unit) {
    val (reply, quote) = chatLooks(state, peer, authorLook, Tima.words.chat.someone)
    androidx.compose.runtime.CompositionLocalProvider(LocalReplyLook provides reply, LocalQuoteLook provides quote, content = content)
}

/** Нажатие по самому сообщению — только встроенное (заказчик 2026-10-20); ставит строка ленты. */
internal val LocalBubbleTap = androidx.compose.runtime.staticCompositionLocalOf<Modifier> { Modifier }

/** Нажатие по дате и времени — меню действий с сообщением (заказчик 2026-10-20). */
internal val LocalTimeTap = androidx.compose.runtime.staticCompositionLocalOf<(() -> Unit)?> { null }

/**
 * Сообщение вне пузыря (Решения §2): эмоджи, фото без текста, файл. Сторона — как у пузыря, под
 * содержимым — время и отметка.
 */
@Composable
internal fun OutsideBubble(line: ChatLine, content: @Composable () -> Unit) = Row(
    Modifier.fillMaxWidth(),
    horizontalArrangement = if (line.outgoing) Arrangement.End else Arrangement.Start,
) {
    Column(horizontalAlignment = if (line.outgoing) Alignment.End else Alignment.Start) {
        Box(LocalBubbleTap.current) { content() }
        val timeTap = LocalTimeTap.current
        Row(
            (if (timeTap != null) Modifier.clickable(onClick = timeTap) else Modifier).testTag("chat:time"),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (line.edits > 0) Tertiary(Tima.words.chat.edited, lineOne = true)
            Tertiary(time(line.atMs), lineOne = true)
            markOf(line.display, line.atMs, LocalChatReceipt.current)?.let { Mark(it) }
            // Своё фото или файл забрал получатель — галочки «приложение забрало» (Решения §6).
            val actions = LocalAttachmentActions.current
            val media = line.rich?.media.orEmpty()
            if (line.outgoing && media.isNotEmpty() && media.all { actions.fetched(it.mediaId) }) {
                Caption("✓✓", fontSize = TimaType.sz6, weight = FontWeight.Bold, color = Tima.colors.confirmed, modifier = Modifier.testTag(FETCHED_TAG))
            }
        }
    }
}

/**
 * Только эмоджи — от одного до трёх знаков, без букв, цифр и знаков препинания. Грубая граница,
 * но честная: «😀😀» — знак, «ок 👍» — текст.
 */
internal fun isEmojiOnly(text: String): Boolean {
    val t = text.trim()
    if (t.isEmpty() || t.length > 24) return false
    var pictographs = 0
    var i = 0
    while (i < t.length) {
        val c = t[i]
        when {
            c.isHighSurrogate() -> { pictographs++; i += 2; continue }
            // Склейка, вариант «картинкой» и рамка клавиши — части знака, а не знаки.
            c.code == 0x200D || c.code == 0xFE0F || c.code == 0x20E3 -> Unit
            c.code in 0x2190..0x2BFF || c.code == 0xA9 || c.code == 0xAE -> pictographs++
            else -> return false
        }
        i++
    }
    return pictographs in 1..MAX_EMOJI_ALONE * 2
}

/** Сколько эмоджи «одни» рисуются крупно. */
private const val MAX_EMOJI_ALONE = 3

/** Служебная строка: сказанное приложением, а не человеком. */
@Composable
private fun SystemLine(text: String) = Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = TimaSpacing.about2),
    horizontalArrangement = Arrangement.Center,
) {
    Tertiary(text, lineOne = false)
}

@Composable
private fun Bubbled(
    line: ChatLine,
    author: String?,
    letter: String,
    face: ImageBitmap?,
    strip: Color?,
    continuation: Boolean,
    showCircle: Boolean,
    onNarrow: ((Long, Int, Int) -> Unit)?,
    onCarry: ((Long, Int) -> Unit)?,
    onAskChatKeys: (() -> Unit)? = null,
    keyLost: Boolean = false,
    look: io.tima.core.ui.AvatarLook = io.tima.core.ui.AvatarLook.Free,
    onAvatar: (() -> Unit)? = null,
) {
    // Сужение — кнопкой-цифрой со списком (В29): нажали цифру — под сообщением круги уже нынешнего.
    var narrowing by androidx.compose.runtime.remember(line.dedupKey) { androidx.compose.runtime.mutableStateOf(false) }
    val canNarrow = onNarrow != null && line.serverId > 0 && MessageCircle.narrowerThan(line.level).isNotEmpty()
    Bubbled(line, author, letter, face, strip, continuation, showCircle, onNarrow, onCarry, onAskChatKeys, keyLost, look, onAvatar,
        narrowing = narrowing, onDigit = if (canNarrow) ({ narrowing = !narrowing }) else null, onNarrowed = { narrowing = false })
}

@Composable
private fun Bubbled(
    line: ChatLine,
    author: String?,
    letter: String,
    face: ImageBitmap?,
    strip: Color?,
    continuation: Boolean,
    showCircle: Boolean,
    onNarrow: ((Long, Int, Int) -> Unit)?,
    onCarry: ((Long, Int) -> Unit)?,
    onAskChatKeys: (() -> Unit)?,
    keyLost: Boolean,
    look: io.tima.core.ui.AvatarLook,
    onAvatar: (() -> Unit)?,
    narrowing: Boolean,
    onDigit: (() -> Unit)?,
    onNarrowed: () -> Unit,
) {
    // Подкладка — внутри пузыря, во всю его ширину (заказчик 2026-10-20; прежде — под пузырём).
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (line.outgoing) Alignment.End else Alignment.Start) {
        BubbleOf(line, author, letter, face, strip, continuation, showCircle, onNarrow, onCarry, onAskChatKeys, keyLost, look, onAvatar, narrowing, onDigit, onNarrowed)
    }
}

/**
 * Сообщение, как его показывает лента (заказчик 2026-10-20: «Показать сообщение» — тем же способом,
 * что в чате): фото без текста и файлы — вне пузыря, остальное, с подкладками, — в пузыре. Ответ и
 * вставленное называет [ChatLooks].
 */
@Composable
fun MessageView(rich: RichContent, my: Boolean = true, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = if (my) Alignment.End else Alignment.Start) {
        if (rich.isOutside()) AttachmentsView(rich) else Bubble(my = my) { RichBody(rich) }
    }
}

@Composable
private fun BubbleOf(
    line: ChatLine,
    author: String?,
    letter: String,
    face: ImageBitmap?,
    strip: Color?,
    continuation: Boolean,
    showCircle: Boolean,
    onNarrow: ((Long, Int, Int) -> Unit)?,
    onCarry: ((Long, Int) -> Unit)?,
    onAskChatKeys: (() -> Unit)?,
    keyLost: Boolean,
    look: io.tima.core.ui.AvatarLook,
    onAvatar: (() -> Unit)?,
    narrowing: Boolean,
    onDigit: (() -> Unit)?,
    onNarrowed: () -> Unit,
) = Bubble(
    my = line.outgoing,
    author = author,
    // Буква — ник, иначе имя, иначе имя пользователя, иначе «+» (заказчик 2026-09-18).
    avatar = if (author != null) letter else null,
    avatarImage = face,
    strip = strip,
    continuation = continuation,
    look = look,
    onAvatar = onAvatar,
    surface = LocalBubbleTap.current,
    bottom = {
        // Уровень — цифрой в кружке перед временем, только нестандартный (В29: −1 и 2 —
        // стандарт); с включённым показом кругов — и стандартный открытый. Шифр метки не носит.
        if (line.level >= 0 && (line.level != STANDARD_OPEN_LEVEL || showCircle)) LevelDigit(line.level, onDigit)
        // Дата и время — меню действий с сообщением (заказчик 2026-10-20).
        val timeTap = LocalTimeTap.current
        Row(
            (if (timeTap != null) Modifier.clickable(onClick = timeTap) else Modifier).testTag("chat:time"),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Исправленное (ADR-0045): пометка у времени — у всех, кто видит сообщение.
            if (line.edits > 0) Tertiary(Tima.words.chat.edited, lineOne = true)
            Tertiary(time(line.atMs), lineOne = true)
            markOf(line.display, line.atMs, LocalChatReceipt.current)?.let { Mark(it) }
        }
    },
) {
    val text = line.text
    val rich = line.rich
    when {
        // Удалено у всех (Решения §8): строка осталась, содержимого нет.
        line.deleted -> Chip(Tima.words.chat.deleted, kind = ChipKind.Quiet)
        // Оформленное (ПЛАН-(РС) РС4) — блоками; обычное — текстом, как было.
        text != null && rich != null -> RichBody(rich)
        // Простое — ссылки в нём синие и нажимаются, под текстом «Перейти» (заказчик 2026-10-19).
        text != null -> PlainWithLinks(text)

        // **«Не читается» и «ещё не разобрано» — разные вещи, и путать их нельзя.**
        // Первое окончательно: ключа нет, подпись не сошлась. Второе — секунда между
        // записью конверта и его расшифровкой, и человеку в этот миг говорить «не
        // читается» значит соврать: сообщение сейчас появится.
        //
        // Нашлось на живом прогоне: экран показывал «не читается» на сообщении, которое в
        // базе лежало разобранным. Список переписок при этом различал их правильно — то
        // есть два места в одном приложении говорили человеку разное.
        // В личной переписке — с просьбой: нажатие просит ключи у своих устройств и
        // собеседника (заказчик 2026-10-06: «сообщение недоступно, запросить»).
        // Ключа не осталось ни у кого — просить незачем, и кнопка была бы обещанием без
        // исполнения (2026-10-06).
        line.display == MessageDisplay.UNREADABLE && keyLost -> Chip(Tima.words.chat.messageKeyLost, kind = ChipKind.Quiet)

        line.display == MessageDisplay.UNREADABLE -> if (onAskChatKeys != null) {
            Chip(Tima.words.chat.messageUnavailableAsk, kind = ChipKind.Quiet, onClick = onAskChatKeys)
        } else {
            Chip(Tima.words.chat.messageUnavailable, kind = ChipKind.Quiet)
        }

        else -> Chip(Tima.words.chat.decrypting, kind = ChipKind.Quiet)
    }

    // Кнопка «Добавить себе» — в конце сообщения, у чужих реплик и только у выносимых.
    // «Нельзя унести» показывается отсутствием кнопки, а не серой кнопкой с объяснением:
    // место отдаётся тексту, а метка круга и так говорит, почему (макет, раздел 5).
    if (onCarry != null && !line.outgoing && line.serverId > 0 && CarryToPage.canCarry(line.level)) {
        CarryButton(roomy = roomAfter(line.text)) { onCarry(line.serverId, line.level) }
    }

    // Список сужения — под сообщением, пока открыт цифрой. У неотправленного номера нет.
    if (narrowing && onNarrow != null && line.serverId > 0) {
        NarrowChoice(line.level) { to -> onNarrow(line.serverId, line.level, to); onNarrowed() }
    }
}

/**
 * «Добавить себе» — полная и без слова.
 *
 * Кнопка конкурирует за место в конце сообщения: хватает ширины — стоит полная, не
 * хватает — остаётся круг с тем же знаком. Правый край у обеих общий, поэтому подмена
 * ничего не сдвигает (макет `уровень-сообщения.html`, раздел 5).
 *
 * Порог в знаках, а не в точках: точная ширина текста известна только при разметке, а
 * решение нужно принять до неё. Ошибка на границе безобидна — обе кнопки делают одно и то
 * же и стоят на одном месте.
 */
@Composable
private fun CarryButton(roomy: Boolean, onClick: () -> Unit) = Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.End,
) {
    if (roomy) {
        Chip(Tima.words.chat.addToSelf, kind = ChipKind.Quiet, onClick = onClick)
    } else {
        // Тот же знак, что у метки круга: запись не скачивается, а уходит дальше.
        ButtonCircle(onClick = onClick) { Arrow(Side.Right, color = Tima.colors.text2) }
    }
}

/**
 * Хватает ли места в конце последней строки под слово.
 *
 * Считается по знакам: точная ширина текста известна только при разметке, а решение нужно
 * до неё. Ошибка на границе безобидна — обе кнопки делают одно и то же и стоят на одном
 * месте, меняется лишь то, переносится ли она на свою строку.
 */
private fun roomAfter(text: String?): Boolean {
    val tail = (text ?: return true).length % PER_LINE
    return tail <= PER_LINE - WORD_ROOM
}

/** Знаков в строке пузыря и места под «Добавить себе» — оценка по макету. */
private const val PER_LINE = 34
private const val WORD_ROOM = 15

/**
 * Круги, до которых можно сузить эту реплику.
 *
 * **Список считается от нынешнего круга, а не выбирается из всех.** Расширение отвергнет
 * сервер, но доводить до отказа нельзя: кнопка, которая заведомо не сработает, — это
 * обещание, которого приложение не держит.
 */
@Composable
private fun NarrowChoice(was: Int, onPick: (Int) -> Unit) {
    val narrower = MessageCircle.narrowerThan(was)
    if (narrower.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(TimaSpacing.about1)) {
        Tertiary(Tima.words.chat.narrowTo, lineOne = true)
        // Список, а не ряд чипов: под цифрой — цифра и слово круга, по строке на круг.
        for (circle in narrower) {
            Chip("${circle.level} · ${circle.title}", kind = ChipKind.Quiet, onClick = { onPick(circle.level) })
        }
    }
}



/** Подтверждение «Удалить у всех» и сужения в полосе сообщения — метки для сценариев. */
const val DELETE_CONFIRM_TAG = "msg:delete.confirm"
const val NARROW_CONFIRM_TAG = "chat:narrow.confirm"

/** Метка полосы правки и её «✕». */
const val EDIT_BAR_TAG = "chat:editing"
const val EDIT_CANCEL_TAG = "chat:editing.cancel"

/** Метка полосы ответа и её «✕». */
const val REPLY_BAR_TAG = "chat:reply"
const val REPLY_CANCEL_TAG = "chat:reply.cancel"

/** Полоса ответа над строкой ввода: на что отвечаем, ✕ — не отвечать (Решения §11.1). */
@Composable
private fun ReplyBar(look: ReplyLook, onCancel: () -> Unit) {
    val colors = Tima.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.softAccent)
            .padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2)
            .testTag(REPLY_BAR_TAG),
        horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) { ReplyBlock(look) }
        IconButton(glyph = "✕", onClick = onCancel, modifier = Modifier.testTag(REPLY_CANCEL_TAG))
    }
}

/** Полоса «Изменение» над строкой ввода: идёт правка, ✕ — отменить (ADR-0045). */
@Composable
private fun EditBar(onCancel: () -> Unit) {
    val colors = Tima.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.softAccent)
            .padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2)
            .testTag(EDIT_BAR_TAG),
        horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Caption(Tima.words.chat.editing, fontSize = TimaType.sz5, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
        IconButton(glyph = "✕", onClick = onCancel, modifier = Modifier.testTag(EDIT_CANCEL_TAG))
    }
}

/**
 * Сообщение о беде: единственное на весь экран, и это не случайно.
 *
 * То, что очередь решает сама — повторы, ожидание сети, — человеку сообщать нечем и
 * незачем: это видно по отметке у реплики. Остаётся то, что требует его решения.
 */
@Composable
private fun Trouble(trouble: ChatNotice, onClose: () -> Unit, onConfirm: (() -> Unit)? = null) {
    val colors = Tima.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.softAccent)
            .padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2),
        horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val words = Tima.words.chat
        val text = when (trouble) {
            // Числа обязательны: «слишком большое» без размера человеку бесполезно —
            // он не знает, насколько сокращать.
            is ChatNotice.TooLarge ->
                words.tooLarge(trouble.bytes, trouble.limit)

            // Просьба ушла живым устройствам, а не «серверу»: человеку важно понимать,
            // что ответ зависит от того, откроет ли кто-то из участников приложение.
            is ChatNotice.KeysAsked ->
                words.keysAsked(trouble.devices)

            // Не «попробуйте позже»: ждать здесь бесполезно, и сказать надо именно это.
            ChatNotice.KeysNoHelpers ->
                words.keysNoHelpers

            is ChatNotice.KeysLost ->
                words.keysLost(trouble.messages)

            ChatNotice.KeysNothingMissing ->
                words.keysNothingMissing

            // Не ошибка, а действие. И названы ОБА выхода: устройство, подключённое по
            // QR-коду, фразы не знает, и сообщение «нужна фраза» для него — тупик.
            // Смену ключа при этом может запустить любой участник, то есть сам человек
            // с другого своего устройства, — и после неё группа начнёт читаться вперёд.
            ChatNotice.KeysNeedPhrase ->
                words.keysNeedPhrase

            is ChatNotice.KeysRefused -> trouble.text

            // Главное здесь — второе предложение. Сужение прячет вперёд, а не назад: у
            // тех, кто уже унёс запись к себе, она останется, и узнать об этом человек
            // обязан ДО нажатия.
            is ChatNotice.NarrowWarning ->
                words.narrowWarning(trouble.circle)

            is ChatNotice.Narrowed -> words.narrowed(trouble.circle)
            is ChatNotice.NarrowRefused -> trouble.text
            is ChatNotice.Said -> trouble.text
            is ChatNotice.DeleteAsk -> if (trouble.more.isEmpty()) words.deleteForAllAsk else words.deleteManyAsk(1 + trouble.more.size)
        }
        Caption(
            text,
            fontSize = TimaType.sz5,
            weight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        onConfirm?.let {
            Chip(
                if (trouble is ChatNotice.DeleteAsk) words.menuDeleteForAll else words.narrow,
                kind = ChipKind.Selected,
                onClick = it,
                modifier = Modifier.testTag(if (trouble is ChatNotice.DeleteAsk) DELETE_CONFIRM_TAG else NARROW_CONFIRM_TAG),
            )
        }
        ButtonCircle(onClick = onClose) { Arrow(Side.Right, color = colors.text2) }
    }
}

/**
 * Полоса «ключа у этого устройства нет вовсе».
 *
 * **Это не пустая группа.** Устройство, подключённое по QR-коду, не имеет ни одной версии
 * группового ключа: до ближайшей смены группа для него молчит целиком, включая сообщение,
 * пришедшее минуту назад. Появление устройства смену не запускает — её запускает вход,
 * выход или новая эпоха, то есть раз в месяц.
 *
 * Названы **оба выхода**: попросить ключ у тех, кто в сети, либо написать в группу с
 * другого своего устройства — тогда ключ сменится, и группа начнёт читаться вперёд.
 */
@Composable
private fun NoKeyYet(expect: Boolean, phrase: String, onPhrase: (String) -> Unit, onRequest: () -> Unit) {
    val colors = Tima.colors
    val words = Tima.words.chat
    // Просьба подписывается ключом личности (Р42): первое нажатие открывает поле фразы, второе
    // отправляет (заказчик 2026-10-06, п. 1.7). Слова живут только в поле.
    var open by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.functional)
            .padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about3),
        verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
    ) {
        Caption(
            words.noGroupKey,
            fontSize = TimaType.sz5,
            weight = FontWeight.Bold,
        )
        Caption(
            words.noGroupKeyAbout,
            fontSize = TimaType.sz5,
        )
        if (open && !expect) {
            Field(value = phrase, onChange = onPhrase, hint = words.phraseWords, phrase = true, modifier = Modifier.fillMaxWidth())
        }
        if (!expect) {
            Chip(words.askKey, kind = ChipKind.Selected, onClick = {
                if (!open) open = true else if (phrase.isNotBlank()) onRequest()
            })
        } else {
            Chip(words.asking, kind = ChipKind.Quiet)
        }
    }
}

/**
 * Полоса «часть истории недоступна» с кнопкой запроса.
 *
 * **Почему это не беда.** Сообщения до входа в группу не читаются законно: обёртки
 * прошлых версий ключа выдают только тем, кто был в группе на момент ротации. Показать
 * это ошибкой значило бы сказать человеку, что что-то сломалось, тогда как всё работает
 * как задумано — и одновременно спрятать единственное доступное ему действие.
 */
@Composable
private fun StoryUnavailable(
    expect: Boolean,
    phraseInputNeeded: Boolean,
    phrase: String,
    onPhrase: (String) -> Unit,
    onAsk: () -> Unit,
    onRequest: () -> Unit,
) {
    val colors = Tima.colors
    val words = Tima.words.chat
    Column(modifier = Modifier.fillMaxWidth().background(colors.functional)) {
        if (phraseInputNeeded) {
            Field(
                value = phrase,
                onChange = onPhrase,
                hint = words.phraseWords, phrase = true,
                modifier = Modifier.padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2),
            )
        }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.functional)
            .padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2),
        horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Caption(
            words.storyUnavailable,
            fontSize = TimaType.sz5,
            modifier = Modifier.weight(1f),
        )
        Button(label = if (expect) words.asking else words.askKey, onClick = {
            if (!phraseInputNeeded) onAsk() else if (phrase.isNotBlank()) onRequest()
        })
    }
    }
}

/**
 * Буквы аватара собеседника: первые буквы одного-двух слов имени.
 *
 * `?` — когда имени нет вовсе: в шапке в этом случае стоит «Без имени», и пустой аватар
 * выглядел бы дырой, а не отсутствием имени.
 */
private fun peerLetters(name: String): String =
    name.trim().split(" ").filter { it.isNotBlank() }.take(2)
        .joinToString("") { it.first().uppercase() }
        .ifEmpty { "?" }

/** Правая кнопка мыши (ПК): нажатие её не доходит до `clickable`, ловится отдельно. */
private fun Modifier.secondaryClick(onClick: () -> Unit): Modifier = pointerInput(onClick) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (event.type == androidx.compose.ui.input.pointer.PointerEventType.Press && event.buttons.isSecondaryPressed) {
                event.changes.forEach { it.consume() }
                onClick()
            }
        }
    }
}
