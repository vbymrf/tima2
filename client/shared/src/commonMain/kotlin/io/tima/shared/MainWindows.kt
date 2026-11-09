package io.tima.shared

import kotlinx.datetime.toLocalDateTime
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import io.tima.core.ui.Name
import io.tima.core.ui.AvatarSize
import io.tima.core.ui.Tertiary
import io.tima.core.ui.Button
import io.tima.core.ui.Secondary
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Composable
import io.tima.core.ui.LocalStripLook
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.height
import io.tima.core.encryption.AccountIdentitiesOverKodium
import io.tima.core.encryption.DeviceKeyFactoryOverKodium
import io.tima.core.encryption.IdentitySignerOverKodium
import io.tima.core.network.TransferQr
import io.tima.core.encryption.TransferProverOverKodium
import io.tima.core.encryption.PersonalChatIdsOverKodium
import io.tima.core.encryption.deviceIdentityFrom
import io.tima.core.database.SqlChatBook
import io.tima.core.database.SqlGroupKeys
import io.tima.core.network.GroupKeyRecoveryOverHttp
import io.tima.core.network.GroupsOverHttp
import io.tima.domain.chat.MessageCircle
import io.tima.domain.chat.MessageDisplay
import io.tima.domain.chat.NarrowMessageLevel
import io.tima.domain.chat.ChatKind
import io.tima.domain.chat.Section
import io.tima.domain.chat.GroupKeyRotator
import io.tima.domain.chat.GroupKind
import io.tima.domain.chat.HealGroupKey
import io.tima.domain.chat.RotateStep
import io.tima.domain.chat.ChatSummary
import io.tima.domain.chat.Contact
import io.tima.domain.chat.ChatFaces
import io.tima.feature.group.InviteCandidate
import io.tima.domain.chat.PersonLook
import io.tima.domain.chat.PersonField
import io.tima.domain.chat.field
import io.tima.domain.chat.line
import io.tima.domain.chat.CallOutcome
import io.tima.domain.chat.CallRecord
import io.tima.domain.chat.ChatPerson
import io.tima.domain.chat.ChatPeople
import io.tima.domain.chat.CreateGroupChat
import io.tima.domain.chat.ManageGroupMembers
import io.tima.domain.chat.RequestGroupKeys
import io.tima.domain.chat.CommunityKinds
import io.tima.feature.group.CommunityScreen
import io.tima.feature.group.CommunityStore
import io.tima.feature.group.SocialStore
import io.tima.feature.group.CatalogTab
import io.tima.feature.group.FriendsTab
import io.tima.feature.group.NewGroupStore
import io.tima.feature.group.Step
import io.tima.feature.group.AccessScreen
import io.tima.feature.group.AccessStore
import io.tima.feature.group.MembersStore
import io.tima.feature.group.NewGroupScreen
import io.tima.feature.group.MemberScreen
import io.tima.core.database.TimaDatabase
import io.tima.core.words.CurrentWords
import io.tima.feature.shell.SettingsItem
import io.tima.core.ui.Stage
import io.tima.core.ui.StageSizes
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import io.tima.domain.account.Session
import io.tima.domain.chat.StartPersonalChat
import io.tima.feature.auth.AuthState
import io.tima.feature.auth.AuthStore
import io.tima.feature.auth.LinkStore
import io.tima.feature.auth.LinkScreen
import io.tima.feature.auth.DevicesState
import io.tima.feature.auth.DevicesStore
import io.tima.core.network.AppVersionResult
import io.tima.feature.auth.DeviceScreen
import io.tima.feature.auth.EntryScreen
import io.tima.feature.chat.ChatStore
import io.tima.feature.chat.FailedMessageSheet
import io.tima.feature.chat.MessageMenuSheet
import io.tima.domain.chat.DeadMessages
import io.tima.feature.chat.ChatsState
import io.tima.feature.chat.ChatsStore
import io.tima.feature.chat.GroupsScreen
import io.tima.feature.chat.ChatMenuSheet
import io.tima.domain.chat.letter
import io.tima.feature.chat.MyColorSheet
import io.tima.core.network.MembersResult
import io.tima.core.network.MemberResult
import io.tima.feature.chat.SectionTab
import io.tima.feature.chat.sectionTabs
import io.tima.domain.chat.common
import io.tima.feature.chat.ALL_SECTION
import io.tima.feature.chat.COMMON_SECTION
import io.tima.feature.chat.BookView
import io.tima.feature.chat.SectionsRow
import io.tima.feature.chat.SectionsScreen
import io.tima.feature.chat.BookState
import io.tima.core.contacts.ContactsAccessWay
import io.tima.core.contacts.askContactsAccess
import io.tima.core.contacts.contactsAccessWay
import io.tima.core.contacts.platformInvite
import io.tima.core.media.decodeImage
import io.tima.core.secrets.Account
import io.tima.core.contacts.platformPhoneBook
import io.tima.core.notify.NotifyAccessWay
import io.tima.core.notify.askAwake
import io.tima.core.notify.askNotifyAccess
import io.tima.feature.shell.PermissionsScreen
import io.tima.feature.shell.LoginStart
import io.tima.core.contacts.contactsAllowed
import io.tima.core.call.openCallSettings
import io.tima.core.call.askCallAccess
import io.tima.core.call.callAccessState
import io.tima.feature.shell.SoundRow
import io.tima.core.media.systemSoundsAvailable
import io.tima.core.media.systemDefaultSoundTitle
import io.tima.core.media.rememberSoundFilePicker
import io.tima.core.media.rememberSystemSoundPicker
import io.tima.core.media.SoundUse
import io.tima.core.media.SoundPick
import io.tima.core.notify.wire
import io.tima.core.notify.soundChoiceOf
import io.tima.core.notify.SoundKeys
import io.tima.core.notify.SoundChoice
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.stateIn
import io.tima.feature.shell.BackgroundTrouble
import io.tima.core.notify.openCallsChannelSettings
import io.tima.core.notify.openFullScreenSettings
import io.tima.core.notify.backgroundFacts
import io.tima.core.notify.BackgroundWatch
import io.tima.core.notify.awakeAllowed
import io.tima.core.notify.notifyAccessWay
import io.tima.feature.shell.NotifyAccess
import io.tima.feature.shell.NotificationsScreen
import io.tima.core.ui.Tab
import io.tima.core.ui.TabButton
import io.tima.domain.chat.BookEntry
import io.tima.domain.chat.PageStep
import io.tima.domain.chat.AddContact
import io.tima.domain.chat.RemoveContact
import io.tima.domain.chat.SyncBook
import io.tima.feature.chat.CallsScreen
import io.tima.feature.chat.CallsState
import io.tima.feature.chat.CallsStore
import io.tima.feature.chat.BookStore
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import io.tima.core.ui.ControlRow
import io.tima.core.ui.Field
import io.tima.core.ui.IconButton
import io.tima.core.ui.TimaSpacing
import io.tima.feature.chat.GuestPageScreen
import io.tima.feature.chat.PERSON_FIRST_LINE
import io.tima.feature.chat.asLine
import io.tima.core.call.CallEngine
import io.tima.core.call.CallStage
import io.tima.feature.call.BenchLine
import io.tima.feature.call.BenchScreen
import io.tima.feature.call.CallBenchSwitch
import io.tima.core.ui.LayoutLocal
import io.tima.feature.call.CallScreen
import io.tima.feature.call.CallVideo
import io.tima.feature.chat.BookViewSheet
import io.tima.feature.chat.matches
import io.tima.feature.chat.orderedSections
import io.tima.feature.chat.InviteScreen
import io.tima.feature.chat.NewContactScreen
import io.tima.feature.chat.NewContactStore
import io.tima.domain.account.CreateVirtual
import io.tima.domain.account.TransferVirtual
import io.tima.domain.account.TransferAcceptStep
import io.tima.domain.account.VirtualStep
import io.tima.feature.auth.NewVirtualScreen
import io.tima.feature.auth.VirtualsState
import io.tima.feature.auth.VirtualsStore
import io.tima.feature.auth.VirtualsScreen
import io.tima.feature.auth.TransferStore
import io.tima.feature.auth.TransferScreen
import io.tima.feature.auth.NewVirtualStep
import io.tima.feature.auth.NewVirtualStore
import io.tima.feature.chat.ProfileScreen
import io.tima.feature.chat.SelfPageScreen
import io.tima.feature.chat.ProfileState
import io.tima.feature.chat.ProfileStore
import io.tima.feature.chat.BookScreen
import io.tima.feature.shell.beganLabel
import io.tima.feature.shell.AccountLeavingSheet
import io.tima.feature.shell.CloseQuestionSheet
import io.tima.feature.shell.CALL_FILTERS
import io.tima.feature.shell.Window
import io.tima.feature.shell.MediaWindow
import io.tima.feature.shell.ActivityWindow
import io.tima.feature.shell.SocialWindow
import io.tima.feature.shell.PageWindow
import io.tima.feature.shell.InSide
import io.tima.feature.shell.ActiveCall
import io.tima.feature.shell.LocalActiveCall
import io.tima.feature.shell.windowSwipe
import io.tima.feature.shell.WindowHourglass
import io.tima.feature.shell.WindowSwitchClock
import io.tima.feature.shell.WindowFrame
import io.tima.feature.shell.Rail
import io.tima.feature.shell.TabStub
import io.tima.feature.shell.FilterRow
import io.tima.feature.shell.WindowSwitchingScreen
import io.tima.core.ui.Appearance
import io.tima.core.ui.merged
import io.tima.core.ui.TimaTheme
import androidx.compose.foundation.isSystemInDarkTheme
import io.tima.feature.shell.AppearanceScreen
import io.tima.core.words.Language
import io.tima.core.ui.Tima
import io.tima.feature.shell.WindowTab
import io.tima.core.ui.words
import io.tima.core.words.RussianWords
import io.tima.feature.shell.LanguageScreen
import io.tima.feature.shell.SettingsScreen
import io.tima.feature.shell.TextLookScreen
import io.tima.core.diag.Diary
import io.tima.core.diag.DiaryPolicy
import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode
import io.tima.core.network.ProblemPost
import io.tima.core.network.ProblemSendResult
import io.tima.feature.shell.Origin
import io.tima.feature.shell.ProblemFacts
import io.tima.feature.shell.ProblemScreen
import io.tima.feature.shell.ProblemStore
import io.tima.feature.shell.Snapshot
import io.tima.feature.shell.SendOutcome
import io.tima.feature.shell.Began
import io.tima.feature.shell.CallLogLimits
import io.tima.feature.shell.DiaryLimits
import io.tima.feature.shell.KeepFor
import io.tima.feature.shell.KeepUnit
import io.tima.feature.shell.StorageScreen
import io.tima.feature.shell.UpdateGate
import io.tima.feature.shell.UpdateMemory
import io.tima.core.ui.ButtonKind
import io.tima.feature.shell.Notice
import io.tima.feature.shell.NoticeAction
import io.tima.feature.shell.NoticeQueueScreen
import io.tima.feature.shell.NoticeEntry
import io.tima.feature.shell.NoticeLine
import io.tima.feature.shell.UpdateNews
import io.tima.feature.shell.notice
import io.tima.feature.shell.UpdateInstaller
import io.tima.feature.shell.UpdateState
import io.tima.feature.shell.UpdateOffer
import io.tima.feature.shell.UpdateSection
import io.tima.feature.shell.UpdateStore
import io.tima.feature.chat.NewChatStore
import io.tima.domain.chat.CarryToPage
import io.tima.domain.chat.CreateChannel
import io.tima.domain.chat.CreateCommunity
import io.tima.domain.chat.ChatLine
import io.tima.domain.chat.CommentEntry
import io.tima.domain.chat.WriteComment
import io.tima.feature.chat.CommentsStore
import io.tima.feature.chat.LocaleStore
import io.tima.feature.chat.PageStore
import io.tima.feature.chat.NewChatScreen
import io.tima.feature.chat.CommentsScreen
import io.tima.feature.chat.CommentsState
import io.tima.feature.chat.PageScreen
import io.tima.feature.chat.ChatScreen
import io.tima.feature.chat.ChatsScreen
import io.tima.core.ui.theme
import io.tima.feature.shell.item
import io.tima.feature.shell.label

