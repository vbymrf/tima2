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
import kotlinx.coroutines.flow.debounce
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
 * Переписка — сборка её store со всеми портами и экран; новая группа и состав группы; помощники фото и файлов.
 * Вынесены из Root.kt (ПЛАН-(РФ) РФ3) без изменения поведения.
 */

@Composable
internal fun Chat(
    environment: Environment,
    network: ChatPorts,
    /** ПК: стрелки ленты редактора и перетаскивание файлов (Решения §10.2). */
    desktop: Boolean = false,
    /** Файлы почтальона: своя папка, повторная просьба (Решения §6). `null` — без папки. */
    postman: PostmanFiles? = null,
    /** «Доставлено», «прочитано», «печатает», «в сети» — личной переписке (ПЛАН-(ОП)). */
    liveStates: LiveStates? = null,
    /** Забрать новое открытой группы (ПЛАН-(ОУ) ОУ6) → номер позднейшего на сервере. */
    onPullGroupNew: suspend (String) -> Long = { 0L },
    /** Это устройство — им подписывается просьба о ключе группы (Р42). */
    myDeviceId: String,
    chatId: String,
    name: String?,
    scope: kotlinx.coroutines.CoroutineScope,
    onBack: () -> Unit,
    onMembers: () -> Unit,
    /** Отчёт о проблеме из подокна неотправленного — с причиной и тем, ждёт оно или отказано. */
    onReportFailed: ((reason: String?, waiting: Boolean) -> Unit)? = null,
    /** Унести реплику к себе на страницу: `(messageId, круг записи)`. */
    onCarry: (Long, Int) -> Unit = { _, _ -> },
    /** Лечение группы без ключа при открытии; `null` — лечить нечем. */
    heal: HealGroupKey? = null,
    /** Меню «•••» (Р5): разделы набора сообществ и перенос. `null` — меню нет. */
    shelves: List<Section> = emptyList(),
    onMoveToShelf: ((chatId: String, sectionId: String) -> Unit)? = null,
    currentShelf: String = "",
    /**
     * Уведомления — чтобы пока переписка на экране, о ней не уведомляли (У10).
     *
     * `null` — уведомлять нечем: так собирают экран в проверках.
     */
    notices: Notices? = null,
    /** Люди за идентификаторами авторов; `null` — только идентификаторы. */
    people: People? = null,
    /** Как называть авторов — «Вид» набора сообществ. */
    authorLook: PersonLook = PersonLook.DEFAULT,
    /** Владелец группы — его полоса салатовая. */
    ownerId: String? = null,
    /** Выбранные участниками цвета полос: человек → номер оттенка (сервер 0053). */
    hues: Map<String, Int> = emptyMap(),
    /** Вид группы — от него круги сообщений; `null` — все, как было. */
    kind: GroupKind? = null,
    /** Сообщество группы: подборка сообщений — его (Решения §11.2); `null` — своя у группы. */
    community: String? = null,
    myUserId: String = "",
    /**
     * Разобрать недоступное заново — когда сервер сказал, что ключ сообщения у устройства уже
     * есть (2026-10-06). `null` — нечем: так собирают экран в проверках.
     */
    reopenUnreadable: (suspend () -> Int)? = null,
    /** «Открыть через фразу» (план КК, КК4): переписка, слова, группа ли. `null` — нечем (проверки). */
    openByPhrase: (suspend (String, List<String>, Boolean) -> io.tima.domain.chat.PhraseOpen)? = null,
    /** ПК: «Передать с телефона» в окне недоступного (КК4). */
    fromPhoneMay: Boolean = false,
    /** Где аватар автора в группе — «Вид» в «Социуме»; в личной переписке аватара нет. */
    avatarLook: io.tima.core.ui.AvatarLook = io.tima.core.ui.AvatarLook.Free,
    /** Нажатие на аватар автора — его страница. */
    onAuthor: ((String) -> Unit)? = null,
    /** Как меня зовут — для образца «мой пузырь глазами остальных». */
    myName: String = "",
    /** Открыли группу: обновить владельца и цвета участников. */
    onOpened: (String) -> Unit = {},
    /** Поставить или сбросить (`null`) мой цвет; `done(беда)` — итог, `null` — вышло. */
    onMyColor: ((chatId: String, hue: Int?, done: (String?) -> Unit) -> Unit)? = null,
    /** Нажали на аватар с именем в шапке — личная страница собеседника; `null` — группа. */
    onPerson: (() -> Unit)? = null,
    /** Картинка аватара собеседника в шапке. */
    peerFace: String? = null,
    /** Позвонить собеседнику; `null` — у группы или там, где звонить нечем. */
    onCall: (() -> Unit)? = null,
    /** Видеозвонок — пункт «•••» личной переписки (ЗВ6). */
    onVideoCall: (() -> Unit)? = null,
    /** Разделы книги для «•••» личной переписки: раздел переписки — раздел собеседника. */
    bookSections: List<Section> = emptyList(),
    currentBookSection: String = "",
    onMoveToBookSection: ((chatId: String, sectionId: String) -> Unit)? = null,
    /** Групповой звонок — пункт «⋯» (ГЗ7); `null` — нечем звонить. */
    onGroupCall: (() -> Unit)? = null,
    /** Р54: идёт перерегистрация, это прежняя личность — ответ на своё сообщение придёт не сюда. */
    reregWarn: Boolean = false,
    /** Идущий звонок группы — полоса «Идёт звонок»; `null` — звонка нет. */
    groupCall: io.tima.core.call.GroupCallLive? = null,
    /** Следить за звонком группы, пока она открыта. */
    onWatchGroupCall: (suspend () -> Unit)? = null,
    onJoinGroupCall: (String) -> Unit = {},
    /** Я уже в звонке этой группы — «Присоединиться» не нужно. */
    inCallHere: Boolean = false,
    /** Я автор чата звонка: «Повторить звонок», пока звонок не идёт. `null` — не автор. */
    onRepeatGroupCall: (() -> Unit)? = null,
    /** Временная группа звонка — когда удалится, мс (решение 11). */
    ttlUntilMs: Long? = null,
    /** Группа звонка: имя создателя и его аватар (заказчик 2026-10-02). */
    callCreator: String? = null,
    callCreatorFace: String? = null,
    inviteOf: (ChatLine) -> io.tima.feature.chat.CallInvite? = { null },
    onInvite: (ChatLine) -> Unit = {},
    /** Закреплённое (ПЛАН-(ЗО) ЗО3): общий снимок устройства; `null` — закреплять нечем. */
    pins: io.tima.domain.chat.ChatPins? = null,
    /** Могу ли закреплять: личная — да, группа — модератор и выше. */
    pinMay: Boolean = false,
    /** Текст шапки группы — окно «Описание» (ЗО4); `null` — у личной. */
    onShowcase: (() -> Unit)? = null,
    /** Владельцу группы — поставить суперсообщение по номеру (ЗО5). */
    setSuper: (suspend (Long) -> Boolean)? = null,
    /** Черновик суперсообщения из «Описания»: открыть редактор с ним; `onSuperTaken` — взят. */
    superDraft: io.tima.domain.chat.ComposedDoc? = null,
    onSuperTaken: () -> Unit = {},
    /** Сообщение по номеру — вставленное, подкладка, переход (Решения §11.3–§11.4). */
    finder: io.tima.domain.chat.MessageFinder? = null,
    /** Буфер переходов переписки (ПЛАН-(БП)); `null` — только в памяти. */
    jumpPlace: io.tima.feature.chat.JumpPlace? = null,
    /** Вид группы из базы устройства; `null` — личная или ещё не сверялась. */
    groupKind: GroupKind? = null,
    /** Открыть другую переписку — из сообщения, показанного одним. */
    onOpenChat: ((String) -> Unit)? = null,
    /** Куда переслать фото из просмотра: переписки, кроме этой — номер и название. */
    forwardTargets: List<Pair<String, String>> = emptyList(),
    /** Переслать фото: открыть ту переписку, фото уйдёт там. */
    onForward: ((chatId: String, title: String, items: List<io.tima.feature.chat.Forwarded>) -> Unit)? = null,
    /** Пересланное сюда из другой переписки — отправить; `onIncomingTaken` — ушло. */
    incoming: List<io.tima.feature.chat.Forwarded>? = null,
    onIncomingTaken: () -> Unit = {},
    /** Проигрыватель голосовых — один на приложение (ПЛАН-(ВМ) §2.4). */
    voiceHub: io.tima.feature.chat.VoiceHub? = null,
    /** Имя микрофона для голосовых (ПК: выбранный для звонков); `null` — по умолчанию. */
    voiceMic: () -> String? = { null },
) {
    // Групповая ли переписка — решает столбец `kind`, а не догадка по идентификатору.
    var chatMenu by remember { mutableStateOf(false) }
    // От этого зависит трое: показывать ли автора у реплик, спрашивать ли имена и есть ли
    // вход в состав.
    val group = remember(chatId) {
        environment.chatFacts.kindOf(chatId) == ChatKind.Group
    }
    // Открытая группа — «зашли, забрали» (ПЛАН-(ОУ) ОУ6): тело её сообщений к телефону само не
    // приходит. Открыли — забрали новое и поставили отметку; пока открыта, новое по вершине —
    // так же. Без сети — показываем, что уже есть.
    val groupTop = liveStates?.tops?.collectAsState()?.value?.get("group:$chatId")?.topId
    // Вид группы — из списка групп; список мог не доехать, тогда признак — сама вершина.
    val publicGroup = group && liveStates != null && (kind == GroupKind.Public || groupTop != null)
    // Увидел ли человек переписку (заказчик 2026-10-20): окно на экране без замка и звонка поверх,
    // 3 секунды подряд. Только тогда — «прочитано» и снятие уведомлений; мелькнувшая — не считается.
    val seen = rememberSeen(chatId)
    LaunchedEffect(chatId, publicGroup, groupTop, seen) {
        if (!publicGroup || liveStates == null) return@LaunchedEffect
        val newest = runCatching { onPullGroupNew(chatId) }.getOrDefault(0L)
        if (newest > 0 && seen) liveStates.readEntity("group", chatId, newest)
    }
    // Собеседник личной переписки — ему «печатает», за его «в сети» смотрим (ПЛАН-(ОП)).
    val peerUser = remember(chatId, group) { if (group) null else environment.chatFacts.peerOf(chatId) }
    val live = liveStates?.takeIf { peerUser != null }
    if (live != null && peerUser != null) {
        DisposableEffect(chatId, peerUser) {
            live.watch(peerUser)
            onDispose {
                live.watch(null)
                live.stopTyping()
            }
        }
    }
    // Пока переписку видят — уведомлений о ней нет (У10): человек читает её глазами,
    // и строка в шторке была бы уведомлением о том, что он уже видит. `DisposableEffect`,
    // а не `LaunchedEffect`: уход с экрана обязан снять отметку, иначе закрытая переписка
    // останется «открытой» навсегда и замолчит насовсем.
    //
    // Отметка — только когда переписку **увидели** ([rememberSeen], 2026-10-20): открыли кнопкой
    // звонка, стоит замок, свёрнуто — не увидели, и «прочитано» отправителю было бы неправдой.
    DisposableEffect(chatId, notices, seen) {
        if (seen) {
            notices?.watching(chatId)
            seenUpTo(environment, chatId)
        }
        onDispose {
            if (seen) {
                notices?.watching(null)
                // И на уходе: пока переписку видели, в ней могло прийти новое — его тоже видели.
                seenUpTo(environment, chatId)
            }
        }
    }
    // Store живёт столько, сколько открыта переписка: ключ по chatId, чтобы при переходе в
    // другую он пересоздался, а не показал реплики предыдущей.
    val store = remember(chatId) {
        ChatAssembly.store(
            chatId = chatId, group = group, environment = environment, network = network, scope = scope,
            myDeviceId = myDeviceId, myUserId = myUserId, peerUser = peerUser, community = community,
            reopenUnreadable = reopenUnreadable, heal = heal, people = people, postman = postman,
            openByPhrase = openByPhrase, fromPhoneMay = fromPhoneMay,
            pins = pins, pinMay = pinMay, setSuper = setSuper, finder = finder,
        )
    }
    // Вид группы — первым: от него зависит, нужен ли ей ключ (заказчик 2026-10-21).
    LaunchedEffect(store, groupKind) { store.groupKind(groupKind) }
    val state by store.state.collectAsState()
    LaunchedEffect(pinMay) { store.pinAllowed(pinMay) }
    // «Прочитано» — только пока переписку видят (2026-10-20). Строка в журнале — по ней проверяется,
    // что открытая кнопкой звонка, под замком или свёрнутая переписка прочитанной не стала.
    var wasSeen by remember(store) { mutableStateOf(false) }
    LaunchedEffect(store, seen) {
        store.seen(seen)
        if (seen != wasSeen) Journal.note(LogCode.STATES, if (seen) "переписка видна — прочитано" else "переписка не видна", "чат" to chatId.take(8))
        wasSeen = seen
    }
    LaunchedEffect(superDraft) {
        superDraft?.let { doc ->
            store.composeSuper(doc)
            onSuperTaken()
        }
    }
    // Ветка открыта — показываем её вместо переписки, тем же подокном, что и комментарии
    // канала (ADR-0024: механизм один, на экране разные слова). «Назад» из ветки
    // возвращает в переписку, а не закрывает её: человек не уходил из группы.
    val someone = Tima.words.chat.someone
    state.thread?.let { open ->
        CommentsScreen(
            state = CommentsState(
                entries = open.replies.map { it.asComment(open.rootId) },
                level = open.root.level,
                loaded = true,
                draft = state.threadDraft,
            ),
            nameOf = { userId -> state.names[userId]?.line(authorLook) ?: someone },
            onBack = store::threadClosed,
            onDraft = store::threadDraftChanged,
            onSend = { store.threadSendPressed() },
            onReply = { name -> store.threadDraftChanged(WriteComment.mention(name, state.threadDraft)) },
            root = open.root.asComment(0),
            title = Tima.words.comments.thread,
        )
        return
    }
    LaunchedEffect(chatId, group) { if (group) onOpened(chatId) }
    if (group && onWatchGroupCall != null) LaunchedEffect(chatId) { onWatchGroupCall() }
    var failed by remember { mutableStateOf<ChatLine?>(null) }
    // Меню отправленного сообщения (Решения §11.1): на какое сообщение нажали.
    var menuFor by remember { mutableStateOf<ChatLine?>(null) }
    // Забранные получателем файлы почтальона — галочки у своих (Решения §6).
    // Голосовые подряд (ПЛАН-(ВМ) Р40): следующее — новее в этой переписке; не голосовое — стоп.
    SideEffect { voiceHub?.next = { id -> io.tima.feature.chat.nextVoiceAfter(state.lines, id) } }
    val fetchedFiles by (postman?.fetched ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptySet<String>()) }).collectAsState()
    val goneFiles by (postman?.gone ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptySet<String>()) }).collectAsState()
    val wifiFiles by (postman?.waitingWifi ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptySet<String>()) }).collectAsState()
    // Долгое нажатие — выделить часть и скопировать (Решения §11.1).
    var selectFor by remember { mutableStateOf<ChatLine?>(null) }
    // ── Вложения (Решения §2, §3, §6; ВЫБОР-РЕАЛИЗАЦИИ §11.2а) ─────────────────────
    var photoOpen by remember { mutableStateOf<io.tima.domain.chat.RichMedia?>(null) }
    // Просмотр фото: что сказать поверх фото, какое пересылается, «Поделиться» платформы.
    var photoNotice by remember { mutableStateOf<String?>(null) }
    var forwardOf by remember { mutableStateOf<Pair<io.tima.feature.chat.PhotoAt, androidx.compose.ui.graphics.ImageBitmap?>?>(null) }
    // Правка фото уходит, когда просмотр закрыт, — и может пережить сам экран переписки (ADR-0052).
    val photoEditScope = remember { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default) }
    val shareImage = io.tima.core.media.rememberShareImage()
    // Меню файла (заказчик 2026-10-20): на какой файл нажали; какой пересылается.
    var fileFor by remember { mutableStateOf<io.tima.domain.chat.RichMedia?>(null) }
    var fileForward by remember { mutableStateOf<io.tima.domain.chat.RichMedia?>(null) }
    val shareFile = io.tima.core.media.rememberShareFile()
    val openFile = io.tima.core.media.rememberOpenFile()
    val photoWords = Tima.words.chat
    var asking by remember { mutableStateOf<AttachAsk?>(null) }
    var askTrouble by remember { mutableStateOf<String?>(null) }
    // Одно фото — сначала в редактор фото, его «Отправить» и шлёт; «Отмена» — не отправлять (заказчик
    // 2026-10-22). Несколько — уходят сразу, как прежде. Не раскодировалось — уходит как есть: решает отправка.
    var sendDraft by remember { mutableStateOf<SendDraft?>(null) }
    // Надписи редактора при сведении — тем же набором текста, что и на экране.
    val photoMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
    fun sendOne(bytes: ByteArray, mime: String) {
        scope.launch {
            val bmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                io.tima.core.media.decodeImage(bytes, atLeast = io.tima.core.media.PhotoPrep.FULL_SIDE)
            }
            if (bmp != null) sendDraft = SendDraft(bytes, mime, bmp) else store.sendPhotos(listOf(bytes to mime))
        }
    }
    val photosPicker = io.tima.core.media.rememberPhotosPicker(io.tima.domain.chat.SendAttachments.MAX_PHOTOS) { list ->
        when (list.size) {
            0 -> Unit
            1 -> sendOne(list.single().bytes, list.single().mime)
            else -> store.sendPhotos(list.map { it.bytes to it.mime })
        }
    }
    val camera = io.tima.core.media.rememberCameraShot { shot -> shot?.let { sendOne(it.bytes, it.mime) } }
    val onFile: (io.tima.core.media.PickedFile?) -> Unit = { f -> f?.let { store.sendFile(it.bytes, it.mime, it.name) } }
    val anyFile = io.tima.core.media.rememberFilePicker(io.tima.core.media.FileKind.ANY, onFile)
    val documentFile = io.tima.core.media.rememberFilePicker(io.tima.core.media.FileKind.DOCUMENT, onFile)
    val audioFile = io.tima.core.media.rememberFilePicker(io.tima.core.media.FileKind.AUDIO, onFile)
    val videoFile = io.tima.core.media.rememberFilePicker(io.tima.core.media.FileKind.VIDEO, onFile)
    val contactPicker = io.tima.core.media.rememberContactPicker { c -> c?.let { store.sendContact(it.name, it.phone) } }
    val placeNoAccess = Tima.words.chat.placeNoAccess
    val myPlace = io.tima.core.media.rememberMyPlace { p ->
        if (p != null) store.sendPlace(p.first, p.second) else { askTrouble = placeNoAccess; asking = AttachAsk.Place }
    }
    // В группе файлы — только ссылкой (Решения §2): верхние «Файл», «Документ», «Аудио», «Видео» там убраны —
    // их делает раздел «Из облака» (заказчик 2026-10-24, 1г#).
    val byLinkOnly = setOf(
        io.tima.feature.chat.AttachItem.FILE, io.tima.feature.chat.AttachItem.DOCUMENT,
        io.tima.feature.chat.AttachItem.AUDIO, io.tima.feature.chat.AttachItem.VIDEO,
    )
    val attachItems = io.tima.feature.chat.AttachItem.entries.filter {
        (it != io.tima.feature.chat.AttachItem.CAMERA || camera != null) && !(store.filesByLink && it in byLinkOnly)
    }
    var linkAsk by remember { mutableStateOf(false) }
    val attachHandler: (io.tima.feature.chat.AttachItem) -> Unit = { item ->
        // В группе файлы — только ссылкой на облако (Решения §2).
        fun fileOr(kind: String, pick: () -> Unit) = if (store.filesByLink) asking = AttachAsk.Cloud(kind) else pick()
        when (item) {
            io.tima.feature.chat.AttachItem.PICTURE -> photosPicker()
            io.tima.feature.chat.AttachItem.CAMERA -> camera?.invoke()
            io.tima.feature.chat.AttachItem.FILE -> fileOr("file", anyFile)
            io.tima.feature.chat.AttachItem.DOCUMENT -> fileOr("document", documentFile)
            io.tima.feature.chat.AttachItem.AUDIO -> fileOr("audio", audioFile)
            io.tima.feature.chat.AttachItem.VIDEO -> fileOr("video", videoFile)
            io.tima.feature.chat.AttachItem.CONTACT -> contactPicker?.invoke() ?: run { asking = AttachAsk.Contact }
            io.tima.feature.chat.AttachItem.PLACE -> myPlace?.invoke() ?: run { asking = AttachAsk.Place }
            // Ссылка — тем же окном, что «Вставить ссылку» в редакторе (заказчик 2026-10-19): вид,
            // название, цвет; уходит отдельным сообщением в своём пузыре.
            io.tima.feature.chat.AttachItem.LINK -> linkAsk = true
            io.tima.feature.chat.AttachItem.CLOUD_FILE -> asking = AttachAsk.FromCloud("file")
            io.tima.feature.chat.AttachItem.CLOUD_VIDEO -> asking = AttachAsk.FromCloud("video")
            io.tima.feature.chat.AttachItem.CLOUD_AUDIO -> asking = AttachAsk.FromCloud("audio")
        }
    }
    // «Сохранить как» (Решения §6): скачать у почтальона, открыть ключом из тела, отдать системе.
    val saved = Tima.words.chat
    val saver = io.tima.core.media.rememberFileSaver { ok ->
        Journal.note(LogCode.FILE_STORE, "сохранено как", "вышло" to ok)
        if (!ok) store.said(saved.fileRefused(""))
    }
    // Файл открытым: из своей папки, иначе у почтальона; срок прошёл — попросили прислать снова
    // (Решения §6). Не вышло — сказать почему и вернуть `null`.
    suspend fun plainOf(file: io.tima.domain.chat.RichMedia, say: (String) -> Unit): ByteArray? {
        val got = postman?.obtain(file)
            ?: network.media.download(file.mediaId)?.let { PostmanFiles.Obtained.Got(it) }
            ?: PostmanFiles.Obtained.Failed
        return when (got) {
            is PostmanFiles.Obtained.Got -> io.tima.core.encryption.MediaFiles.open(file.key, got.sealed).also { if (it == null) say(saved.fileGone) }
            PostmanFiles.Obtained.Asked -> null.also { say(saved.fileAsked) }
            PostmanFiles.Obtained.Failed -> null.also { say(saved.sendFailed) }
        }
    }
    val saveFromChat: (io.tima.domain.chat.RichMedia) -> Unit = { file ->
        scope.launch {
            plainOf(file, store::said)?.let { plain -> saver(savedName(file, 0), file.mime, plain) }
        }
    }
    // «Сохранить как» группой — в выбранную папку (заказчик 2026-10-22).
    val folderSaver = io.tima.core.media.rememberFolderSaver { count ->
        Journal.note(LogCode.FILE_STORE, "сохранено группой", "файлов" to count)
        store.said(saved.savedFiles(count))
    }
    /** Файлы и фото сообщений: один — «Сохранить как», несколько — группой в папку. */
    val saveFiles: (List<io.tima.domain.chat.RichMedia>) -> Unit = { files ->
        if (files.size == 1) {
            saveFromChat(files.single())
        } else if (files.isNotEmpty()) {
            scope.launch {
                val items = files.mapIndexedNotNull { i, m -> plainOf(m, store::said)?.let { io.tima.core.media.SavedFile(savedName(m, i + 1), m.mime, it) } }
                if (items.isNotEmpty()) folderSaver(items)
            }
        }
    }
    // Пересланное сюда фото — уходит, как только переписка готова (у группы — известен её вид:
    // от него круг быстрой отправки).
    LaunchedEffect(incoming, kind) {
        val items = incoming ?: return@LaunchedEffect
        if (group && kind == null) return@LaunchedEffect
        store.sendForwarded(items)
        onIncomingTaken()
        Journal.note(LogCode.FILE_SEND, "переслано сюда", "сколько" to items.size)
    }
    // Выбор нескольких сообщений (заказчик 2026-10-19): что выбрано — строки переписки.
    var chosenLines by remember(chatId) { mutableStateOf<List<ChatLine>>(emptyList()) }
    val chooseToggle: (ChatLine) -> Unit = { line ->
        chosenLines = if (chosenLines.any { it.dedupKey == line.dedupKey }) chosenLines.filterNot { it.dedupKey == line.dedupKey } else chosenLines + line
    }
    var chosenDelete by remember { mutableStateOf(false) }
    // Копия целиком (заказчик 2026-10-22): вставленное и подкладка — по этой переписке, как на экране.
    val copyWords = Tima.words.chat
    val fullText: (ChatLine) -> String = { line ->
        val quoteLook = io.tima.feature.chat.chatLooks(state, name ?: copyWords.someone, authorLook, copyWords.someone).second
        io.tima.feature.chat.copyText(line, quoteLook, copyWords.copyUnavailable, copyWords.deleted)
    }
    var chosenForward by remember { mutableStateOf(false) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val shareOut = remember { io.tima.core.contacts.platformInvite() }
    val copiedWord = Tima.words.chat.copied
    // Выбран круг, которого у группы этого вида нет (остался с прежней сборки или от
    // другой группы) — сбросить на первый допустимый, иначе отправка получит 400.
    LaunchedEffect(kind, state.level) {
        val allowed = kind?.circles ?: return@LaunchedEffect
        if (allowed.none { it.level == state.level }) store.circleChosen(allowed.first().level)
    }
    // Быстрая отправка из строки ввода (Решения §9): личная группа — −1, публичная — 2. Выбора
    // над строкой нет; другой уровень ставит редактор.
    LaunchedEffect(kind) { kind?.let { store.circleChosen(it.quickLevel) } }
    var myColor by remember { mutableStateOf(false) }
    // Листы переписки — подокна в паре меток для сценариев (ПЛАН-(МО)).
    InnerPopup(PopupCode.CHAT_MENU, chatMenu)
    InnerPopup(PopupCode.CHAT_FAILED, failed != null)
    InnerPopup(PopupCode.CHAT_COLOR, myColor)
    InnerPopup(PopupCode.CHAT_MESSAGE, menuFor != null)
    InnerPopup(PopupCode.CHAT_EDITOR, state.editorOpen)
    var myColorTrouble by remember { mutableStateOf<String?>(null) }
    // Отправители, чья личность отменена владельцем (ДУ6, Р30) — из настроек аккаунта.
    // Поток запомнен: `settings.all()` каждый раз новый, и подписка без remember снималась и
    // ставилась заново на каждую перерисовку — раз в секунду, пока открыта переписка
    // (ПЛАН-(БС) Э5.2). Выборка — не на главном потоке, и только когда она изменилась.
    val cancelledSenders by remember(environment) {
        environment.settings.all()
            .map { all -> all.keys.filter { it.startsWith(IdentityChain.CANCELLED_PREFIX) }.map { it.removePrefix(IdentityChain.CANCELLED_PREFIX) }.toSet() }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
    }.collectAsState(initial = emptySet())
    // Повтор нажатия — в шаги открытого черновика (2#); проскочившая копия виджета — в журнал (3#).
    DisposableEffect(store) {
        store.copyLog = { kind -> Journal.note(LogCode.EDITOR, "возможная копия", "вид" to kind) }
        val off = io.tima.core.ui.OnePressLog.listen { tag, skipped, _ -> store.draftNote(io.tima.domain.chat.DraftSteps.repeat(tag, skipped)) }
        onDispose {
            off()
            store.copyLog = null
        }
    }
    // «Прочитано» — то новое, что сейчас на экране (ПЛАН-(ОП)): время написания позднейшего входящего.
    val newestIncoming = state.lines.filter { !it.outgoing }.maxOfOrNull { it.atMs }
    LaunchedEffect(newestIncoming, live) { if (live != null && newestIncoming != null) live.read(chatId, newestIncoming) }
    val receipts by (live?.receipts ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyMap<String, io.tima.feature.chat.ChatReceipt>()) }).collectAsState()
    val typingNow by (live?.typing ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyMap<String, Long>()) }).collectAsState()
    val presenceNow by (live?.presence ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyMap<String, LiveStates.Seen>()) }).collectAsState()
    // Секунды — чтобы «печатает» и «в сети» гасли по сроку и без нового сигнала.
    var tickMs by remember { mutableStateOf(msNow()) }
    LaunchedEffect(live) {
        while (live != null) {
            kotlinx.coroutines.delay(1_000)
            tickMs = msNow()
        }
    }
    val mutedNow by (liveStates?.muted ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptySet<String>()) }).collectAsState()
    // Строка статуса — производное: секундный тик пересчитывает только её, а переписка
    // перерисовывается, лишь когда строка сменилась (ПЛАН-(БС) Э5.2).
    val chatWords = Tima.words.chat
    val callWords = Tima.words.callLog
    val peerLine by remember(live, chatId, peerUser, chatWords, callWords) {
        derivedStateOf {
            if (live == null) {
                null
            } else {
                val seen = peerUser?.let { presenceNow[it] }
                io.tima.feature.chat.peerStatus(
                    chatWords, callWords,
                    typing = (typingNow[chatId] ?: 0) > tickMs,
                    online = seen?.onlineAt(tickMs) == true,
                    lastSeenMs = seen?.lastSeenMs ?: 0,
                )
            }
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(
        io.tima.feature.chat.LocalChatSelection provides io.tima.feature.chat.ChatSelection(chosenLines.map { it.dedupKey }.toSet(), chooseToggle),
    ) {
        ChatScreen(
            onPeek = if (finder != null) store::peek else null,
            onOlder = store::older,
            onReach = store::reach,
            onPlace = store::savePlace,
            onPeekClose = store::peekClosed,
            onUnpin = if (pins != null) store::unpin else null,
            onShowcase = onShowcase,
            cancelledSenders = cancelledSenders,
            // Строка ввода правит набранное без знаков (ADR-0048).
            source = io.tima.core.encryption.TypedOverQuickMarkdown,
            state = state,
            receipt = receipts[chatId],
            onPerson = onPerson,
            onCall = onCall,
            peerFace = if (ttlUntilMs != null) callCreatorFace else peerFace,
            callCreator = callCreator,
            callGroup = ttlUntilMs != null,
            authorLook = authorLook,
            ownerId = ownerId,
            hues = hues,
            peer = name ?: "Без имени",
            onSet = { text ->
                store.draftChanged(text)
                // «Печатаю» собеседнику — пока набирают; стёрли — «перестал» (ПЛАН-(ОП)).
                if (live != null && peerUser != null) {
                    if (text.isBlank()) live.stopTyping() else live.typed(chatId, peerUser)
                }
            },
            onSend = {
                live?.stopTyping()
                store.sendPressed()
            },
            onBack = onBack,
            onCloseMessage = store::noticeDismissed,
            onRequestKey = store::requestKey,
            // Нажатие на «Не расшифровано» — окно «Ждём ключ…» (план КК, КК4).
            onAskChatKeys = store::unreadableTapped,
            onKeyWaitClose = store::keyWaitClosed,
            onPhraseOpen = store::phraseOpenStarted,
            onOpenByPhrase = store::openByPhrasePressed,
            onFromPhone = store::fromPhonePressed,
            avatarLook = avatarLook,
            onAuthor = onAuthor,
            onPhrase = store::changedPhrase,
            onMembers = if (group) onMembers else null,
            onMore = if ((group && onMoveToShelf != null) || (!group && onMoveToBookSection != null)) { { chatMenu = true } } else null,
            // Круг предлагается только в группе: в личной переписке всё зашифровано и
            // адресовано одному человеку — выбирать нечего.
            circle = if (group) MessageCircle.of(state.level) else null,
            circles = kind?.circles ?: MessageCircle.entries,
            onCircle = if (group) { chosen -> store.circleChosen(chosen.level) } else null,
            // Сужение живёт рядом с меткой круга: показ включён — значит человек занят
            // доступом, а не чтением. Выключен — реплики выглядят как обычно.
            onNarrow = if (group) { messageId, was, to -> store.narrowAsked(messageId, was, to) } else null,
            onNarrowConfirm = store::narrowConfirmed,
            onDeleteConfirm = store::deleteForAllConfirmed,
            onEditCancel = store::editCancelled,
            onReplyCancel = store::replyCancelled,
            onEmojiSend = store::sendEmoji,
            onEditor = if (store.canCompose) ({
                store.openEditor()
                Journal.note(LogCode.EDITOR, "открыт")
            }) else null,
            composed = state.composed?.let { io.tima.feature.chat.ComposedLook(it.firstLine, it.rows) },
            onComposedDrop = {
                store.dropComposed()
                Journal.note(LogCode.EDITOR, "корзина")
            },
            onComposedRestore = if (state.droppedComposed != null) ({
                store.restoreComposed()
                Journal.note(LogCode.EDITOR, "вернуть")
            }) else null,
            onJump = { what, chain -> Journal.note(LogCode.JUMP, if (what == "to") "к сообщению" else "назад или вперёд", "точек" to chain, "буфер" to jumpPlace?.key?.substringBefore(':')) },
            jumpPlace = jumpPlace,
            onAttach = if (store.canAttach) attachHandler else null,
            attachItems = attachItems,
            // Голосовое (ПЛАН-(ВМ) §2.5): в личной и в группе; разрешение — как у звонка, без камеры.
            voice = if (store.canAttach) io.tima.feature.chat.VoiceInput(
                ask = { done -> io.tima.core.call.askCallAccess(false) { done(it) } },
                micName = voiceMic,
                send = { clip ->
                    Journal.note(LogCode.VOICE, "отправка", "секунд" to clip.durationMs / 1000, "размер" to clip.ogg.size)
                    store.sendVoice(clip.ogg, clip.durationMs, clip.wave)
                },
                onEvent = { what, value ->
                    if (what == io.tima.feature.chat.VoiceEvent.RECORD_STARTED) Journal.note(LogCode.VOICE, voiceWord(what), "кГц" to value)
                    else Journal.note(LogCode.VOICE, voiceWord(what), "секунд" to value)
                },
            ) else null,
            attachments = io.tima.feature.chat.AttachmentActions(
                // Идёт выбор — нажатие на фото отмечает его сообщение, а не открывает (2026-10-19).
            openPhoto = { photo ->
                val owner = state.lines.firstOrNull { l -> l.rich?.media?.any { it.mediaId == photo.mediaId } == true }
                if (chosenLines.isNotEmpty() && owner != null) chooseToggle(owner) else photoOpen = photo
            },
            selectPhoto = { photo ->
                state.lines.firstOrNull { l -> l.rich?.media?.any { it.mediaId == photo.mediaId } == true }?.let(chooseToggle)
            },
                saveFile = { file -> saveFromChat(file) },
                fetched = { id -> id in fetchedFiles },
                gone = { id -> id in goneFiles },
                waitsWifi = { id -> id in wifiFiles },
                voice = voiceHub,
                fileMenu = { file ->
                    // Идёт выбор — нажатие отмечает сообщение с файлом, как у фото.
                    val owner = state.lines.firstOrNull { l -> l.rich?.media?.any { it.mediaId == file.mediaId } == true }
                    if (chosenLines.isNotEmpty() && owner != null) chooseToggle(owner) else fileFor = file
                },
            ),
            onMenu = { line -> menuFor = line },
            onSelect = { line -> selectFor = line },
            // Показ доступности — только в группе: в личной переписке круга нет.
            onCircles = if (group) store::circlesShown else null,
            // «Добавить себе» ведёт на свою страницу — то есть в другое окно. Оттого перенос
            // делает PageStore, а не ChatStore: страница одна, и знать о принесённом обязана
            // она, а не окно переписки.
            onCarry = if (group) { messageId, was -> onCarry(messageId, was) } else null,
            // Ветка — только в группе: в личной переписке отвечать некому, кроме одного
            // собеседника, и разговор о реплике совпадает с самой перепиской.
            onThread = if (group) store::threadOpened else null,
            onFailed = { line -> failed = line },
            // Временная группа звонка: «удалится через N ч» под названием (решение 11).
            caption = ttlUntilMs?.let { Tima.words.groupCall.ttl(((it - msNow()) / 3_600_000L).toInt().coerceAtLeast(0)) } ?: peerLine,
            // Полоса «Идёт звонок · Присоединиться» над лентой группы (решение 3а). В личной
            // переписке прежней личности во время перерегистрации — предупреждение (Р54).
            banner = if (reregWarn && !group) {
                { io.tima.feature.chat.GroupCallBanner(text = Tima.words.auth.reregSenderWarn, joinLabel = null, onJoin = {}) }
            } else groupCall?.takeIf { group }?.let { live ->
                {
                    val inRoom = live.members.count { it.inRoom }
                    val minutes = ((msNow() - live.startedAtMs) / 60_000L).toInt().coerceAtLeast(0)
                    io.tima.feature.chat.GroupCallBanner(
                        text = if (live.paused) Tima.words.groupCall.livePaused else Tima.words.groupCall.live(inRoom, minutes),
                        joinLabel = if (inCallHere) null else Tima.words.groupCall.join,
                        onJoin = { onJoinGroupCall(live.callId) },
                    )
                }
            } ?: onRepeatGroupCall?.takeIf { group && !inCallHere }?.let { repeat ->
                {
                    io.tima.feature.chat.GroupCallBanner(
                        text = Tima.words.groupCall.inviteEnded,
                        joinLabel = Tima.words.groupCall.repeat,
                        onJoin = repeat,
                    )
                }
            },
            invite = inviteOf,
            onInvite = onInvite,
        )
    }
    if (chosenLines.isNotEmpty()) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            io.tima.feature.chat.SelectionBar(
                count = chosenLines.size,
                onPick = if (chosenLines.any { it.serverId > 0 }) ({
                    store.pickedMany(chosenLines)
                    Journal.note(LogCode.PICKS, "добавить выбранные", "сколько" to chosenLines.size)
                    chosenLines = emptyList()
                }) else null,
                // Копируем сообщения с текстом, сохраняем только файлы и фото (заказчик 2026-10-22).
                onCopy = chosenLines.filter { filesOf(it).isEmpty() && !it.text.isNullOrBlank() }.takeIf { it.isNotEmpty() }?.let { texts ->
                    {
                        val text = texts.sortedBy { it.atMs }.map(fullText).filter(String::isNotBlank).joinToString("\n\n")
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
                        store.said(copiedWord)
                        chosenLines = emptyList()
                    }
                },
                onSave = chosenLines.sortedBy { it.atMs }.flatMap(::filesOf).takeIf { it.isNotEmpty() }?.let { files ->
                    {
                        saveFiles(files)
                        chosenLines = emptyList()
                    }
                },
                onForward = if (onForward != null && forwardTargets.isNotEmpty()) ({ chosenForward = true }) else null,
                onDelete = { chosenDelete = true },
                onClose = { chosenLines = emptyList() },
            )
        }
    }
    if (chosenDelete) {
        val cw = Tima.words.chat
        val own = chosenLines.all { it.outgoing && it.serverId > 0 }
        io.tima.feature.chat.ChoiceSheet(
            title = cw.deleteManyTitle(chosenLines.size),
            items = listOf(io.tima.feature.chat.Choice(cw.menuDeleteForMe)) +
                if (own) listOf(io.tima.feature.chat.Choice(cw.menuDeleteForAll)) else emptyList(),
            empty = cw.deleteManyTitle(chosenLines.size),
            onCancel = { chosenDelete = false },
            onPick = { i ->
                chosenDelete = false
                if (i == 0) store.deleteForMeMany(chosenLines) else store.deleteForAllManyAsked(chosenLines)
                chosenLines = emptyList()
            },
        )
    }
    if (chosenForward) {
        val cw = Tima.words.chat
        io.tima.feature.chat.ChoiceSheet(
            title = cw.photoForwardTitle,
            items = forwardTargets.map { io.tima.feature.chat.Choice(title = it.second) },
            empty = cw.photoForwardTitle,
            onCancel = { chosenForward = false },
            onPick = { i ->
                val (target, title) = forwardTargets[i]
                chosenForward = false
                val lines = chosenLines.sortedBy { it.atMs }
                chosenLines = emptyList()
                scope.launch {
                    // Фото — файлом от почтальона, остальное — знаками или документом редактора.
                    val items = lines.flatMap { line ->
                        val rich = line.rich
                        val photos = rich?.blocks?.filterIsInstance<io.tima.domain.chat.RichBlock.Album>()
                            ?.flatMap { a -> a.media.mapNotNull { rich.media.getOrNull(it) } }.orEmpty()
                        if (photos.isNotEmpty()) {
                            photos.mapNotNull { m -> plainOf(m, store::said)?.let { io.tima.feature.chat.Forwarded.Photo(it, m.mime) } }
                        } else {
                            listOfNotNull(store.forwardOf(line))
                        }
                    }
                    if (items.isEmpty()) {
                        store.said(cw.forwardNothing)
                    } else {
                        Journal.note(LogCode.FILE_SEND, "переслать", "сколько" to items.size)
                        onForward?.invoke(target, title, items)
                    }
                }
            },
        )
    }

    // Подокно неотправленного — ПОСЛЕ экрана переписки: что позже в композиции, то сверху.
    failed?.let { line ->
        val waiting = line.display == MessageDisplay.PENDING
        FailedMessageSheet(
            text = line.text,
            reason = line.failReason,
            onDelete = { store.deleteUnsent(line.dedupKey); failed = null },
            onReport = onReportFailed?.let { report -> { report(line.failReason, waiting); failed = null } },
            onClose = { failed = null },
            waiting = waiting,
            attempts = line.attempts,
            // Сколько осталось до следующей попытки; срока нет — очередь возьмётся ближайшим
            // проходом, и это честнее выдуманных секунд.
            secondsLeft = if (line.nextAttemptAtMs > 0) {
                ((line.nextAttemptAtMs - msNow()) / 1000).coerceAtLeast(0).toInt()
            } else {
                0
            },
            // Повтор — только у отказанного: ждущее очередь повторяет сама.
            onRetry = if (waiting) null else ({ store.retryDead(line.dedupKey); failed = null }),
        )
    }
    // Меню сообщения — после экрана, как и лист неотправленного: что позже, то сверху.
    sendDraft?.let { draft ->
        io.tima.feature.chat.PhotoEditor(
            source = draft.preview,
            saveLabel = photoWords.photoSend,
            onSave = { marks ->
                sendDraft = null
                if (marks.isEmpty) {
                    // Ничего не меняли — уходит исходник обычным путём.
                    Journal.note(LogCode.FILE_SEND, "одно фото через редактор — без изменений")
                    store.sendPhotos(listOf(draft.bytes to draft.mime))
                } else {
                    store.sendPhotoMade { side -> madePhoto(draft, marks, side, photoMeasurer) }
                }
            },
            onCancel = {
                sendDraft = null
                Journal.note(LogCode.FILE_SEND, "одно фото — отправка отменена в редакторе")
            },
        )
    }
    photoOpen?.let { opened ->
        // Все фото переписки от старых к новым: «‹», «›» и смахивание вбок листают их (2026-10-18).
        val photos = remember(state.lines, state.names) {
            state.lines.asReversed().flatMap { line ->
                val rich = line.rich ?: return@flatMap emptyList()
                val from = if (line.outgoing) myName else line.senderId?.let { state.names[it]?.name } ?: name
                rich.blocks.filterIsInstance<io.tima.domain.chat.RichBlock.Album>()
                    .flatMap { album -> album.media.mapNotNull { rich.media.getOrNull(it) } }
                    .map { io.tima.feature.chat.PhotoAt(it, line, from) }
            }
        }
        val start = photos.indexOfFirst { it.media.mediaId == opened.mediaId }.coerceAtLeast(0)
        val say: (String) -> Unit = { photoNotice = it }
        io.tima.feature.chat.PhotoViewer(
            photos = photos.ifEmpty { listOf(io.tima.feature.chat.PhotoAt(opened, ChatLine(dedupKey = "", chatId = chatId, display = MessageDisplay.SENT, text = null, outgoing = false, atMs = 0, localId = 0), null)) },
            start = start,
            notice = photoNotice,
            onClose = { photoOpen = null; photoNotice = null },
            actions = io.tima.feature.chat.PhotoActions(
                save = { p, changed ->
                    scope.launch {
                        val plain = changed?.let { jpegOf(it) } ?: plainOf(p.media, say)
                        plain?.let { saver(p.media.name.ifBlank { "photo.jpg" }, if (changed != null) "image/jpeg" else p.media.mime, it) }
                    }
                },
                // Удалить — сообщение, в котором фото пришло; у всех — своё и отправленное.
                deleteForMe = { p -> photoOpen = null; store.deleteForMe(p.line) },
                deleteForAll = { p -> photoOpen = null; store.deleteForAllAsked(p.line) },
                forward = if (onForward != null && forwardTargets.isNotEmpty()) { p, changed -> forwardOf = p to changed } else null,
                share = { p, changed ->
                    if (shareImage == null) {
                        say(photoWords.photoShareNone)
                    } else {
                        scope.launch {
                            val plain = changed?.let { jpegOf(it) } ?: plainOf(p.media, say)
                            plain?.let { if (!shareImage(it, if (changed != null) "image/jpeg" else p.media.mime)) say(photoWords.photoShareNone) }
                        }
                    }
                },
                // Поворот и редактор (заказчик 2026-10-22, ПЛАН-(ИФ) ИФ5–ИФ7).
                load = { p -> plainOf(p.media, say)?.let { io.tima.core.media.decodeImage(it) } },
                // Листание пропускает фото, которого нет и не будет, и ждущее Wi-Fi (заказчик 2026-10-23).
                skip = { p -> p.media.mediaId in goneFiles || p.media.mediaId in wifiFiles },
                refusal = { p ->
                    when (val no = if (group) PhotoRefusal.Group else photoRefusal(p.line, environment.edit, chatId)) {
                        null -> null
                        PhotoRefusal.Group -> photoWords.photoEditGroup
                        PhotoRefusal.NotMine -> photoWords.photoEditNotMine
                        PhotoRefusal.NotSent -> photoWords.photoEditNotSent
                        PhotoRefusal.Limit -> photoWords.photoEditLimit
                        PhotoRefusal.KeyChanged -> photoWords.photoEditKeyChanged
                    }
                },
                commit = { changed ->
                    val peer = peerUser ?: myUserId.takeIf { it.isNotEmpty() }
                    val uploads = SealedUploads({ network.media }) { id, sealed, mime -> postman?.keepSent(id, sealed, photo = mime.startsWith("image/"), voice = mime == io.tima.domain.chat.SendAttachments.VOICE_MIME) }
                    val rules = io.tima.domain.chat.CachedMediaRules(environment.settings, { network.media.rules() }, ::msNow)
                    photoEditScope.launch {
                        for ((p, bmp) in changed) {
                            val index = p.line.rich?.media?.indexOfFirst { it.mediaId == p.media.mediaId } ?: -1
                            if (index < 0) continue
                            val ready = io.tima.core.media.PhotoPrep.fit(bmp, rules.rules().photoSide)
                            val up = uploads.upload(ready.full, "image/jpeg", chatId, peer)
                            if (up == null) {
                                Journal.trouble(LogCode.FILE_SEND, "изменённое фото не загрузилось", "файл" to p.media.mediaId.take(8))
                                store.said(photoWords.sendFailed)
                                continue
                            }
                            val media = io.tima.domain.chat.OutMedia(up.first, up.second, "image/jpeg", ready.width, ready.height, ready.full.size.toLong())
                            val result = environment.edit.replaceMedia(chatId, p.line.serverId, index, media)
                            Journal.note(LogCode.FILE_SEND, "фото изменено — правка", "итог" to result::class.simpleName, "место" to index)
                            if (result is io.tima.domain.chat.EditResult.Queued) {
                                // Прежний файл у почтальона больше не нужен (ПЛАН-(ИФ) ИФ-В2).
                                network.media.drop(p.media.mediaId)
                                store.said(photoWords.photoEditSent)
                            } else {
                                network.media.drop(up.first)
                                store.said(photoWords.photoEditRefused)
                            }
                        }
                    }
                },
            ),
        )
    }
    forwardOf?.let { (p, changed) ->
        io.tima.feature.chat.ChoiceSheet(
            title = photoWords.photoForwardTitle,
            items = forwardTargets.map { io.tima.feature.chat.Choice(title = it.second) },
            empty = photoWords.photoForwardTitle,
            onCancel = { forwardOf = null },
            onPick = { i ->
                val (target, title) = forwardTargets[i]
                forwardOf = null
                scope.launch {
                    val plain = changed?.let { jpegOf(it) } ?: plainOf(p.media) { photoNotice = it } ?: return@launch
                    photoOpen = null
                    Journal.note(LogCode.FILE_SEND, "переслать", "вид" to "фото", "изменено" to (changed != null))
                    onForward?.invoke(target, title, listOf(io.tima.feature.chat.Forwarded.Photo(plain, if (changed != null) "image/jpeg" else p.media.mime)))
                }
            },
        )
    }
    fileFor?.let { file ->
        // Меню файла (заказчик 2026-10-20): «Открыть», «Сохранить как», «Поделиться», «Переслать»,
        // «Удалить у себя» — сообщение, в котором файл. Чего платформа не умеет — пункта нет.
        val owner = state.lines.firstOrNull { l -> l.rich?.media?.any { it.mediaId == file.mediaId } == true }
        val named = file.name.ifBlank { "file" }
        // Программа (apk и подобные) — «Открыть» нет (заказчик 2026-10-24, 2а#): установщик Android считал бы,
        // что ставит TIMA, а у неё есть разрешение ставить приложения — для своего обновления. «Сохранить
        // как» и установка из «Файлов» — тогда Android спросит разрешение у «Файлов».
        val program = io.tima.domain.chat.FileKinds.ofName(named).danger == io.tima.domain.chat.FileKinds.Danger.PROGRAM ||
            file.mime == "application/vnd.android.package-archive"
        val items = buildList<Pair<String, () -> Unit>> {
            if (openFile != null && !program) add(photoWords.fileOpen to {
                scope.launch {
                    val plain = plainOf(file, store::said) ?: return@launch
                    val opened = openFile(plain, file.mime, named)
                    Journal.note(LogCode.FILE_STORE, "открыть", "вышло" to opened)
                    if (!opened) store.said(photoWords.fileOpenNone)
                }
            })
            add(photoWords.fileSaveAs to { saveFromChat(file) })
            if (shareFile != null) add(photoWords.menuShare to {
                scope.launch { plainOf(file, store::said)?.let { plain -> if (!shareFile(plain, file.mime, named)) store.said(photoWords.fileOpenNone) } }
            })
            if (onForward != null && forwardTargets.isNotEmpty()) add(photoWords.photoForward to { fileForward = file })
            if (owner != null) add(photoWords.menuDeleteForMe to { store.deleteForMe(owner) })
        }
        io.tima.feature.chat.ChoiceSheet(
            title = file.name.ifBlank { file.mime },
            items = items.map { (title, _) ->
                io.tima.feature.chat.Choice(title = title, about = if (program && title == photoWords.fileSaveAs) photoWords.fileProgramOwn else "")
            },
            empty = file.name,
            onCancel = { fileFor = null },
            onPick = { i ->
                fileFor = null
                items[i].second()
            },
        )
    }
    fileForward?.let { file ->
        io.tima.feature.chat.ChoiceSheet(
            title = photoWords.photoForwardTitle,
            items = forwardTargets.map { io.tima.feature.chat.Choice(title = it.second) },
            empty = photoWords.photoForwardTitle,
            onCancel = { fileForward = null },
            onPick = { i ->
                val (target, title) = forwardTargets[i]
                fileForward = null
                scope.launch {
                    val plain = plainOf(file, store::said) ?: return@launch
                    Journal.note(LogCode.FILE_SEND, "переслать", "вид" to "файл")
                    onForward?.invoke(target, title, listOf(io.tima.feature.chat.Forwarded.File(plain, file.mime, file.name.ifBlank { "file" })))
                }
            },
        )
    }
    selectFor?.let { line ->
        // Целиком — со ссылками, вставленным и подкладкой (заказчик 2026-10-22).
        val text = fullText(line)
        io.tima.feature.chat.SelectSheet(
            text = text,
            onCopyAll = {
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
                store.said(copiedWord)
                selectFor = null
            },
            onClose = { selectFor = null },
        )
    }
    // Редактор поверх переписки (Решения §10). Модель живёт, пока окно открыто: закрыли — документ
    // ушёл черновиком в store, следующее открытие начнёт историю заново.
    if (state.editorOpen) {
        // Правка своего сообщения (ряд, виджет) — документ исходного; иначе собранный черновик.
        val editingDoc = remember { state.editDoc != null }
        val cw = Tima.words.chat
        val editorModel = remember { io.tima.feature.chat.EditorModel(state.editDoc ?: state.composed ?: io.tima.domain.chat.ComposedDoc(), io.tima.core.encryption.TypedOverQuickMarkdown) }
        val ew = Tima.words.editor
        // Заголовок пункта, которым подборка или подкладка встаёт в список (2026-10-21).
        val refItemWord = Tima.words.chat.refItem
        var editorNotice by remember { mutableStateOf<String?>(null) }
        var editorAsk by remember { mutableStateOf<String?>(null) }
        var editorChoice by remember { mutableStateOf<String?>(null) }
        // Вставка видео, аудио, потока (вид) и форма меток (к чему, сколько строк) — ПЛАН-(ВМ) §3.6–§3.8.
        var editorMedia by remember { mutableStateOf<String?>(null) }
        var editorMarks by remember { mutableStateOf<Pair<String?, Int>?>(null) }
        fun put(widget: io.tima.domain.chat.ComposedWidget) = editorModel.insert(io.tima.domain.chat.ComposedItem.Widget(widget))
        // Картинка внутри сообщения (Решения §3): под ширину ряда 720, без превью, хранится, пока живёт сообщение.
        val editorUploads = remember { SealedUploads({ network.media }) }
        // Исходные файлы картинок черновика (Решения §3): сменилась ширина места — картинка заново из исходного.
        val editorSources = remember { mutableMapOf<String, ByteArray>() }
        // Картинка — в документ: под ширину ряда, закрыта своим ключом, исходный файл — для смены ширины.
        fun insertPicture(picked: io.tima.core.media.PickedImage) {
            scope.launch {
                val prepared = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { io.tima.core.media.PhotoPrep.inline(picked.bytes) }
                val up = prepared?.let { editorUploads.upload(it.full, "image/jpeg", null, null) }
                if (prepared == null || up == null) {
                    editorNotice = ew.pictureFailed
                } else {
                    editorNotice = null
                    editorSources[up.first] = picked.bytes
                    put(io.tima.domain.chat.ComposedWidget.Image(io.tima.domain.chat.OutMedia(up.first, up.second, "image/jpeg", prepared.width, prepared.height, prepared.full.size.toLong())))
                }
            }
        }
        val editorPicture = io.tima.core.media.rememberPhotosPicker(1) { list -> list.firstOrNull()?.let(::insertPicture) }
        // Своя картинка видео (ПЛАН-(ВМ) Р43): тем же путём, что картинка в сообщении, но в виджет видео.
        var editorVideoPicture by remember { mutableStateOf<((io.tima.domain.chat.OutMedia?) -> Unit)?>(null) }
        val editorPictureForVideo = io.tima.core.media.rememberPhotosPicker(1) { list ->
            val done = editorVideoPicture ?: return@rememberPhotosPicker
            val picked = list.firstOrNull() ?: return@rememberPhotosPicker done(null)
            scope.launch {
                val prepared = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { io.tima.core.media.PhotoPrep.inline(picked.bytes) }
                val up = prepared?.let { editorUploads.upload(it.full, "image/jpeg", null, null) }
                done(if (prepared != null && up != null) io.tima.domain.chat.OutMedia(up.first, up.second, "image/jpeg", prepared.width, prepared.height, prepared.full.size.toLong()) else null)
            }
        }
        val editorContact = io.tima.core.media.rememberContactPicker { c -> c?.let { put(io.tima.domain.chat.ComposedWidget.Contact(it.name, it.phone)) } }
        val noPlaceAccess = Tima.words.chat.placeNoAccess
        val editorPlace = io.tima.core.media.rememberMyPlace { p ->
            if (p != null) put(io.tima.domain.chat.ComposedWidget.Place(p.first, p.second)) else { editorNotice = noPlaceAccess; editorAsk = "place" }
        }
        val linkCards = remember { io.tima.core.network.LinkCardsOverHttp() }
        // Ссылке выбрали вид «Карточка» в «Свойствах» (ADR-0050 §3) — карточка из сети. Сама кнопка
        // карточкой не становится (заказчик 2026-10-20: «стала Карточкой… с лагом 2 секунды»).
        androidx.compose.runtime.SideEffect {
            // «Свойства» видео, аудио или меток → «Редактировать метки» (заказчик 2026-10-24): нет меток — одна пустая строка.
            editorModel.onEditMarks = { bind -> editorMarks = bind to 1 }
            editorModel.onLink = { url ->
                scope.launch {
                    val card = linkCards.card(url) ?: return@launch
                    val image = card.image?.let { bytes ->
                        val prepared = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { io.tima.core.media.PhotoPrep.inline(bytes) }
                        val up = prepared?.let { editorUploads.upload(it.full, "image/jpeg", null, null) }
                        if (prepared != null && up != null) io.tima.domain.chat.OutMedia(up.first, up.second, "image/jpeg", prepared.width, prepared.height, prepared.full.size.toLong()) else null
                    }
                    editorModel.replaceLink(url, io.tima.domain.chat.ComposedWidget.Link(url, card.title, "card", card.description, image))
                }
            }
        }
        val replyLines = remember(state.lines) { state.lines.filter { !it.outgoing && it.serverId > 0 }.take(30) }
        val pickList = remember(editorChoice) {
            if (editorChoice == "picks" || editorChoice == "underlay") environment.picks.list(io.tima.domain.chat.MessagePicks.levelOf(chatId, group, community)) else emptyList()
        }
        // Ответ и вставленное в просмотре — как в ленте (заказчик 2026-10-20).
        io.tima.feature.chat.ChatLooks(state, name ?: "Без имени", authorLook) {
        io.tima.feature.chat.EditorScreen(
            model = editorModel,
            // Правка уходит уровнем исходного — уровень не выбирается.
            levels = if (editingDoc) emptyList() else kind?.circles ?: listOf(io.tima.domain.chat.MessageCircle.Secret),
            title = if (editingDoc) Tima.words.chat.editing else null,
            defaultLevel = state.level,
            preview = io.tima.core.encryption.ComposedBodiesOverKodium::preview,
            problems = io.tima.core.encryption.ComposedBodiesOverKodium::problems,
            onBack = { doc ->
                if (editingDoc) {
                    store.editorEditCancelled()
                    Journal.note(LogCode.EDITOR, "правка отменена")
                } else {
                    store.editorBack(doc)
                    Journal.note(LogCode.EDITOR, "закрыт", "рядов" to doc.rows)
                }
            },
            onDraft = store::editorDraft,
            onSend = send@{ doc ->
                if (editingDoc) {
                    when (val r = store.editorEditSend(doc)) {
                        io.tima.domain.chat.EditResult.Structure -> editorNotice = cw.editStructure
                        io.tima.domain.chat.EditResult.Empty -> editorNotice = cw.editImpossible
                        is io.tima.domain.chat.EditResult.TooLarge -> editorNotice = cw.editImpossible
                        is io.tima.domain.chat.EditResult.Queued -> Journal.note(LogCode.EDITOR, "правка отправлена", "рядов" to doc.rows)
                        null -> Unit
                        else -> Journal.note(LogCode.EDITOR, "правка", "исход" to r::class.simpleName.orEmpty())
                    }
                    return@send
                }
                scope.launch {
                    // Метки без своего видео не уходят (ПЛАН-(ВМ) Р9), и по порядку времени.
                    val doc = io.tima.domain.chat.ComposedMedia.prune(doc)
                    // Картинка в ячейке уже ряда — под её ширину (720 × доля), из исходного файла.
                    val fitted = doc.copy(
                        items = doc.items.map { item ->
                            if (item !is io.tima.domain.chat.ComposedItem.Row) return@map item
                            item.copy(
                                cells = item.cells.map { cell ->
                                    val target = io.tima.core.media.PhotoPrep.INLINE_SIDE * cell.width / 100
                                    cell.copy(
                                        widgets = cell.widgets.map { w ->
                                            val source = (w as? io.tima.domain.chat.ComposedWidget.Image)?.media?.takeIf { it.width > target }?.let { editorSources[it.mediaId] }
                                            if (source == null) return@map w
                                            val prepared = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { io.tima.core.media.PhotoPrep.inline(source, target) }
                                            val up = prepared?.let { editorUploads.upload(it.full, "image/jpeg", null, null) }
                                            if (prepared == null || up == null) w
                                            else io.tima.domain.chat.ComposedWidget.Image(io.tima.domain.chat.OutMedia(up.first, up.second, "image/jpeg", prepared.width, prepared.height, prepared.full.size.toLong()))
                                        },
                                    )
                                },
                            )
                        },
                    )
                    when (val result = store.sendComposed(fitted)) {
                        is io.tima.domain.chat.ComposedResult.Queued, is io.tima.domain.chat.ComposedResult.ContinuationFirst ->
                            Journal.note(LogCode.EDITOR, "отправлено", *composedFacts(fitted), "продолжение" to (result is io.tima.domain.chat.ComposedResult.ContinuationFirst))
                        is io.tima.domain.chat.ComposedResult.Invalid ->
                            Journal.trouble(LogCode.EDITOR_REFUSED, "не отправлено", "правила" to result.problems.size, *composedFacts(fitted))
                        is io.tima.domain.chat.ComposedResult.TableTooWide ->
                            Journal.trouble(LogCode.EDITOR_REFUSED, "не отправлено", "таблица" to "больше ${result.maxColumns} столбцов")
                        else -> Unit
                    }
                }
            },
            notice = editorNotice,
            desktop = desktop,
            // Перетащили файлы (ПК): картинка — в сообщение; прочее — отдельным сообщением вне пузыря (Решения §2).
            onFiles = { files ->
                for (f in files) {
                    when {
                        f.mime.startsWith("image/") -> insertPicture(io.tima.core.media.PickedImage(f.bytes, f.mime))
                        group -> editorNotice = ew.filesByLink
                        else -> { store.sendFile(f.bytes, f.mime, f.name); editorNotice = ew.fileSentApart(f.name) }
                    }
                }
            },
            // Раздел «Вставка» (Решения §10.4): виджет встаёт после куска, где курсор.
            onButton = { key ->
                editorNotice = null
                when (key) {
                    "picture" -> { editorPicture(); true }
                    "link", "cloud" -> { editorAsk = key; true }
                    "place" -> { editorPlace?.invoke() ?: run { editorAsk = "place" }; true }
                    "contact" -> { editorContact?.invoke() ?: run { editorAsk = "contact" }; true }
                    "reply", "picks", "underlay" -> { editorChoice = key; true }
                    // Видео, звук и метки по ссылке (ПЛАН-(ВМ) §3.6–§3.8).
                    "video", "audio", "stream" -> { editorMedia = key; true }
                    "marks" -> { editorMarks = null to 0; true }
                    else -> false
                }
            },
        )
        }
        editorAsk?.let { ask ->
            val settingsNow by environment.settings.all().collectAsState(initial = emptyMap())
            EditorAskSheet(
                ask = ask,
                scope = scope,
                cards = linkCards,
                cardsOn = settingsNow[LINK_CARDS_KEY] != "0",
                linkColor = settingsNow[LINK_COLOR_KEY].orEmpty(),
                onLinkColor = { c -> scope.launch { environment.settings.put(LINK_COLOR_KEY, c) } },
                cardImage = { bytes ->
                    val prepared = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { io.tima.core.media.PhotoPrep.inline(bytes) }
                    val up = prepared?.let { editorUploads.upload(it.full, "image/jpeg", null, null) }
                    if (prepared != null && up != null) {
                        io.tima.domain.chat.OutMedia(up.first, up.second, "image/jpeg", prepared.width, prepared.height, prepared.full.size.toLong())
                    } else null
                },
                put = ::put,
                onNotice = { editorNotice = it },
                onClose = { editorAsk = null },
                doc = editorModel.doc,
                // «Облако» видео или аудио → метки без числа: одна пустая строка (заказчик 2026-10-24).
                onMarks = { bind -> editorMarks = bind to 1 },
            )
        }
        editorMedia?.let { kind ->
            io.tima.feature.chat.MediaWidgetAsk(
                kind = kind,
                doc = editorModel.doc,
                ports = io.tima.feature.chat.MediaAskPorts(
                    probe = { url -> probeStream(linkCards, url) },
                    pickPicture = { done -> editorVideoPicture = done; editorPictureForVideo() },
                ),
                onInsert = { widgets -> widgets.forEach(::put) },
                onMarks = { bind, n -> editorMedia = null; editorMarks = bind to n },
                onClose = { editorMedia = null },
            )
        }
        editorMarks?.let { (bind, n) ->
            io.tima.feature.chat.MarksAsk(
                doc = editorModel.doc,
                bind = bind,
                count = n,
                onSave = { marks ->
                    // Метки к этому видео уже есть — правятся на месте; к одному видео одни (Р11).
                    val swapped = io.tima.domain.chat.ComposedMedia.replace(editorModel.doc, { it is io.tima.domain.chat.ComposedWidget.Marks && it.bind == marks.bind }, marks)
                    if (swapped != null) editorModel.layout { _, s -> swapped to s } else put(marks)
                },
                // «Удалить» — метки к этому видео убираются; их не было — ничего (заказчик 2026-10-24).
                onDelete = { b ->
                    val left = io.tima.domain.chat.ComposedMedia.removeMarks(editorModel.doc, b)
                    if (left != editorModel.doc) editorModel.layout { _, s -> left to (if (s.item < left.items.size) s else io.tima.domain.chat.Spot((left.items.size - 1).coerceAtLeast(0))) }
                },
                onClose = { editorMarks = null },
            )
        }
        when (editorChoice) {
            "reply" -> io.tima.feature.chat.ChoiceSheet(
                place = io.tima.core.ui.TextPlace.MESSAGES,
                title = ew.replyTitle,
                items = replyLines.map { line ->
                    io.tima.feature.chat.Choice(
                        title = line.senderId?.let { state.names[it]?.name } ?: name.orEmpty(),
                        about = line.text.orEmpty(),
                    )
                },
                empty = ew.replyEmpty,
                onCancel = { editorChoice = null },
                onPick = { i ->
                    val line = replyLines[i]
                    editorChoice = null
                    put(
                        io.tima.domain.chat.ComposedWidget.Reply(
                            io.tima.domain.chat.ReplyTarget(if (group) "group" else "personal", chatId, line.serverId, line.senderId ?: peerUser.orEmpty()),
                        ),
                    )
                },
            )
            // Подкладка (Решения §11.3): из подборки — ссылкой; в группе — ещё и новое продолжение своего уровня.
            // Подкладка (ADR-0050 §1): несколько сообщений целиком в конец, вне пузыря; «Списком» — список
            // подборок по строке; в группе — ещё и новое продолжение своего уровня.
            "underlay" -> io.tima.feature.chat.ManyChoiceSheet(
                place = io.tima.core.ui.TextPlace.MESSAGES,
                title = ew.button("underlay"),
                items = pickList.map { pick ->
                    val here = if (pick.chatId == chatId) state.lines.firstOrNull { it.serverId == pick.serverId } else null
                    io.tima.feature.chat.Choice(title = here?.text ?: ew.pickElsewhere(pick.serverId))
                },
                empty = ew.picksEmpty,
                max = io.tima.domain.chat.MessagePicks.UNDERLAY_MAX,
                maxList = io.tima.domain.chat.MessagePicks.MAX,
                asList = ew.asList,
                insert = ew.insertPicked,
                picked = ew::pickedOf,
                head = if (group) io.tima.feature.chat.Choice(ew.underlayNew, ew.underlayNewAbout) else null,
                onHead = {
                    editorChoice = null
                    // Закрытая часть — уровнем выше открытого: по умолчанию «по разрешению» (3).
                    val levels = kind?.circles?.map { it.level }.orEmpty()
                    editorModel.addContinuation(if (3 in levels) 3 else levels.maxOrNull() ?: 2)
                },
                onCancel = { editorChoice = null },
                onPick = { indices, asList ->
                    editorChoice = null
                    val picked = indices.map { pickList[it] }
                    fun refKind(pick: io.tima.domain.chat.Pick) = if (pick.chatId == chatId && !group) "personal" else "group"
                    // Несколько сообщений — по одной строке у каждого; одно — целиком (заказчик 2026-10-20).
                    val lines = if (picked.size > 1) 1 else 0
                    if (asList) {
                        // «Списком» (заказчик 2026-10-20): пункт на сообщение без заголовка — получатель
                        // покажет имя владельца или «Сообщение»; под ним сообщение. Подкладку в пункт
                        // разметка не кладёт (MarkupRules, ADR-0050) — пока в пункте подборка.
                        val items = picked.map { pick ->
                            io.tima.domain.chat.ComposedListItem(
                                1, refItemWord,
                                widgets = listOf(io.tima.domain.chat.ComposedWidget.Quoted(refKind(pick), pick.chatId, pick.serverId, author = false, lines = maxOf(lines, 1))),
                            )
                        }
                        editorModel.insert(io.tima.domain.chat.ComposedItem.ItemList(items))
                    } else {
                        // На строку курсора, как любой виджет (заказчик 2026-10-21); несколько — друг под другом.
                        for (pick in picked) {
                            editorModel.insert(
                                io.tima.domain.chat.ComposedItem.Widget(io.tima.domain.chat.ComposedWidget.Underlay(refKind(pick), pick.chatId, pick.serverId, lines = lines)),
                            )
                        }
                    }
                    Journal.note(LogCode.PICKS, "подкладка", "сообщений" to picked.size, "списком" to asList)
                },
            )
            // Подборка — одно или несколько сообщений (заказчик 2026-10-21: «вернуть возможность вставлять
            // несколько Подборок»); несколько встают списком — пункт «Сообщение», под ним подборка.
            "picks" -> io.tima.feature.chat.ManyChoiceSheet(
                place = io.tima.core.ui.TextPlace.MESSAGES,
                title = ew.picksTitle,
                items = pickList.map { pick ->
                    val here = if (pick.chatId == chatId) state.lines.firstOrNull { it.serverId == pick.serverId } else null
                    io.tima.feature.chat.Choice(title = here?.text ?: ew.pickElsewhere(pick.serverId))
                },
                empty = ew.picksEmpty,
                max = io.tima.domain.chat.MessagePicks.MAX,
                maxList = io.tima.domain.chat.MessagePicks.MAX,
                asList = "",
                insert = ew.insertPicked,
                picked = ew::pickedOf,
                onCancel = { editorChoice = null },
                onPick = { indices, _ ->
                    editorChoice = null
                    // Вид ссылки — по переписке, где сообщение лежит: своя группа — `group`, иначе личная.
                    fun quotedOf(pick: io.tima.domain.chat.Pick): io.tima.domain.chat.ComposedWidget.Quoted {
                        val kind = if (pick.chatId == chatId && group) "group" else if (pick.chatId == chatId) "personal" else "group"
                        return io.tima.domain.chat.ComposedWidget.Quoted(kind, pick.chatId, pick.serverId)
                    }
                    val picked = indices.map { pickList[it] }
                    if (picked.size == 1) {
                        put(quotedOf(picked.single()))
                    } else {
                        val items = picked.map { pick ->
                            io.tima.domain.chat.ComposedListItem(1, refItemWord, widgets = listOf(quotedOf(pick).copy(author = false, lines = 1)))
                        }
                        editorModel.insert(io.tima.domain.chat.ComposedItem.ItemList(items))
                    }
                    Journal.note(LogCode.PICKS, "вставлено", "сообщений" to picked.size, "уровень" to io.tima.domain.chat.MessagePicks.levelOf(chatId, group, community).substringBefore(':'), "в подборке" to pickList.size)
                },
            )
        }
    }
    if (linkAsk) {
        val settingsNow by environment.settings.all().collectAsState(initial = emptyMap())
        val linkUploads = remember { SealedUploads({ network.media }) }
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
                val up = prepared?.let { linkUploads.upload(it.full, "image/jpeg", null, null) }
                if (prepared != null && up != null) {
                    io.tima.domain.chat.OutMedia(up.first, up.second, "image/jpeg", prepared.width, prepared.height, prepared.full.size.toLong())
                } else null
            },
            put = { widget ->
                store.sendWidget(widget)
                Journal.note(LogCode.EDITOR, "ссылка из скрепки", "вид" to ((widget as? io.tima.domain.chat.ComposedWidget.Link)?.mode ?: "?"))
            },
            onNotice = { it?.let(store::said) },
            onClose = { linkAsk = false },
        )
    }
    asking?.let { ask ->
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
            trouble = askTrouble,
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
            onCancel = { asking = null; askTrouble = null },
            onDone = { values ->
                when (ask) {
                    is AttachAsk.Cloud -> { store.sendLink(values[0], cloudKind = ask.kind); asking = null }
                    // Отдельным сообщением: видео и звук, что играют у нас, — виджетом, без меток (2а#).
                    is AttachAsk.FromCloud -> if (values[0].isNotBlank()) {
                        store.sendWidget(io.tima.feature.chat.cloudAttachWidget(values[0], values.getOrElse(1) { "" }, ask.kind))
                        asking = null
                    }
                    AttachAsk.Place -> {
                        val point = io.tima.core.media.PlaceText.parse(values[0])
                        if (point == null) askTrouble = w.placeAsk else { store.sendPlace(point.first, point.second); asking = null; askTrouble = null }
                    }
                    AttachAsk.Contact -> { store.sendContact(values[0], values[1]); asking = null }
                }
            },
        )
    }
    state.peek?.let { found ->
        val other = (found as? io.tima.domain.chat.FoundMessage.Found)?.line?.chatId?.takeIf { it != chatId }
        io.tima.feature.chat.PeekSheet(
            found = found,
            onClose = store::peekClosed,
            onOpenChat = if (other != null && onOpenChat != null) { { store.peekClosed(); onOpenChat(other) } } else null,
            author = (found as? io.tima.domain.chat.FoundMessage.Found)?.line?.senderId?.let { state.names[it]?.line(authorLook) },
        )
    }
    menuFor?.let { line ->
        val text = line.text
        val close = { menuFor = null }
        MessageMenuSheet(
            text = text?.take(160),
            own = line.outgoing,
            onClose = close,
            // Ответ — блок первым в разметке тела: ссылка зашифрована вместе с текстом.
            onReply = { store.replyStarted(line); close() },
            onEdit = if (line.edits < io.tima.domain.chat.EditMessage.MAX_EDITS) {
                { store.editStarted(line); close() }
            } else {
                null
            },
            onDeleteForMe = { store.deleteForMe(line); close() },
            onDeleteForAll = { store.deleteForAllAsked(line); close() },
            // Наружу — текст без знаков (Решения §11.5). Нечем поделиться (ПК) — копия в буфер.
            onShare = text?.let { t ->
                {
                    if (!shareOut.share(t)) {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(t))
                        store.said(copiedWord)
                    }
                    close()
                }
            },
            // «Скопировать текст» — окно: выделить часть или скопировать всё (заказчик 2026-10-19:
            // прежнее долгое нажатие переехало сюда).
            // У файлов и фото — «Сохранить как» вместо «Скопировать текст» (заказчик 2026-10-22).
            onCopy = text?.takeIf { filesOf(line).isEmpty() }?.let { { selectFor = line; close() } },
            onSave = filesOf(line).takeIf { it.isNotEmpty() }?.let { files -> { saveFiles(files); close() } },
            onPick = {
                store.picked(line)
                Journal.note(LogCode.PICKS, "добавить", "уровень" to io.tima.domain.chat.MessagePicks.levelOf(chatId, group, community).substringBefore(':'))
                close()
            },
            // Перенести себе — из группы и только выносимые круги (К2): личная переписка шифр.
            onCarry = if (group && CarryToPage.canCarry(line.level)) {
                { onCarry(line.serverId, line.level); close() }
            } else {
                null
            },
            // Закрепить и открепить (ПЛАН-(ЗО) ЗО3): у кого право и у отправленного.
            onPin = if (pins != null && state.pinMay && line.serverId > 0) {
                { if (state.pinned == line.serverId) store.unpin() else store.pin(line); close() }
            } else {
                null
            },
            pinned = state.pinned == line.serverId,
        )
    }
    if (chatMenu && group && onMoveToShelf != null) {
        ChatMenuSheet(
            sections = shelves,
            currentSection = currentShelf,
            onMoveTo = { sectionId -> onMoveToShelf(chatId, sectionId) },
            onClose = { chatMenu = false },
            circlesShown = state.showCircles,
            onCircles = store::circlesShown,
            onMembers = onMembers,
            onMyColor = if (onMyColor != null) { { myColor = true } } else null,
            onGroupCall = onGroupCall,
            notifyOff = liveStates?.let { "group:$chatId" in mutedNow },
            onNotifyOff = liveStates?.let { l -> { off: Boolean -> l.mute("group", chatId, off) } },
        )
    }
    if (myColor && group && onMyColor != null) {
        // Занятые — буквой того, кто взял; свой номер в занятые не идёт.
        MyColorSheet(
            mine = hues[myUserId],
            taken = hues.filterKeys { it != myUserId }.entries.associate { (who, hue) ->
                hue to (state.names[who]?.letter() ?: "•")
            },
            members = maxOf(state.names.size + 1, hues.size),
            author = myName,
            letter = myName.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "+",
            onPick = { hue -> onMyColor(chatId, hue) { myColorTrouble = it } },
            onReset = { onMyColor(chatId, null) { myColorTrouble = it } },
            onClose = { myColor = false; myColorTrouble = null },
            trouble = myColorTrouble,
        )
    }
    // Личная переписка: тот же лист, набор — разделы книги, без доступности и участников.
    if (chatMenu && !group && onMoveToBookSection != null) {
        ChatMenuSheet(
            sections = bookSections,
            currentSection = currentBookSection,
            onMoveTo = { sectionId -> onMoveToBookSection(chatId, sectionId) },
            onClose = { chatMenu = false },
            onVideoCall = onVideoCall,
            onGroupCall = onGroupCall,
            group = false,
            notifyOff = liveStates?.let { "chat:$chatId" in mutedNow },
            onNotifyOff = liveStates?.let { l -> { off: Boolean -> l.mute("chat", chatId, off) } },
        )
    }
}

/**
 * Реплика переписки глазами подокна разговора.
 *
 * Ветка в группе и комментарии канала показываются одним подокном (ADR-0024): механизм
 * один, а слова на экране разные. Отсюда и перевод — не потому, что типы «похожи», а
 * потому что это одно и то же: сообщение, у которого назван корень.
 *
 * Текста нет — значит не расшифровалось. Строка всё равно остаётся: человек должен видеть,
 * что ответ был, иначе он ждёт продолжения разговора, которого, по его сведениям, нет.
 */
internal fun ChatLine.asComment(root: Long) = CommentEntry(
    postId = serverId,
    authorId = senderId.orEmpty(),
    text = text ?: "Сообщение не читается",
    atMs = atMs,
    parentPostId = root,
)

/**
 * Новая группа: подокно создания.
 *
 * Собирается здесь, потому что случай использования требует троих сразу — групп на
 * сервере, справочника и книги переписок. Домен их объявляет, а сводит приложение.
 */
@Composable
internal fun NewGroup(
    environment: Environment,
    network: GroupPorts,
    /**
     * Каналы и сообщества мастера. Отдельным параметром, а не через [network]: тот про
     * группы, а мастер создаёт три разные вещи, и складывать всё в один порт значило бы
     * связать группы с сообществами без нужды.
     */
    social: ChatPorts,
    scope: kotlinx.coroutines.CoroutineScope,
    onBack: () -> Unit,
    onCreated: (String, String) -> Unit,
    /** Выпуск первого ключа при рождении группы. */
    rotator: GroupKeyRotator? = null,
) {
    val store = remember {
        NewGroupStore(
            creation = CreateGroupChat(
                groups = GroupsOverHttp(network.groups),
                directory = network.directory,
                chats = SqlChatBook(environment.db, environment.cipher),
                // Первый ключ — при рождении группы, как в v1. Без этого группа немая:
                // отправка отвечает NoKey, а просить ключ не у кого (2026-09-16…18).
                rotator = rotator,
            ),
            scope = scope,
            // Раздел при создании (Р5): набор сообществ и куда положить созданное.
            shelves = environment.communitySections,
            chatSections = environment.communitySections,
            // Канал и сообщество перестали быть серыми: у мастера есть чем их выполнить.
            // Звуковой чат остаётся серым — он ждёт реализации (решение заказчика
            // 2026-09-08), и признак «готов» считается по наличию случая, а не по флагу.
            channels = CreateChannel(social.channels),
            communities = CreateCommunity(social.communities),
            linkable = { social.communities.linkable() },
        )
    }
    val state by store.state.collectAsState()

    // Группа создана — открываем её. Оставаться на экране создания нечем: он своё сделал,
    // а человек ждёт переписку, а не подтверждение.
    LaunchedEffect(state.created) {
        state.created?.let { groupId ->
            // Непозванные показываются на самом экране; если они есть, переход не спешим
            // делать — иначе список номеров мелькнёт и исчезнет.
            if (state.notInvited.isEmpty()) {
                onCreated(groupId, state.title)
                store.reset()
            }
        }
    }

    NewGroupScreen(
        state = state,
        onSection = store::choseSection,
        onKind = store::choseKind,
        onJoining = store::choseJoining,
        onForward = store::forward,
        onExplain = store::explain,
        onTitle = store::changedTitle,
        onDescription = store::changedDescription,
        onNumber = store::changedNumber,
        onCountryCode = store::changedCountryCode,
        onAddNumber = store::addNumber,
        onRemoveNumber = store::removeNumber,
        onCreate = store::create,
        onShelf = store::choseShelf,
        onNewShelf = store::changedNewShelf,
        onCreateShelf = store::createShelf,
        // Уйти с экрана, когда он задержался ради непозванных: без этой кнопки уйти было
        // нечем, а прежняя «Создать» заводила ещё одну группу.
        onOpenCreated = {
            state.created?.let { groupId ->
                onCreated(groupId, state.title)
                store.reset()
            }
        },
        ready = store::ready,
        onCatalogue = store::choseCatalogue,
        onComments = store::choseComments,
        onItem = store::choseItem,
        // «Назад» с первого шага закрывает мастер, с остальных — шаг назад: человек,
        // ошибшийся на третьем шаге, не должен начинать заново.
        onBack = {
            if (state.step == Step.Section) {
                onBack()
                store.reset()
            } else {
                store.back()
            }
        },
    )
}