/*
 * Окна главного экрана — окно «Телефон», счётчики окон, строка поиска, часы смены окна, пара меток экрана.
 * Вынесены из Root.kt (ПЛАН-(РФ) РФ2) без изменения поведения: Root.kt держит корень, здесь — каркас окон.
 */

/**
 * Место для метки `screen:` (ПЛАН-(МО)-МЕТОК-ОКОН): подэкран — кодом журнала [whereCode]; нет его —
 * окно с вкладкой (`Phone.Chats`); у окна звонка — стадия, в том же порядке, в каком `CallScreen`
 * выбирает кнопки: конец, входящий, набор, разговор.
 */
internal fun placeCode(window: Window, where: Where, call: CallHost, tabOf: (Window) -> WindowTab?): String = when {
    where != Where.Nothing -> whereCode(where)
    window == Window.Call -> when {
        call.state.stage == CallStage.Ended -> PlaceCode.CALL_ENDED
        call.incoming && call.state.stage != CallStage.Connected -> PlaceCode.CALL_INCOMING
        call.state.stage != CallStage.Connected -> PlaceCode.CALL_DIALING
        else -> PlaceCode.CALL_TALK
    }
    else -> window.name + (tabOf(window)?.let { "." + it.name } ?: "")
}

internal fun windowCounters(counts: io.tima.domain.chat.NoticeCounts): Map<Window, Int> {
    // Счётчик идёт ЗА перепиской, а не остаётся там, где она лежала. Группы уехали на
    // вкладку окна 5 — значит и новое в них считается окну 5. Иначе человек видит
    // янтарную точку на «Телефоне», открывает его и не находит там ничего: счётчик
    // указывает в пустоту, и это хуже отсутствующего счётчика.
    //
    // Число окна — **сумма чисел его вкладок** (ПЛАН-(ЖУ)-ЖУРНАЛА-УВЕДОМЛЕНИЙ.md, ЖУ2, заказчик
    // 2026-09-30): «Телефон» = «Чаты» + «Звонки». До того — сумма непрочитанных сообщений,
    // без пропущенных звонков.
    val phone = counts.tab(io.tima.domain.chat.NoticeTab.Chats) + counts.tab(io.tima.domain.chat.NoticeTab.Calls)
    val page = counts.tab(io.tima.domain.chat.NoticeTab.Groups)
    // Каналы — в «Социуме»: там их каталог и лента (ПЛАН-(ОУ)).
    val social = counts.tab(io.tima.domain.chat.NoticeTab.Channels)
    return buildMap {
        if (phone > 0) put(Window.Phone, phone)
        if (page > 0) put(Window.Page, page)
        if (social > 0) put(Window.Social, social)
    }
}