/**
 * Состав группы: подокно участников.
 *
 * Ротация ключа при смене состава собирается здесь же — ей нужны escrow, крипта, сеть и
 * хранилище разом, то есть ровно то, чего домен не видит.
 */
@Composable
internal fun Members(
    environment: Environment,
    network: GroupPorts,
    session: Session,
    groupId: String,
    scope: kotlinx.coroutines.CoroutineScope,
    onBack: () -> Unit,
    /** Открыть подокно «Доступ»: просьбы и выдача третьего круга. */
    onAccess: () -> Unit = {},
    /** Вид группы из базы устройства: открытой смена состава ключа не трогает. */
    groupKind: GroupKind? = null,
    people: ChatPeople? = null,
    look: PersonLook = PersonLook.DEFAULT,
    contacts: List<InviteCandidate>? = null,
) {
    val kindNow by rememberUpdatedState(groupKind)
    val store = remember(groupId) {
        MembersStore(
            members = ManageGroupMembers(
                groups = GroupsOverHttp(network.groups),
                directory = network.directory,
                nicknames = network.directory,
                rotator = GroupKeyRotation(
                    groups = network.groups,
                    deviceKeys = network.keys,
                    escrow = network.escrow,
                    groupKeys = network.groupKeys,
                    book = SqlGroupKeys(environment.db, environment.cipher, onPut = { g, v, k -> environment.onGroupKeyStored?.invoke(g, v, k) }),
                    msNow = ::msNow,
                    // Ключ группы — только доверенным устройствам (ДУ3): без этого смена
                    // состава из «Участников» обходила проверку.
                    trust = environment.trustGate,
                ),
                kind = { kindNow },
            ),
            groupId = groupId,
            myUserId = session.userId,
            scope = scope,
            people = people,
        )
    }
    val state by store.state.collectAsState()

    // Состав спрашивается при открытии: он меняется чужими руками, и показывать
    // вчерашний список значит показывать неправду.
    LaunchedEffect(groupId) { store.refresh() }

    // Заявки новых личностей (ДУ6, Р9): видят и решают владелец и модераторы; остальным
    // сервер их не отдаёт, и блока нет.
    var claims by remember(groupId) { mutableStateOf<List<io.tima.feature.group.IdentityClaimLine>>(emptyList()) }
    var claimsRev by remember(groupId) { mutableStateOf(0) }
    LaunchedEffect(groupId, claimsRev) {
        val raw = network.groups.identityClaims(groupId).orEmpty()
        suspend fun name(id: String): String =
            people?.person(id)?.let { io.tima.domain.chat.selfName(it.userName) ?: it.name ?: it.nick?.let { n -> "@$n" } } ?: ("…" + id.takeLast(6))
        claims = raw.map { c -> io.tima.feature.group.IdentityClaimLine(c.userId, name(c.userId), name(c.fromUserId)) }
    }

    MemberScreen(
        state = state,
        onNumber = store::changedNumber,
        onCountryCode = store::changedCountryCode,
        onInvite = store::invite,
        onRemove = store::remove,
        onBack = onBack,
        onAccess = onAccess,
        look = look,
        onNick = store::changedNick,
        onInviteNick = store::inviteByNick,
        contacts = contacts,
        onContacts = store::contactsSheet,
        onInviteUser = store::inviteUser,
        claims = claims,
        onConfirmClaim = { userId ->
            scope.launch {
                if (network.groups.confirmIdentityClaim(groupId, userId)) {
                    claimsRev++
                    store.refresh()
                }
            }
        },
    )
}

/**
 * Сводные счётчики непрочитанного по окнам.
 *
 * Число сегодня одно и настоящее — непрочитанные сообщения окна 1. У остальных окон
 * его нет, и подставлять туда ноль было бы не честнее: ноль означает «прочитано всё»,
 * а правда в том, что считать нечего — социального слоя на сервере нет.
 */
/**
 * Отметка «просмотрено до» — время последнего входящего в открытой переписке (ЖУ9). Только
 * в базу устройства: в копию аккаунта она уйдёт одной отправкой в конце сессии.
 */
internal fun seenUpTo(environment: Environment, chatId: String) {
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
        runCatching {
            val upto = environment.readState.lastIncomingTs(chatId)
            if (upto > 0) environment.readState.markLocal(chatId, upto)
        }
    }
}

/**
 * Повтор и удаление отказанного — поверх очереди. Журнал получает пару к `QUEUE-REFUSED`:
 * что сделал человек, с какой прежней причиной и каким новым кругом.
 */
internal fun deadMessages(environment: Environment): DeadMessages = object : DeadMessages {
    override suspend fun retry(dedupKey: String, level: Int): Boolean {
        val before = environment.queue.entry(dedupKey)
        val done = environment.queue.retryDead(dedupKey, level)
        Journal.note(
            LogCode.QUEUE_RETRY, "человек повторил отказанное",
            "действие" to "повтор", "причина" to (before?.failReason ?: "не сохранена"),
            "круг" to level, "переписка" to (before?.chatId ?: "?"), "вышло" to done,
        )
        return done
    }

    override suspend fun delete(dedupKey: String): Boolean {
        val before = environment.queue.entry(dedupKey)
        val done = environment.queue.deleteUnsent(dedupKey)
        Journal.note(
            LogCode.QUEUE_RETRY, "человек убрал неотправленное",
            "действие" to "удалить", "состояние" to (before?.state?.name ?: "?"),
            "причина" to (before?.failReason ?: "не сохранена"),
            "переписка" to (before?.chatId ?: "?"), "вышло" to done,
        )
        return done
    }
}