/**
 * Окно «Телефон» — три вкладки макета.
 *
 * «Чаты» и «Контакты» построены, «Звонки» ждут К7 и говорят об этом словами: кнопка,
 * которая ничего не делает, обещает больше, чем есть.
 *
 * Вкладка запоминается вызывающим, а не этим экраном: «единая сессия» из `§1` требует,
 * чтобы окно возвращалось туда, где его оставили, — в том числе после захода в подокно.
 * Фильтр журнала живёт здесь: он принадлежит одной вкладке и вместе с ней и уходит.
 */
@Composable
internal fun PhoneWindow(
    tab: WindowTab,
    onTab: (WindowTab) -> Unit,
    /** Пометка переписки — «удалится через N ч» у временной группы звонка (решение 11). */
    tagOf: (ChatSummary) -> String? = { null },
    list: ChatsState,
    book: BookState,
    /** Человек за строкой книги — имя из книги плюс карточка справочника. */
    personOf: (BookEntry) -> ChatPerson = { ChatPerson(name = it.name, phone = it.phone) },
    /** Собеседник личной переписки — для строки списка чатов; `null` у групп. */
    personOfChat: (ChatSummary) -> ChatPerson? = { null },
    /** Аватар собеседника личной переписки. */
    faceOfChat: (ChatSummary) -> String? = { null },
    /** Группа звонка: создатель и его аватар. */
    callGroupOf: (ChatSummary) -> io.tima.feature.chat.CallGroupLook? = { null },
    faceOf: (BookEntry) -> String? = { null },
    onSearchInBook: (String) -> Unit,
    onOpen: (ChatSummary) -> Unit,
    onOpenPerson: (BookEntry) -> Unit,
    onChooseSection: (String) -> Unit = {},
    /** Управление разделами — «Добавить» в плитке ярлычков. */
    onSections: (() -> Unit)? = null,
    /**
     * Р4 — разделы у личных переписок. Раздел переписки — это раздел её собеседника в
     * книге: своего поля у переписки нет и заводить его незачем, человек один.
     */
    sectionOfChat: (ChatSummary) -> String = { "" },
    /** Янтарная цифра раздела: сколько его людей написали новое. По ключу полосы. */
    newInSection: (String) -> Int = { 0 },
    chatSections: List<SectionTab> = emptyList(),
    chatSection: String = "",
    onChooseChatSection: (String) -> Unit = {},
    onNew: () -> Unit,
    onSettings: () -> Unit,
    onSwitchWindows: () -> Unit,
    onNeighbourWindow: (InSide) -> Unit,
    /** «Вид» — последняя вкладка-кнопка: открывает подокно настроек списка. */
    onView: () -> Unit,
    onToggleSection: (String) -> Unit,
    onAddContact: () -> Unit,
    /** Нажали на аватар в книге — личная страница человека. */
    onFacePerson: (BookEntry) -> Unit = {},
    /** Аватар в «Чатах» и «Звонках» — страница человека, строка — переписка (заказчик 2026-10-06). */
    onFaceUser: (String) -> Unit = {},
    /** Строка «Звонков» — переписка с этим человеком (заказчик 2026-10-06). */
    onOpenUser: ((String) -> Unit)? = null,
    /** Позвонить из строки книги — ЗВ13. `null` — звонить нечем (ПК). */
    onCallPerson: ((BookEntry) -> Unit)? = null,
    /** Видеозвонок из строки книги — Ж3. `null` — звонить нечем. */
    onVideoCallPerson: ((BookEntry) -> Unit)? = null,
    /** «Позвонить» во вкладке «Звонки» — поиск человека (заказчик 2026-10-24, 5а#). `null` — звонить нечем. */
    onNewCall: (() -> Unit)? = null,
    onInvite: (BookEntry) -> Unit,
    /** Кто я: строка журнала у двоих читается по-разному, и без этого её не прочесть. */
    myUserId: String = "",
    /** Журнал звонков — вкладка «Звонки» (Ж2). */
    callsState: CallsState = CallsState(),
    /** Человек за строкой журнала: сервер знает только `user_id`, имя живёт в книге. */
    personOfCall: (CallRecord) -> ChatPerson = { ChatPerson() },
    /** «Доставлено» и «прочитано» своего последнего в личной переписке (ПЛАН-(ОП)). */
    receiptOfChat: (ChatSummary) -> io.tima.feature.chat.ChatReceipt? = { null },
    /** Собеседник печатает — «печатает…» вместо превью (ПЛАН-(ОП)). */
    typingOfChat: (ChatSummary) -> Boolean = { false },
    /** Отключённые уведомления — ключи `вид:сущность` (ПЛАН-(ОУ)). */
    mutedKeys: Set<String> = emptySet(),
    /** Строка группового звонка; `null` — звонок личный (заказчик 2026-10-08). */
    groupCallOf: (CallRecord) -> io.tima.feature.chat.GroupCallLine? = { null },
    /** Нажали на строку группового звонка — его чат или «Чат удалён». */
    onOpenGroupCall: (CallRecord) -> Unit = {},
    /** Короткое слово поверх журнала; `null` — нет. */
    callsNote: String? = null,
    faceOfCall: (CallRecord) -> String? = { null },
    /** Перезвонить из журнала — **тем же видом**, каким звонили тогда (Ж6). */
    onCallAgain: ((CallRecord) -> Unit)? = null,
    /** Открыли вкладку «Звонки»: сходить за свежим журналом. */
    onOpenedCalls: () -> Unit = {},
    /** Вкладку «Звонки» увидели ([rememberSeen]) — погасить счётчик и уведомления. */
    onCallsSeen: () -> Unit = {},
    /** Числа вкладок и строк — из журнала уведомлений (ЖУ2). */
    noticeCounts: io.tima.domain.chat.NoticeCounts = io.tima.domain.chat.NoticeCounts.NONE,
    /** Открыли вкладку: прочитать телефонную книгу и сверить. */
    onOpenedContacts: () -> Unit,
    /**
     * «Разрешить»: системный диалог, и после согласия — чтение.
     *
     * `null` — спрашивать нечего: телефонной книги у системы нет (ПК). Кнопки тогда нет
     * совсем, а не «неактивная».
     */
    onAllowContacts: (() -> Unit)?,
    /** Нажатие уведёт в настройки, а не поднимет диалог: система больше не спросит (Л1). */
    allowInSettings: Boolean = false,
    /** «Обновить» — сверка с телефонной книгой по требованию (Л2). */
    onRefreshContacts: (() -> Unit)? = null,
    /** «Отказаться» от чтения книги телефона (2026-10-08). `null` — кнопки нет. */
    onRefuseContacts: (() -> Unit)? = null,
    /** Кто человек строки журнала: в книге, незнакомый, заблокирован (2026-10-08). */
    kindOfCall: (CallRecord) -> io.tima.feature.chat.PersonKind = { io.tima.feature.chat.PersonKind.Known },
    /** Кто собеседник личной переписки: в книге, незнакомый, заблокирован (2026-10-08). */
    kindOfChat: (ChatSummary) -> io.tima.feature.chat.PersonKind = { io.tima.feature.chat.PersonKind.Known },
) {
    var calls by remember { mutableStateOf(CALL_FILTERS.first()) }
    val bookWords = Tima.words.book
    // ── ПОИСК ПО ОКНУ ───────────────────────────────────────────────────────
    //
    // Решение заказчика 2026-09-19: «для поиска у нас есть кнопка — щас она не
    // задействована; поиск будет появляться тогда, когда нажмут на кнопку поиск».
    //
    // Состояние окна, а не вкладки: набранное переживает переход «Чаты ↔ Контакты», и
    // это осознанно — ищут человека, а на какой он вкладке, вспоминают уже по дороге.
    // Закрытие крестиком чистит запрос: оставить его невидимым значило бы оставить
    // список сужённым без единого признака, почему.
    var searching by remember { mutableStateOf(false) }
    var request by remember { mutableStateOf("") }
    // Строка поиска — подокно в паре меток для сценариев (ПЛАН-(МО)); на «Звонках» её не видно.
    InnerPopup(PopupCode.SEARCH, searching && tab != WindowTab.Calls)
    fun searchTo(text: String) {
        request = text
        // Книга фильтрует у себя: у неё поиск идёт по имени, нику и номеру сразу.
        onSearchInBook(text)
    }
    // Числа уведомлений у разделов — только у «Чатов» (заказчик 2026-10-08). Модель разделов
    // у «Чатов» и «Контактов» одна, а уведомления относятся к перепискам: переключение на
    // «Контакты» их убирает.
    val sectionNotices = tab == WindowTab.Chats
    val sectionNews: (String) -> Int = if (sectionNotices) newInSection else { _ -> 0 }
    WindowFrame(
        window = Window.Phone,
        tabs = listOf(WindowTab.Chats, WindowTab.Contacts, WindowTab.Calls),
        selected = tab,
        onTab = onTab,
        // Счётчик пропущенных — только у «Звонков» и только мне непросмотренных (Ж7).
        //
        // **Отдельный от непрочитанных сообщений, а не общий** (решение заказчика
        // 2026-09-23): «три непрочитанных» и «три пропущенных звонка» — разные срочности
        // и разные поступки. Сложенные в одно число, они означают «что-то есть», то есть
        // не означают ничего.
        //
        // С 2026-09-30 — из журнала уведомлений (ЖУ2): у вкладки — сколько сущностей на ней с
        // новым. «Чаты» — сколько переписок, «Звонки» — сколько звонивших.
        countOf = { which ->
            when (which) {
                WindowTab.Chats -> noticeCounts.tab(io.tima.domain.chat.NoticeTab.Chats)
                WindowTab.Calls -> noticeCounts.tab(io.tima.domain.chat.NoticeTab.Calls)
                else -> 0
            }
        },
        onSwitchWindows = onSwitchWindows,
        // У «Звонков» кнопки поиска нет, и это уже не «журнала нет» (прежний довод,
        // К7): журнал есть. Искать в нём нечем — строка журнала не содержит текста
        // вовсе, а имя приезжает из книги в момент показа. Поиск по журналу — это поиск
        // по книге, и он живёт во вкладке «Контакты».
        onSearch = if (tab == WindowTab.Calls) {
            null
        } else {
            {
                searching = !searching
                if (!searching) searchTo("")
            }
        },
        onSettings = onSettings,
        onNeighbourWindow = onNeighbourWindow,
        // «Вид» — последней вкладкой на ВСЕХ трёх вкладках окна (заказчик 2026-09-19).
        // До этого она была только у «Контактов», хотя настройки у вкладок общие: раздел
        // переписки — это раздел собеседника (Р4), и «как называть человека» одинаково
        // решает и книгу, и список переписок, и журнал звонков. У «Звонков» своих разделов
        // нет — там подокно показывается без них.
        tabsTrailing = {
            // Кнопка, а не вкладка: она открывает подокно, а не переключает
            // показанное. В макете это `.таб-вид` — залитая таблетка со значком.
            TabButton(label = Tima.words.tabs.label(WindowTab.View), glyph = "▤", onClick = onView)
        },
        // Второй ряд: у журнала фильтры, у «Контактов» в виде «меню» — разделы.
        secondRow = when {
            tab == WindowTab.Calls -> { { FilterRow(CALL_FILTERS, calls, { calls = it }) } }
            // Полоса разделов — исполнения В и Г из `разделы.md`. Полоса — ФИЛЬТР: до
            // 2026-09-18 выбор на ней ни на что не влиял, потому что `onChooseSection`
            // сюда не передавался вовсе, и список показывал всех при любом чипе.
            tab == WindowTab.Contacts && !book.view.folders && book.tabs(bookWords).size > 1 ->
                { { SectionsRow(book.tabs(bookWords), book.chosen, book.view.icons, onChooseSection, newIn = sectionNews) } }
            // Р4: те же разделы у личных переписок — раздел переписки это раздел собеседника.
            tab == WindowTab.Chats && chatSections.size > 1 ->
                { { SectionsRow(chatSections, chatSection, book.view.icons, onChooseChatSection, newIn = newInSection) } }
            else -> null
        },
        // Строка поиска — только пока она открыта, и только там, где есть что искать.
        searchRow = if (searching && tab != WindowTab.Calls) {
            {
                SearchRow(
                    value = request,
                    onChange = { searchTo(it) },
                    hint = if (tab == WindowTab.Chats) bookWords.searchChats else bookWords.search,
                    onClose = {
                        searching = false
                        searchTo("")
                    },
                )
            }
        } else {
            null
        },
    ) {
        // Отобранное и «Вид» — запомнены, а не собираются заново на каждую перерисовку окна:
        // новый объект на каждой перерисовке не давал пропустить ни экран, ни его строки
        // (Redmi A3, 2026-10-08). Пересчитываются, только когда сменилось то, из чего собраны.
        val look = remember(book.view) { book.view.look() }
        when (tab) {
            WindowTab.Chats -> ChatsScreen(
                // Р4: переписки сужаются выбранным разделом — тем же, что у контактов;
                // поиск сужает дальше, по имени в шапке и первой строке последнего.
                state = remember(list, chatSection, request, sectionOfChat) {
                    list.copy(
                        chats = list.chats.filter { chat ->
                            (
                                chatSection.isEmpty() ||
                                    sectionOfChat(chat) == (if (chatSection == COMMON_SECTION) "" else chatSection)
                                ) && chat.matches(request)
                        },
                    )
                },
                onOpen = onOpen,
                onNew = onNew,
                onSettings = onSettings,
                // Тот же человек и тот же «Вид», что во вкладке «Контакты».
                personOf = personOfChat,
                faceOf = faceOfChat,
                onFace = { chat -> chat.peerId?.let(onFaceUser) },
                look = look,
                countOf = { chat -> noticeCounts.chat(chat.chatId, chat.peerId) },
                tagOf = tagOf,
                callGroupOf = callGroupOf,
                receiptOf = receiptOfChat,
                typingOf = typingOfChat,
                mutedOf = { chat -> "chat:${chat.chatId}" in mutedKeys },
                kindOf = kindOfChat,
            )

            WindowTab.Contacts -> {
                // Телефонная книга читается при открытии вкладки, а не при запуске:
                // разрешение, спрошенное на первом экране, объяснить нечем — человек
                // ещё не видел ни одного контакта.
                LaunchedEffect(Unit) { onOpenedContacts() }
                BookScreen(
                    state = book,
                    onOpen = onOpenPerson,
                    personOf = personOf,
                    faceOf = faceOf,
                    onToggleSection = onToggleSection,
                    onChooseSection = onChooseSection,
                    onSections = onSections,
                    newIn = sectionNews,
                    onAdd = onAddContact,
                    onFace = onFacePerson,
                    onCall = onCallPerson,
                    onVideoCall = onVideoCallPerson,
                    onInvite = onInvite,
                    onAllow = onAllowContacts,
                    allowInSettings = allowInSettings,
                    onRefuse = onRefuseContacts,
                    onRefresh = onRefreshContacts,
                )
            }

            WindowTab.Calls -> {
                // Журнал читается при открытии вкладки, а не при запуске: смотреть в него
                // приходят редко, а сходить за ним стоит запроса.
                // Кто оставил уведомление — снимок до того, как вход его снимет (заказчик
                // 2026-10-08): по снимку аватар последнего пропущенного от этого человека в
                // оранжевом контуре. Снимок живёт, пока открыта вкладка: переключился — контура нет.
                val noticed = remember { noticeCounts.entities(io.tima.domain.chat.NoticeTab.Calls) }
                val flaggedCalls = remember(callsState.records, noticed) {
                    noticed.mapNotNull { who ->
                        callsState.records.firstOrNull { record ->
                            record.initiatorId == who && !record.outgoing(myUserId) && record.outcome(myUserId) == CallOutcome.Missed
                        }?.callId
                    }.toSet()
                }
                LaunchedEffect(Unit) {
                    if (noticed.isNotEmpty()) {
                        Journal.note(
                            LogCode.NOTICE, "«Звонки»: контур у звонков с уведомлением", "сколько" to flaggedCalls.size,
                            "звонки" to flaggedCalls.joinToString(",") { it.take(8) }, "от кого" to noticed.joinToString(",") { it.take(8) },
                        )
                    }
                    onOpenedCalls()
                }
                val callsSeen = rememberSeen("calls")
                LaunchedEffect(callsSeen) { if (callsSeen) onCallsSeen() }
                CallsScreen(
                    // ── ФИЛЬТРЫ ВТОРОГО РЯДА ────────────────────────────────
                    //
                    // «Из книги» и «Незнакомые» отбираются **по книге, а не по
                    // серверу**: знает ли телефон этого человека — вопрос к телефону.
                    // «Незнакомые» у нас поэтому означает «звонил тот, кого нет в
                    // книге», а не «звонок с чужого номера»: чужих номеров в нашем
                    // журнале не бывает вовсе — звонить может только аккаунт.
                    // Групповой звонок — не про одного человека: он в «Всех» и «Пропущенных».
                    // Кто из книги — одним набором на смену книги, а не проходом по книге на
                    // каждую запись журнала при каждой перерисовке.
                    records = remember(callsState.records, calls, book.all, myUserId) {
                        val inBook = book.all.mapNotNullTo(HashSet()) { it.userId }
                        callsState.records.filter { record ->
                            val known = record.other(myUserId) in inBook
                            when (calls) {
                                WindowTab.FromBook -> known && !record.group
                                WindowTab.Unknown -> !known && !record.group
                                WindowTab.Missed -> record.outcome(myUserId) == CallOutcome.Missed
                                else -> true
                            }
                        }
                    },
                    me = myUserId,
                    personOf = personOfCall,
                    faceOf = faceOfCall,
                    look = look,
                    onCallAgain = onCallAgain,
                    onNewCall = onNewCall,
                    onOpen = { record ->
                        if (record.group) onOpenGroupCall(record) else onOpenUser?.invoke(record.other(myUserId))
                    },
                    // Аватар группового — страница создателя, как в «Чатах».
                    onFace = { record -> onFaceUser(if (record.group) record.initiatorId else record.other(myUserId)) },
                    offline = callsState.offline,
                    groupOf = groupCallOf,
                    note = callsNote,
                    flagged = { it.callId in flaggedCalls },
                    kindOf = kindOfCall,
                )
            }

            // Остаётся «Вид» — он не вкладка с содержимым, а кнопка, открывающая
            // подокно настроек списка. Сюда попасть можно только мимо неё.
            else -> TabStub(
                willWhat = "Здесь ничего не показывается",
                thanHolds = "«Вид» — кнопка, а не вкладка: она открывает настройки списка.",
            )
        }
    }
}