/** Что спросить у человека, прежде чем отправить вложение (ВЫБОР-РЕАЛИЗАЦИИ §11.2а). */
internal sealed interface AttachAsk {
    /** Файл в группе — ссылкой на облако; [kind]: `file`, `document`, `audio`, `video`. */
    data class Cloud(val kind: String) : AttachAsk

    /** Раздел скрепки «Из облака» (заказчик 2026-10-24): ссылка и название; [kind] — `file`, `video`, `audio`. */
    data class FromCloud(val kind: String) : AttachAsk
    data object Place : AttachAsk
    data object Contact : AttachAsk
}

/** Что собрано в редакторе — числами, без текста (ПЛАН-(ЖС) §4): рядов, ячеек, виджетов по видам. */
internal fun composedFacts(doc: io.tima.domain.chat.ComposedDoc): Array<Pair<String, Any?>> {
    val rows = doc.items.filterIsInstance<io.tima.domain.chat.ComposedItem.Row>()
    val widgets = doc.items.filterIsInstance<io.tima.domain.chat.ComposedItem.Widget>().map { it.widget } + rows.flatMap { r -> r.cells.flatMap { it.widgets } }
    val kinds = widgets.groupingBy { it::class.simpleName.orEmpty().lowercase() }.eachCount().entries.joinToString(",") { "${it.key}:${it.value}" }
    return arrayOf("рядов" to doc.rows, "ячеек" to rows.sumOf { it.cells.size }, "виджеты" to kinds.ifEmpty { "нет" })
}

/** Изменённое в просмотре фото — JPEG для «Сохранить как», «Переслать», «Поделиться». */
internal fun jpegOf(image: androidx.compose.ui.graphics.ImageBitmap): ByteArray = io.tima.core.media.PhotoPrep.fit(image).full

/** Почему правку фото уже не отправить (заказчик 2026-10-22: «нажали — сообщение почему»). */
internal enum class PhotoRefusal { Group, NotMine, NotSent, Limit, KeyChanged }

/**
 * Можно ли досылать правку фото (ADR-0045 §5, ADR-0052): своё, отправлено, правок меньше трёх и тот же
 * месяц UTC, что у исходного, — в личной переписке ключ меняется с месяцем, и правку после смены
 * сервер не примет. Проверка здесь, а не только у сервера: после отказа прежний файл уже убран бы.
 * `null` — можно.
 */
internal fun photoRefusal(line: ChatLine, edit: io.tima.domain.chat.EditMessage, chatId: String, now: Long = msNow()): PhotoRefusal? {
    if (!line.outgoing) return PhotoRefusal.NotMine
    if (line.serverId <= 0 || line.atMs <= 0) return PhotoRefusal.NotSent
    fun month(ms: Long) = kotlinx.datetime.Instant.fromEpochMilliseconds(ms).toLocalDateTime(kotlinx.datetime.TimeZone.UTC).let { it.year * 12 + it.monthNumber }
    if (month(line.atMs) != month(now)) return PhotoRefusal.KeyChanged
    return when (edit.refusal(chatId, line.serverId)) {
        null -> null
        io.tima.domain.chat.EditResult.Limit -> PhotoRefusal.Limit
        io.tima.domain.chat.EditResult.NotMine -> PhotoRefusal.NotMine
        else -> PhotoRefusal.NotSent
    }
}