/**
 * Строка поиска окна: однострочное поле и крестик.
 *
 * Однострочное — решение заказчика 2026-09-19: «сделай его однострочным, щас там
 * появляется текст на две строки». Вторая строка сдвигала вниз весь список ровно в тот
 * момент, когда человек набирает и смотрит на результат.
 *
 * Крестик, а не только повторное нажатие «🔍» в шапке: закрыть ищут там же, где ищут
 * набранное, — рядом с полем, а не глазами по шапке.
 */
@Composable
internal fun SearchRow(
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2),
        horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Field(
            value = value,
            onChange = onChange,
            hint = hint,
            lineOne = true,
            // Толщина — как у крестика рядом (заказчик 2026-09-19).
            narrow = true,
            // Кнопку «🔍» уже нажали — спрашивать второй раз, ткнув в поле, незачем.
            autoFocus = true,
            modifier = Modifier.weight(1f),
        )
        ControlRow { IconButton(glyph = "✕", onClick = onClose, modifier = Modifier.testTag(SEARCH_CLOSE_TAG)) }
    }
}

// ── Полоса «звонки не дойдут» (ВЗ0г) ────────────────────────────────────────

/** Песочные часы, пока строится окно (Э4): состояние читает только этот слой, не корень. */
@Composable
internal fun HourglassLayer(awaited: androidx.compose.runtime.State<Window?>) {
    if (awaited.value != null) WindowHourglass()
}