/** Одно фото перед отправкой: исходные байты и уменьшенная копия для редактора. */
internal class SendDraft(val bytes: ByteArray, val mime: String, val preview: androidx.compose.ui.graphics.ImageBitmap)

/**
 * Свести правку одного фото на исходнике (ADR-0052, заказчик 2026-10-22: «делаем обработку в редакторе…
 * а потом при нажатии кнопки масштабируем до 1280»). Исходник читается заново таким, чтобы видимое после
 * рамки было не меньше [side], но не больше 4096 по стороне — потолок памяти; не прочиталось или не
 * хватило памяти — сводится на копии редактора.
 */
internal fun madePhoto(
    draft: SendDraft,
    marks: io.tima.core.media.PhotoMarks,
    side: Int,
    measurer: androidx.compose.ui.text.TextMeasurer? = null,
): io.tima.core.media.PhotoPrep.Prepared? {
    val preview = draft.preview
    val visible = io.tima.core.media.PhotoLayers.outSize(preview.width, preview.height, marks)
    val need = side.toFloat() / maxOf(visible.width, visible.height)
    val source = if (need <= 1f) {
        preview
    } else {
        val atLeast = kotlin.math.ceil(minOf(preview.width, preview.height) * need).toInt()
        runCatching { io.tima.core.media.decodeImage(draft.bytes, atLeast = atLeast, maxSide = MADE_MAX_SIDE) }.getOrNull() ?: preview
    }
    val made = runCatching { io.tima.core.media.PhotoLayers.render(source, marks, measurer) }.getOrNull()
        ?: runCatching { io.tima.core.media.PhotoLayers.render(preview, marks, measurer) }.getOrNull()
        ?: return null
    Journal.note(
        LogCode.FILE_SEND, "одно фото через редактор — сведено",
        "исходник" to "${source.width}x${source.height}", "итог" to "${made.width}x${made.height}",
    )
    return io.tima.core.media.PhotoPrep.fit(made, side)
}

/** Файлы и фото сообщения вне пузыря — то, что сохраняется «Сохранить как». */
internal fun filesOf(line: ChatLine): List<io.tima.domain.chat.RichMedia> =
    line.rich?.takeIf { !line.deleted }?.let { PostmanFiles.outsideOf(it) }.orEmpty()

/** Имя сохраняемого: своё, у фото — «photo.jpg», в группе — с номером. */
internal fun savedName(m: io.tima.domain.chat.RichMedia, n: Int): String = m.name.ifBlank {
    val ext = if (m.mime.startsWith("image/")) "jpg" else "bin"
    if (n > 0) "photo-$n.$ext" else "photo.$ext"
}


/**
 * Сборка store переписки со всеми его портами (ПЛАН-(РФ) РФ3): что откуда берётся — в одном месте, а не посреди
 * экрана. Значения приходят параметрами — те же, что `Chat()` получает сама; поведение прежнее.
 */
internal object ChatAssembly {
    fun store(
        chatId: String,
        group: Boolean,
        environment: Environment,
        network: ChatPorts,
        scope: kotlinx.coroutines.CoroutineScope,
        myDeviceId: String,
        myUserId: String,
        peerUser: String?,
        community: String?,
        reopenUnreadable: (suspend () -> Int)?,
        heal: HealGroupKey?,
        people: People?,
        postman: PostmanFiles?,
        openByPhrase: (suspend (String, List<String>, Boolean) -> io.tima.domain.chat.PhraseOpen)?,
        fromPhoneMay: Boolean,
        pins: io.tima.domain.chat.ChatPins?,
        pinMay: Boolean,
        setSuper: (suspend (Long) -> Boolean)?,
        finder: io.tima.domain.chat.MessageFinder?,
    ): ChatStore =
        ChatStore(
            chatId = chatId,
            observe = environment.chat,
            send = environment.send,
            scope = scope,
            // Где остановились — у каждой переписки своё (ПЛАН-(МП) М2).
            places = ChatPlacesInSettings(environment.settings),
            markRead = environment.reading,
            seenAtStart = false,
            // Запрос недостающего ключа и имена авторов — только у группы: у личной
            // переписки просить не у кого, а собеседник назван в шапке.
            requestKeys = if (group) {
                // Просьба подписывается фразой (Р42): без подписывающего она уходила неподписанной и
                // получала 403 даже с введённой фразой (живая проверка 2026-10-06, п. 1.7).
                RequestGroupKeys(
                    // «Недостающих нет» — дело в ключе подписи автора: повтор сходит за ним (КК2).
                    reopen = { reopenUnreadable?.invoke() },
                    recovery = GroupKeyRecoveryOverHttp(network.keyRecovery) { gid, words ->
                        io.tima.core.encryption.RecoverySignature.sign(words, gid, myDeviceId)
                    },
                )
            } else {
                null
            },
            // Лечение группы без ключа при открытии: обёртки, а если ключа не было ни у
            // кого — первый выпуск. Стенд 2026-09-16…18: группы рождались без ключа.
            heal = if (group) heal else null,
            names = if (group) people else null,
            // Аватар автора: медиа из справочника, байты из медиа-хранилища. Только в
            // группе — в личной переписке подписи у реплик нет вовсе.
            faces = if (group) people else null,
            // Повтор и удаление отказанного — поверх очереди, с журналом «отказ → повтор».
            dead = deadMessages(environment),
            // Сужение — только в группе: у личного сообщения круга нет, оно зашифровано и
            // адресовано одному человеку.
            narrow = if (group) NarrowMessageLevel(network.messageLevels) else null,
            // Ключей нет вовсе — значит группа молчит целиком, и сказать об этом надо
            // прямо. Спрашивается у книги ключей, а не у сети: ответ нужен и офлайн.
            anyKey = if (group) {
                { environment.groupKeyBook.latestVersion(chatId) != null }
            } else {
                null
            },
            // Личная переписка: недоступное сообщение — просьба ключей у своих устройств и
            // собеседника (заказчик 2026-10-06). Ответ приходит событием `recovery.msg_ready`,
            // и история догоняется сама. Незаверенное устройство подписывает просьбу фразой.
            askChatKeys = if (!group) {
                { words, named ->
                    val signature = words?.let { io.tima.core.encryption.RecoverySignature.sign(it, chatId, myDeviceId) }
                    when (val r = network.history.recover(chatId, signature, named)) {
                        is io.tima.core.network.HistoryApi.Recover.Asked -> {
                            Journal.note(
                                LogCode.DEVICE_TRUST, "ключи переписки запрошены", "переписка" to chatId.take(8),
                                "названо" to named.size, "вернут" to r.missing, "уже есть" to r.ready,
                                "потеряно" to r.lost.size, "помощников" to r.helpers, "по фразе" to (signature != null),
                            )
                            // Потерянное запоминается: ключа к нему нет ни у кого, и просить его
                            // снова — будить чужие устройства зря.
                            if (r.lost.isNotEmpty()) {
                                val key = CHAT_KEYS_LOST + chatId
                                runCatching {
                                    val before = environment.settings.all().first()[key].orEmpty()
                                        .split(',').mapNotNull { it.toLongOrNull() }.toSet()
                                    environment.settings.put(key, (before + r.lost).sortedDescending().joinToString(","))
                                }
                            }
                            // Ключ сообщения у устройства уже есть — дело не в нём: разобрать заново,
                            // подтянув ключи подписи отправителей.
                            if (r.ready > 0) runCatching { reopenUnreadable?.invoke() }
                            when {
                                r.missing > 0 && r.helpers > 0 -> io.tima.domain.chat.RequestKeysStep.Asked(r.helpers)
                                r.missing > 0 -> io.tima.domain.chat.RequestKeysStep.NoHelpers
                                r.lost.isNotEmpty() && r.ready == 0 -> io.tima.domain.chat.RequestKeysStep.Lost(r.lost.size)
                                else -> io.tima.domain.chat.RequestKeysStep.NothingMissing
                            }
                        }
                        // Устройство не заверено — просьбу подписывает фраза; опечатка в фразе
                        // приходит тем же отказом.
                        is io.tima.core.network.HistoryApi.Recover.Refused ->
                            if (r.code == "bad_identity_sig" || r.code == "phrase_required") {
                                Journal.note(LogCode.DEVICE_TRUST, "ключи переписки — нужна фраза", "переписка" to chatId.take(8), "с фразой" to (words != null))
                                io.tima.domain.chat.RequestKeysStep.NeedsSecretPhrase
                            } else {
                                Journal.trouble(LogCode.DEVICE_TRUST, "просьба о ключах переписки отклонена", "код" to r.code)
                                io.tima.domain.chat.RequestKeysStep.Refused(r.code)
                            }
                        io.tima.core.network.HistoryApi.Recover.Offline -> io.tima.domain.chat.RequestKeysStep.Offline(0)
                    }
                }
            } else {
                null
            },
            lostKeys = if (!group) {
                {
                    runCatching { environment.settings.all().first()[CHAT_KEYS_LOST + chatId] }.getOrNull().orEmpty()
                        .split(',').mapNotNull { it.toLongOrNull() }.toSet()
                }
            } else {
                null
            },
            openByPhrase = openByPhrase?.let { open -> { words -> open(chatId, words, group) } },
            fromPhoneMay = fromPhoneMay,
            // Сами — один раз на переписку в сутки, в личной и в группе: каждое открытие с
            // недоступным сообщением будило бы чужие устройства заново.
            autoAskAllowed = {
                val key = CHAT_KEYS_ASKED + chatId
                val last = runCatching { environment.settings.all().first()[key]?.toLongOrNull() }.getOrNull() ?: 0L
                val now = msNow()
                (now - last >= CHAT_KEYS_AGAIN_MS).also { if (it) runCatching { environment.settings.put(key, now.toString()) } }
            },
            // Меню сообщения (Решения §11.1): правка, удаление у себя и у всех, подборка.
            edit = environment.edit,
            deleter = io.tima.domain.chat.DeleteMessage(environment.removal, network.removal),
            picks = environment.picks,
            pickLevel = io.tima.domain.chat.MessagePicks.levelOf(chatId, group, community),
            drafts = LoggedDrafts(environment.drafts),
            // Вложения (Решения §2, §3, §6): файл закрыт своим ключом до выхода с устройства.
            attach = io.tima.domain.chat.SendAttachments(
                queue = environment.queue,
                uploads = SealedUploads({ network.media }) { id, sealed, mime ->
                    postman?.keepSent(id, sealed, photo = mime.startsWith("image/"), voice = mime == io.tima.domain.chat.SendAttachments.VOICE_MIME)
                },
                bodies = io.tima.core.encryption.AttachmentBodiesOverKodium,
                keys = io.tima.core.outbox.UuidDedupKeys,
                // Правила приёма — от сервера, не чаще раза в сутки (ПЛАН-(ИФ) ИФ2).
                source = io.tima.domain.chat.CachedMediaRules(environment.settings, { network.media.rules() }, ::msNow),
            ),
            peerId = if (group) null else (peerUser ?: myUserId.takeIf { it.isNotEmpty() }),
            selfChat = !group && (peerUser == null || peerUser == myUserId),
            // Редактор (Решения §10): документ собирается тем же конвейером, что и набранное.
            composer = io.tima.domain.chat.SendComposed(
                queue = environment.queue,
                bodies = io.tima.core.encryption.ComposedBodiesOverKodium,
                keys = io.tima.core.outbox.UuidDedupKeys,
                // Продолжение своего уровня — в группе: в личной один уровень (Решения §11.3).
                continuations = if (group) environment.continuations else null,
            ),
            pins = pins,
            pinMay = pinMay,
            setSuper = setSuper,
            finder = finder,
        ).also { s ->
            // Недоступное по ссылке (ПЛАН-(ЖС)): причина и уровень, без номеров чужих переписок.
            s.attachLog = { reason -> Journal.note(LogCode.FILE_SEND, "отказ", "причина" to reason) }
            s.refLog = { kind, number, reason, level ->
                Journal.note(
                    LogCode.REF_UNAVAILABLE, "недоступно по ссылке", "вид" to kind, "номер" to number,
                    "причина" to reason, "уровень" to (level ?: -1),
                )
            }
            s.pinLog = { on, step ->
                val outcome = when (step) {
                    io.tima.domain.chat.PinStep.Done -> "сделано"
                    is io.tima.domain.chat.PinStep.Refused -> step.code
                    io.tima.domain.chat.PinStep.Offline -> "нет сети"
                }
                if (step == io.tima.domain.chat.PinStep.Done) {
                    Journal.note(LogCode.PIN_MSG, if (on) "закреплено" else "снято", "группа" to group)
                } else {
                    Journal.trouble(LogCode.PIN_MSG, if (on) "не закреплено" else "не снято", "группа" to group, "итог" to outcome)
                }
            }
        }
}
