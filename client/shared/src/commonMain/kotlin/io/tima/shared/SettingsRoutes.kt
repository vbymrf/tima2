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
 * Экраны настроек — список пунктов, устройства, пин-код, обновление, хранилище, отчёт о проблеме, предложение.
 * Вынесены из Root.kt (ПЛАН-(РФ) РФ4) без изменения поведения.
 */

/**
 * Настройки: подокно с тремя вкладками.
 *
 * Собирается здесь, а не в оболочке: вкладки живут в разных модулях — устройства в
 * `feature-auth`, обновление в `feature-shell`, — и свести их вправе только приложение.
 * Оболочка получает готовое содержимое слотом и по-прежнему не знает ни про один feature.
 */
@Composable
internal fun Settings(
    opened: SettingsItem?,
    onOpen: (SettingsItem?) -> Unit,
    onSignOut: () -> Unit,
    onRereg: (io.tima.domain.account.PrepareRereg.Ready) -> Unit,
    onScanCode: (() -> Unit)?,
    /** Копия аккаунта — ради «Запросить ключ» в «Секретная фраза и устройства». */
    accountCopy: BookCopySync,
    network: DevicePorts,
    scope: kotlinx.coroutines.CoroutineScope,
    platform: Platform,
    build: Build,
    appearance: Appearance,
    onAppearance: (Appearance) -> Unit,
    /** Доверие к своим устройствам (ДУ5). */
    deviceTrust: io.tima.domain.account.DeviceTrustActions? = null,
    /** Новая личность отменена — снять событие «начали заново» (ДУ6). */
    onIdentityRestored: () -> Unit = {},
    language: Language,
    onLanguage: (Language) -> Unit,
    /** Страна и отбор выдачи (ПЛАН-(Я)-ЯЗЫКА Я7): живут рядом с выбором языка. */
    locale: LocaleStore,
    onBack: () -> Unit,
    /** Профиль общий с переключением окон: один Store, два входа. */
    profile: ProfileStore,
    profileState: ProfileState,
    /** Виртуальные аккаунты: список свой, а действия уводят из настроек (Д10…Д12). */
    virtuals: VirtualsStore,
    virtualsState: VirtualsState,
    onNewVirtual: () -> Unit,
    /** `null` — принимаем чужой; иначе передаём свой. */
    onTransfer: (String?) -> Unit,
    /** Все аккаунты для «Аккаунтов» (2026-10-07): с устройства и виртуальные с сервера. */
    accountRows: List<io.tima.feature.auth.AccountRow> = emptyList(),
    /** Обновление: один магазин на приложение, здесь только его вкладка (О3, О5). */
    update: UpdateStore,
    updateState: UpdateState,
    /**
     * Испытательный стенд звонков: здесь только выключатель и имя выбранного набора.
     *
     * Передаётся целиком, а не двумя значениями: выключатель обязан **двигать** флаг, и
     * отдельная лямбда рядом с отдельным полем однажды разъехалась бы с ним.
     */
    bench: BenchStore,
    benchState: BenchState,
    /** Что уйдёт в отчёте о проблеме, кроме текста и журнала (ПЛАН-(Б)-ОТЛАДКИ.md, Б3). */
    problemFacts: ProblemFacts,
    /** Черновик отчёта о проблеме — из подокна неотправленного сообщения. */
    problemDraft: String = "",
    problemPhotos: List<io.tima.feature.shell.ProblemPhoto> = emptyList(),
    problemKind: io.tima.feature.shell.ProblemKind = io.tima.feature.shell.ProblemKind.Other,
    /** Откуда человек ушёл в настройки. `null` — попал сюда не из окна (Б2). */
    origin: Origin?,
    /** Сеть плюс очередь: отчёт не теряется, даже если связи нет. */
    reporting: Reporting,
    /** Снимок состояния — считается в момент открытия экрана отчёта. */
    snapshot: () -> Snapshot,
    /** Шаги неотправленных черновиков редактора — блоком в отчёт о проблеме (заказчик 2026-10-24). */
    draftSteps: () -> String = { "" },
    /** Где платформа держит выбранный срок хранения журнала. */
    diaryPolicy: AppearanceStore,
    /** Журнал звонков: сколько его держит телефон и сколько сейчас лежит (Ж5). */
    callsLog: CallsStore,
    callsState: CallsState,
    /** «Отключённые» уведомления (ПЛАН-(ОУ)): `(ключ, название)` и вернуть одно. */
    mutedList: List<Pair<String, String>> = emptyList(),
    onUnmute: (String) -> Unit = {},
    /** Настройки устройства — выбор звуков (ВЗ4) живёт здесь и не синхронизируется. */
    deviceSettings: io.tima.domain.chat.Settings,
    /** Своя папка медиа аккаунта — раздел «Медиа и другие файлы» (Решения §6). */
    mediaFolder: io.tima.core.media.MediaFolder = io.tima.core.media.MediaFolder.NONE,
    /** Файлы почтальона — «Качать по мобильной сети» забирает отложенное сразу. */
    postman: PostmanFiles? = null,
    /** Микрофон, колонки, камера — есть только у ПК; `null` — пункта нет. */
    callDevices: io.tima.core.call.CallDevices? = null,
    /** Своё изображение с фильтрами без звонка (ФЗ12); `null` — движок фильтров не умеет. */
    filterPreview: io.tima.core.call.FilterPreview? = null,
    /** Запуск вместе с системой — есть только у ПК; `null` — раздела в «Разрешениях» нет. */
    loginStart: LoginStart? = null,
    /** Пин-код этого аккаунта (ПЛАН-(ПН)) — значок «Пин» у текущего в «Аккаунтах» (2026-10-07). */
    pin: PinHost? = null,
) {
    // Название темы считается в составе, а не в лямбде списка: лямбда не composable.
    val themeName = Tima.words.appearance.theme(appearance.choice)
    // Где человек в «Частых вопросах» — живёт, пока открыты настройки.
    val help = remember { HelpNav() }
    val fleet = remember {
        DevicesStore(
            network.myFleet, scope, trust = deviceTrust, onIdentityRestored = onIdentityRestored,
            onRereg = onRereg, dateText = ::reregDate,
            // Фраза проверяется по словарю и сумме до сервера (отчёт DGAR).
            checkPhrase = io.tima.core.encryption.PhraseCheckOverKodium,
        )
    }
    val devices by fleet.state.collectAsState()

    SettingsScreen(
        opened = opened,
        onOpen = { onOpen(it) },
        // Выбирать устройства есть смысл только там, где их выбирает человек, — на ПК. «Звонки» — и на ПК (ФЗ17:
        // увеличение кнопками, фильтры); аппаратное кодирование там скрыто — кодеры программные.
        hidden = if (callDevices == null) setOf(SettingsItem.MEDIA) else emptySet(),
        // Из пункта — к списку, из списка — из настроек. Одно «назад» на оба шага
        // выкидывало бы наружу из глубины, то есть теряло бы место, куда человек шёл.
        //
        // **Из «Оформления» не выпускаем, пока цвета дороги назад слиты** — защита от
        // дурака, решение заказчика 2026-09-03. Момент выбран им же и выбран верно: на
        // вводе запрещать нельзя, потому что пару меняют по одному цвету и через
        // слившееся состояние приходится проходить. А вот выйти в приложение, где не
        // видно ни шапки, ни списка настроек, — это и есть «уже не вернуться».
        //
        // Молчаливого отказа не выходит: предупреждение висит на самом экране всё то
        // время, пока пара слита, и кнопка «назад» упирается в уже написанный ответ.
        onBack = {
            when {
                opened == null -> onBack()
                opened == SettingsItem.APPEARANCE && appearance.colors.merged().isNotEmpty() -> Unit
                // «Частые вопросы»: «назад» сначала шагает внутри — со страницы к списку, из раздела к разделам.
                opened == SettingsItem.QUESTIONS && help.back() -> Unit
                else -> onOpen(null)
            }
        },
        value = { item ->
            when (item) {
                // Значение справа — то, что и так посчитано для самого пункта. Отдельный
                // запрос ради строки в списке будил бы сеть на каждый заход в настройки.
                SettingsItem.DEVICES -> devices.devices.size.takeIf { it > 0 }?.toString().orEmpty()
                SettingsItem.ABOUT -> build.name
                // Тема видна, не заходя внутрь: половина заходов в настройки на этом и
                // заканчивается — человек посмотрел и вышел.
                SettingsItem.APPEARANCE -> themeName.lowercase()
                else -> ""
            }
        },
    ) { item ->
        when (item) {
            // Второй вход в профиль — тот же экран, что из переключения окон.
            // Не дубль: настройки — место, где ищут «где это поменять», не помня,
            // откуда туда попали.
            SettingsItem.PROFILE -> ProfileScreen(
                state = profileState,
                onName = profile::changedName,
                onNickname = profile::changedNickname,
                onSave = profile::save,
                onBack = { onOpen(null) },
                onAvatar = profile::croppedAvatar,
                onAvatarRemove = profile::removeAvatar,
                // Шапку рисуют настройки — «одна на подокно».
                withHeader = false,
            )

            SettingsItem.VIRTUALS -> {
                // Список спрашивается у сервера при каждом заходе: он меняется и на
                // других устройствах, а местный список показал бы вчерашнее.
                LaunchedEffect(Unit) { virtuals.refresh() }
                // Пин-код текущего аккаунта — здесь, у его строки (заказчик 2026-10-07).
                PinFlow(pin) { pinOn, onPin, pinNotice ->
                    VirtualsScreen(
                        state = virtualsState,
                        onCreate = onNewVirtual,
                        onGive = { onTransfer(it) },
                        onTake = { onTransfer(null) },
                        rows = accountRows,
                        pinOn = pinOn,
                        onPin = onPin,
                        pinNotice = pinNotice,
                    )
                }
            }

            SettingsItem.DEVICES -> Devices(fleet, devices, build.name, onSignOut, onScanCode, accountCopy)

            // Уведомления (У1, У14). Пункт стоял в списке с самого начала и не
            // открывал ничего; теперь здесь два действия, без которых уведомления на
            // Android не работают: право показывать и «не усыплять».
            // Уведомления — что показывается и звуки (ВЗ4). Разрешения переехали в свой пункт.
            SettingsItem.NOTIFICATIONS -> NotificationsScreen(
                // Звуки: общий выбор — в настройках устройства, не синхронизируется.
                ring = soundRow(deviceSettings, SoundKeys.RING, SoundUse.Ring, "ring"),
                message = soundRow(deviceSettings, SoundKeys.MESSAGE, SoundUse.Message, "message"),
                // Тихие часы (заказчик 2026-10-01) — в настройках устройства.
                quiet = run {
                    val saved by deviceSettings.all().collectAsState(emptyMap())
                    val q = QuietHours.read(saved)
                    io.tima.feature.shell.QuietRow(q.on, q.from, q.to, q.calls, q.messages) { r ->
                        scope.launch {
                            deviceSettings.put(QuietHours.KEY_ON, if (r.on) "1" else "0")
                            deviceSettings.put(QuietHours.KEY_FROM, r.from.toString())
                            deviceSettings.put(QuietHours.KEY_TO, r.to.toString())
                            deviceSettings.put(QuietHours.KEY_CALLS, if (r.calls) "1" else "0")
                            deviceSettings.put(QuietHours.KEY_MESSAGES, if (r.messages) "1" else "0")
                        }
                    }
                },
                // Экономичный режим канала (заказчик 2026-10-06): настройка устройства; смена —
                // сразу в канал, канал поднимается заново с новой перекличкой.
                economy = run {
                    val saved by deviceSettings.all().collectAsState(emptyMap())
                    val e = ChannelEconomy.read(saved)
                    io.tima.feature.shell.EconomyRow(
                        on = e.on, seconds = e.seconds,
                        min = ChannelEconomy.MIN_SECONDS, max = ChannelEconomy.MAX_SECONDS, step = ChannelEconomy.STEP_SECONDS,
                    ) { r ->
                        scope.launch {
                            deviceSettings.put(ChannelEconomy.KEY_ON, if (r.on) "1" else "0")
                            deviceSettings.put(ChannelEconomy.KEY_SECONDS, r.seconds.toString())
                            ChannelEconomy.apply(deviceSettings.all().first())
                            ChannelHost.restart()
                        }
                    }
                },
                muted = mutedList,
                onUnmute = onUnmute,
            )

            // Разрешения — одно место для всех (заказчик 2026-09-26).
            // Микрофон и камера — выбор и проверка без звонка (заказчик 2026-09-26).
            SettingsItem.MEDIA -> callDevices?.let { MediaSettings(it, deviceSettings) }

            SettingsItem.CALLS -> {
                val saved by deviceSettings.all().collectAsState(emptyMap())
                val savedFilters = io.tima.core.call.CallFilterKeys.read(saved)
                // «Фильтры» без звонка (ФЗ12): свой экран поверх пункта; назад — к «Звонкам».
                var filtersHere by remember { mutableStateOf(false) }
                val preview = filterPreview
                if (filtersHere && preview != null) {
                    var shown by remember { mutableStateOf(savedFilters) }
                    LaunchedEffect(preview) { preview.startPreview(shown) }
                    DisposableEffect(preview) { onDispose { scope.launch { preview.stopPreview() } } }
                    val video by preview.previewVideo.collectAsState()
                    val front by preview.previewFront.collectAsState()
                    val trouble by preview.previewTrouble.collectAsState()
                    io.tima.feature.call.FiltersSettingsScreen(
                        filters = shown,
                        onChange = { next, final ->
                            shown = next
                            preview.previewFilters(next)
                            if (final) {
                                Journal.note(LogCode.CALL, "фильтры выбраны", "что" to next.describe(), "где" to "настройки")
                                scope.launch { io.tima.core.call.CallFilterKeys.write(next).forEach { (k, v) -> deviceSettings.put(k, v) } }
                            }
                        },
                        section = io.tima.core.call.FilterSection.of(saved[io.tima.core.call.CallFilterKeys.SECTION]),
                        onSection = { section -> scope.launch { deviceSettings.put(io.tima.core.call.CallFilterKeys.SECTION, section.key) } },
                        video = video,
                        front = front,
                        trouble = trouble,
                        onSwitchCamera = if (preview.previewSwitchable) ({ scope.launch { preview.switchPreviewCamera() } }) else null,
                        onBack = { filtersHere = false },
                    )
                } else {
                // ПК (ФЗ17): увеличение кнопками со своим изображением — камера открыта, пока открыт пункт.
                val pc = callDevices
                if (pc != null) {
                    LaunchedEffect(pc) { pc.startCheck() }
                    DisposableEffect(pc) { onDispose { scope.launch { pc.stopCheck() } } }
                }
                val pcPreview = pc?.preview?.collectAsState()?.value
                io.tima.feature.call.CallCodingScreen(
                    coding = io.tima.core.call.HardwareCodingKeys.read(saved),
                    onChange = { chosen ->
                        scope.launch {
                            deviceSettings.put(io.tima.core.call.HardwareCodingKeys.ENCODE, if (chosen.encode) "1" else "0")
                            deviceSettings.put(io.tima.core.call.HardwareCodingKeys.DECODE, if (chosen.decode) "1" else "0")
                        }
                    },
                    cameraInBackground = io.tima.core.call.HardwareCodingKeys.cameraInBackground(saved),
                    onCameraInBackground = { keep ->
                        scope.launch { deviceSettings.put(io.tima.core.call.HardwareCodingKeys.CAMERA_BACKGROUND, if (keep) "1" else "0") }
                    },
                    // Увеличение изображения (ПЛАН-(ЗН) ЗН8): читается при начале звонка.
                    zoom = if (callDevices == null) io.tima.core.call.CallLookKeys.zoom(saved) else null,
                    onZoom = { z ->
                        Journal.note(LogCode.CALL, "увеличение изображения выбрано", "ступень" to z)
                        scope.launch { deviceSettings.put(io.tima.core.call.CallLookKeys.ZOOM, io.tima.core.call.CameraCrop.label(z)) }
                    },
                    hand = io.tima.core.call.CallLookKeys.hand(saved),
                    onHand = { h ->
                        Journal.note(LogCode.CALL, "рука для увеличения выбрана", "рука" to io.tima.core.call.CallLookKeys.code(h))
                        scope.launch { deviceSettings.put(io.tima.core.call.CallLookKeys.HAND, io.tima.core.call.CallLookKeys.code(h)) }
                    },
                    phone = pc == null,
                    pcZoom = pc?.let {
                        io.tima.feature.call.PcZoomSetup(
                            crop = io.tima.core.call.CallLookKeys.pcCrop(saved),
                            onCrop = { c ->
                                Journal.note(LogCode.CALL, "увеличение изображения выбрано", "обрезка" to c.describe(), "где" to "настройки ПК")
                                scope.launch { deviceSettings.put(io.tima.core.call.CallLookKeys.PC_CROP, io.tima.core.call.CallLookKeys.pcCropCode(c)) }
                            },
                            preview = pcPreview?.let { handle -> { modifier -> io.tima.feature.call.CallVideo(handle, modifier) } },
                        )
                    },
                    onFilters = filterPreview?.let { { filtersHere = true } },
                    filtersCount = savedFilters.changedTotal,
                )
                }
            }

            SettingsItem.PERMISSIONS -> {
                // Состояние читается при каждом заходе, а не запоминается: человек мог
                // сменить разрешение в системных настройках, пока нас не было.
                var awake by remember { mutableStateOf(awakeAllowed()) }
                var access by remember { mutableStateOf(notifyAccessWay()) }
                var callNow by remember { mutableStateOf(callAccessState()) }
                var contactsNow by remember { mutableStateOf(contactsAllowed()) }
                // Автозагрузку человек мог выключить и в «Диспетчере задач» — тоже читается заново.
                var startsWithSystem by remember { mutableStateOf(loginStart?.enabled()) }
                val desktop = platform == Platform.DESKTOP
                PermissionsScreen(
                    access = when (access) {
                        NotifyAccessWay.Given -> NotifyAccess.Given
                        NotifyAccessWay.Ask -> NotifyAccess.Ask
                        NotifyAccessWay.Settings -> NotifyAccess.Settings
                    },
                    onAsk = {
                        askNotifyAccess {
                            access = notifyAccessWay()
                            BackgroundWatch.check("разрешения: уведомления")
                        }
                    },
                    // Строки нет вовсе там, где усыплять некому (ПК): неактивная кнопка
                    // тоже зовёт нажать, а нажимать здесь не на что.
                    onBattery = if (desktop) null else ({ askAwake(); awake = awakeAllowed() }),
                    batteryFree = awake,
                    callsChannelOn = if (desktop) null else backgroundFacts().calls,
                    onCallsChannel = if (desktop) null else ::openCallsChannelSettings,
                    fullScreenOn = if (desktop) null else backgroundFacts().fullScreen,
                    onFullScreen = if (desktop) null else ::openFullScreenSettings,
                    microphone = callNow.microphone,
                    camera = callNow.camera,
                    onAskCall = { video -> askCallAccess(video) { callNow = callAccessState() } },
                    onCallSettings = ::openCallSettings,
                    // ПК: микрофон и камеру разрешает Windows, спросить её нечем (ПК3).
                    callInSettings = desktop,
                    contacts = contactsNow,
                    contactsInSettings = contactsAccessWay() == ContactsAccessWay.Settings,
                    onAskContacts = { askContactsAccess { contactsNow = contactsAllowed() } },
                    autostart = startsWithSystem,
                    onAutostart = loginStart?.takeIf { it.available }?.let { start ->
                        { on -> start.set(on); startsWithSystem = start.enabled() }
                    },
                )
            }

            SettingsItem.APPEARANCE -> AppearanceScreen(appearance, onAppearance)

            // Шрифты и размеры — второй пункт вида (ПЛАН-(Ш)-ШРИФТОВ Ш4).
            SettingsItem.TEXT -> TextLookScreen(appearance, onAppearance)

            // Вид главного окна и переходы (ПЛАН-(ВО) ВО5, ПЛАН-(МП) М5): пишется в настройки устройства аккаунта,
            // корень читает их оттуда же и меняет окно сразу.
            SettingsItem.MAIN_WINDOW -> {
                val stored by remember { deviceSettings.all() }.collectAsState(emptyMap())
                val put: (String, String) -> Unit = { k, v -> scope.launch { runCatching { deviceSettings.put(k, v) } } }
                val by = arriveFrom(stored)
                io.tima.feature.shell.MainWindowScreen(
                    order = io.tima.feature.shell.layoutOf(stored[MainLayoutKeys.ORDER]),
                    nav = stored[MainLayoutKeys.NAV] != "0",
                    arrive = { code -> Via.entries.firstOrNull { it.code == code }?.let { by(it) == Arrive.Point } ?: false },
                    phone = io.tima.core.ui.LayoutLocal.current.phone,
                    onOrder = { put(MainLayoutKeys.ORDER, io.tima.feature.shell.layoutCode(it)) },
                    onNav = { put(MainLayoutKeys.NAV, if (it) "1" else "0") },
                    onArrive = { code, point -> put(MainLayoutKeys.ARRIVE + code, if (point) Arrive.Point.code else Arrive.Start.code) },
                )
            }

            // Выбор языка приложения (ПЛАН-(Я)-ЯЗЫКА Я1). Сообщения не переводятся, и экран
            // говорит это строкой: перевода сообщений нет вовсе.
            SettingsItem.LANGUAGE -> {
                val localeState by locale.state.collectAsState()
                LaunchedEffect(Unit) { locale.refresh() }
                LanguageScreen(
                    current = language.tag,
                    onChoose = onLanguage,
                    country = localeState.locale.country,
                    writingLanguage = localeState.locale.lang,
                    readingLanguages = localeState.languagesText,
                    onlyMyCountry = localeState.filter.onlyMyCountry,
                    onlyMyLanguages = localeState.filter.onlyMyLanguages,
                    localeTrouble = localeState.trouble,
                    onCountry = locale::country,
                    onWritingLanguage = locale::writingLanguage,
                    onReadingLanguages = locale::readingLanguages,
                    onOnlyMyCountry = locale::onlyMyCountry,
                    onOnlyMyLanguages = locale::onlyMyLanguages,
                )
            }

            SettingsItem.UPDATE -> Update(update, updateState)

            // Отчёт о проблеме. Магазин создаётся ЗДЕСЬ, при открытии раздела: журнал
            // снимается в момент, когда человек пришёл жаловаться, а не когда дописал
            // текст — к тому времени начало поломки успело бы вытесниться.
            SettingsItem.PROBLEM -> Problem(
                problemFacts, origin, reporting, scope, platform, snapshot,
                draft = problemDraft, photos = problemPhotos, kind = problemKind, draftSteps = draftSteps,
            )

            // Предложение — та же отправка с очередью, но без журнала (заказчик 2026-10-07).
            SettingsItem.SUGGEST -> Suggest(problemFacts, reporting, scope, platform)

            // «Частые вопросы» (заказчик 2026-10-24): разделы, вкладки, страницы нашим же markdown.
            SettingsItem.QUESTIONS -> Questions(help)

            SettingsItem.STORAGE -> Storage(diaryPolicy, callsLog, callsState, deviceSettings, mediaFolder, postman)

            // Испытательный режим звонков. Пункт временный и уйдёт вместе со стендом —
            // держать его «на всякий случай» после испытаний незачем (С-В1).
            SettingsItem.CALLBENCH -> {
                val saved by deviceSettings.all().collectAsState(emptyMap())
                CallBenchSwitch(
                    on = benchState.on,
                    preset = benchState.preset.name,
                    onSwitch = bench::flag,
                    // Кнопки приближения и сдвига — испытательные (заказчик 2026-11-10): здесь, а не в «Звонках».
                    adapt = io.tima.core.call.CallLookKeys.adapt(saved),
                    onAdapt = { on ->
                        Journal.note(LogCode.CALL, "режим адаптации изображения выбран", "включён" to on)
                        scope.launch { deviceSettings.put(io.tima.core.call.CallLookKeys.ADAPT, if (on) "1" else "0") }
                    },
                )
            }

            else -> TabStub(
                willWhat = Tima.words.settings2.item(item),
                thanHolds = "Раздел из макета настроек. Экрана пока нет — " +
                    "doc/Layout-UI-light/пк/настройки.html",
            )
        }
    }
}

/** Свои устройства: список, отключение и вопрос перед ним. */
@Composable
internal fun Devices(
    store: DevicesStore,
    state: DevicesState,
    buildVersion: String,
    onSignOut: () -> Unit,
    onScanCode: (() -> Unit)?,
    accountCopy: BookCopySync,
) {
    // Ключа служебной группы нет — «Запросить ключ» (заказчик 2026-09-30).
    val keyMissing by accountCopy.keyMissing.collectAsState()
    val keyAsk by accountCopy.keyAsk.collectAsState()
    val auth = Tima.words.auth
    val keyNotice = when (val ask = keyAsk) {
        KeyAsk.Idle -> null
        KeyAsk.Sending -> auth.requestKeySending
        is KeyAsk.Asked -> auth.requestKeyAsked(ask.helpers)
        KeyAsk.NoHelpers -> auth.requestKeyNoHelpers
        KeyAsk.WrongPhrase -> auth.wrongPhrase
        KeyAsk.Got -> auth.requestKeyGot
        KeyAsk.NoAnswer -> auth.requestKeyNoAnswer
        is KeyAsk.Failed -> auth.requestKeyFailed(ask.reason)
    }
    DeviceScreen(
        state = state,
        onAsk = store::ask,
        onConfirm = store::revoke,
        onChangedMind = store::changedMind,
        buildVersion = buildVersion,
        onSignOut = onSignOut,
        onRetry = store::refresh,
        onScan = onScanCode,
        onRequestKey = if (keyMissing && keyAsk !is KeyAsk.Got) accountCopy::requestKey else null,
        keyNotice = keyNotice,
        keySending = keyAsk == KeyAsk.Sending,
        // Доверие (ДУ5): подтвердить себя фразой; заверять другие — только телефон с ключом.
        onConfirmWithPhrase = store::confirmWithPhrase,
        onCancelNewIdentity = store::cancelNewIdentity,
        onShowCertifyCode = store::showCertifyCode,
        // Запрет «Начать заново» (ДУ10, Р41): SMS, потом фраза и код.
        onSendBanCode = store::sendBanCode,
        onBanStartAnew = store::banStartAnew,
        // Сменить ключ копии после отключения устройства (М5).
        onRotateCopy = store::rotateCopy,
        // Завести копию на работающем устройстве (Р44).
        onStartCopy = store::startCopy,
        // Перерегистрация (ДУ9, Р34): запуск, заявка «Аккаунт украден», подтверждение в окне.
        rereg = store.reregView(msNow()),
        onStartRereg = store::startRereg,
        onSendReregCode = store::sendReregCode,
        onRereg = store::rereg,
        // Смена номера (ДУ9): заявка — код на прежний номер, в окне — на новый.
        phoneChange = store.phoneChangeView(msNow()),
        onSendPhoneCode = store::sendPhoneChangeCode,
        onPhoneChange = store::phoneChange,
    )
}

/**
 * Пин-код текущего аккаунта (ПЛАН-(ПН)): экран пин-кода — на месте вкладки, пока идёт; итог —
 * плашкой у строки. Живёт в Настройки → «Аккаунты» (заказчик 2026-10-07: «Перенесем пин код в
 * аккаунты»).
 */
@Composable
internal fun PinFlow(
    pin: PinHost?,
    content: @Composable (pinOn: Boolean?, onPin: ((io.tima.feature.auth.PinMode) -> Unit)?, pinNotice: String?) -> Unit,
) {
    val pinScope = rememberCoroutineScope()
    val pw = Tima.words.pin
    var pinFlow by remember { mutableStateOf<io.tima.feature.auth.PinFlowStore?>(null) }
    var pinVersion by remember { mutableStateOf(0) }
    var pinNotice by remember { mutableStateOf<String?>(null) }
    val flow = pinFlow
    if (flow != null && pin != null) {
        val fs by flow.state.collectAsState()
        LaunchedEffect(fs.step) {
            if (fs.step == io.tima.feature.auth.PinStep.Done) {
                pinNotice = when (fs.result) {
                    io.tima.feature.auth.PinResult.TurnedOn -> pw.turnedOn
                    io.tima.feature.auth.PinResult.TurnedOff -> pw.turnedOff
                    io.tima.feature.auth.PinResult.Changed -> pw.changed
                    else -> null
                }
                pinVersion++
                pinFlow = null
            }
        }
        io.tima.feature.auth.PinScreen(
            state = fs,
            onDigit = flow::digit,
            onErase = flow::erase,
            onForgot = flow::forgot,
            onPhrase = flow::submitPhrase,
            onChoose = flow::choose,
            onTick = flow::tick,
            now = { msNow() },
            temporary = pin.temporary,
            onCancel = { pinFlow = null },
        )
        return
    }
    val pinOn = remember(pin, pinVersion) { pin?.lock?.isOn() }
    content(
        pinOn,
        pin?.let { p ->
            { mode: io.tima.feature.auth.PinMode ->
                pinNotice = null
                pinFlow = io.tima.feature.auth.PinFlowStore(mode, p.lock, p.verify, pinScope, { msNow() }, ::notePin)
            }
        },
        pinNotice,
    )
}

/**
 * Строки «Аккаунтов»: аккаунты устройства и виртуальные с сервера, которых на устройстве нет.
 * «Передать» — у виртуальных из списка сервера: им распоряжается текущий аккаунт.
 */
internal fun accountRows(
    accounts: List<Account>,
    current: String,
    profile: io.tima.feature.chat.ProfileState,
    cardOf: (String) -> io.tima.core.secrets.AccountCard?,
    virtuals: VirtualsState,
): List<io.tima.feature.auth.AccountRow> {
    val owned = virtuals.accounts.map { it.userId }.toSet()
    val onDevice = accounts.map { account ->
        val card = cardOf(account.userId)
        val mine = account.userId == current
        io.tima.feature.auth.AccountRow(
            userId = account.userId,
            name = if (mine) profile.loadedName else card?.name.orEmpty(),
            nickname = if (mine) profile.savedNickname else card?.nickname?.ifBlank { null } ?: account.nickname,
            phone = if (mine) profile.phone else card?.phone.orEmpty(),
            virtual = account.virtual,
            current = mine,
            canGive = account.userId in owned,
        )
    }
    val elsewhere = virtuals.accounts.filter { v -> accounts.none { it.userId == v.userId } }.map { v ->
        io.tima.feature.auth.AccountRow(v.userId, "", v.nickname, "", virtual = true, current = false, canGive = true)
    }
    return onDevice + elsewhere
}

/**
 * Устройство отключено от аккаунта (ПЛАН-(А)-ВЫХОДА-ИЗ-АККАУНТА.md, А3): сервер ответил
 * `device_revoked`. Раньше приложение крутило `401` молча; теперь говорит, что случилось, и
 * ведёт на вход — там и QR, и номер телефона.
 */
@Composable
internal fun RevokedDevice(onAgain: () -> Unit, reason: String = "", deleteAt: Long? = null) {
    val words = Tima.words.auth
    // Причина (ДУ9, ДУ11) — тексты §2б; проигравшей стороне — выбор «бороться» или «новый аккаунт».
    val text = when (reason) {
        "reregistered" -> words.revokedReregistered
        "disputed" -> words.revokedDisputed
        "rereg_not_confirmed" -> words.reregNewLost(deleteAt?.let(::reregDate).orEmpty())
        "rereg_confirmed" -> words.reregOldLost
        else -> null
    }
    val lost = reason == "rereg_not_confirmed" || reason == "rereg_confirmed"
    androidx.compose.foundation.layout.Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Tima.colors.surface)
            .padding(io.tima.core.ui.TimaSpacing.about4),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(io.tima.core.ui.TimaSpacing.about3),
    ) {
        io.tima.core.ui.Caption(words.revokedTitle, weight = androidx.compose.ui.text.font.FontWeight.ExtraBold)
        io.tima.core.ui.Secondary(text ?: words.revokedAbout)
        if (lost) {
            // Обе дороги начинаются со входа: бороться — тем же номером, новый аккаунт — другим.
            io.tima.core.ui.Button(label = words.reregFight, onClick = onAgain, modifier = Modifier.fillMaxWidth())
            io.tima.core.ui.Button(label = words.reregNewAccount, onClick = onAgain, kind = io.tima.core.ui.ButtonKind.Quiet, modifier = Modifier.fillMaxWidth())
        } else {
            io.tima.core.ui.Button(label = words.signInAgain, onClick = onAgain, modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * Обновление: своя версия против того, что предлагает сервер.
 *
 * Порт [AppVersionPort] объявлен оболочкой, а сеть подставляется здесь — оболочка про
 * Ktor не знает и знать не должна.
 */
@Composable
internal fun Update(store: UpdateStore, state: UpdateState) {
    UpdateSection(
        state = state,
        onCheck = store::check,
        onInstall = store::ask,
        onConfirm = store::install,
        onDismiss = store::dismiss,
        canInstall = store.canInstall,
    )
}

/**
 * «Память и трафик»: что занимает место и когда убирается (ПЛАН-(ПМ)-ПАМЯТИ.md).
 *
 * Порты подставляются здесь: оболочка про `core-diag` не знает и не должна — она зависит
 * только от `core-ui`. Экран получает готовые значения и отдаёт обратно выбор.
 */
@Composable
internal fun Storage(
    policyStore: AppearanceStore,
    callsLog: CallsStore,
    callsState: CallsState,
    settings: io.tima.domain.chat.Settings? = null,
    media: io.tima.core.media.MediaFolder = io.tima.core.media.MediaFolder.NONE,
    postman: PostmanFiles? = null,
) {
    // ── Медиа и другие файлы (Решения §6): срок и предел по разделу, в настройках базы аккаунта ──
    val saved by (settings?.all() ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyMap<String, String>()) })
        .collectAsState(initial = emptyMap())
    var mediaTick by remember { mutableStateOf(0) }
    val mediaScope = rememberCoroutineScope()
    fun limitsOf(kind: String): io.tima.core.media.MediaLimits {
        val d = io.tima.core.media.MediaLimits.defaultsOf(kind)
        return io.tima.core.media.MediaLimits(
            keepDays = saved["media.$kind.days"]?.toIntOrNull() ?: d.keepDays,
            maxMegabytes = saved["media.$kind.mb"]?.toIntOrNull() ?: d.maxMegabytes,
        )
    }
    // Пересчёт — по смене пределов и после уборки: занятое человек обязан увидеть сразу.
    val mediaRows = remember(saved, mediaTick) {
        if (media === io.tima.core.media.MediaFolder.NONE) emptyList() else
        listOf(io.tima.core.media.MediaFolder.PHOTO, io.tima.core.media.MediaFolder.FILE, io.tima.core.media.MediaFolder.VOICE).map { kind ->
            val l = limitsOf(kind)
            io.tima.feature.shell.MediaKindState(
                kind = kind,
                occupied = media.entries(kind).sumOf { it.size },
                keep = if (l.keepDays <= 0) null else keepForDays(l.keepDays),
                megabytes = l.maxMegabytes,
            )
        }
    }

    var limits by remember { mutableStateOf(limitsOf(Journal.diary.policy)) }
    // Занятое пересчитывается после каждого действия, а не раз при открытии: человек
    // нажал «очистить» и обязан увидеть, что стало пусто, — иначе он нажмёт ещё раз.
    var occupied by remember { mutableStateOf(Journal.diary.occupied()) }

    StorageScreen(
        limits = limits,
        occupied = occupied,
        onLimits = { chosen ->
            limits = chosen
            val policy = DiaryPolicy(
                days = chosen.keep.days,
                bytes = chosen.megabytes.toLong() * DiaryPolicy.MB,
            )
            // Присвоение само зовёт уборку: новый срок обязан подействовать сразу, а не
            // при следующем запуске — иначе человек, поставивший неделю вместо месяца,
            // не увидит освободившегося места и решит, что настройка не работает.
            Journal.diary.policy = policy
            policyStore.save(policy.write())
            occupied = Journal.diary.occupied()
        },
        onClear = {
            Journal.diary.clear()
            occupied = Journal.diary.occupied()
        },
        // Журнал звонков: своя пара пределов, своё место хранения.
        //
        // Настройка лежит в `setting` местной базы, а не там, где срок дневника
        // (`AppearanceStore`, файл платформы). Причина в том, что дневник обязан
        // чиститься **до** открытия базы — он пишется с первой строки запуска, — а
        // журнал звонков живёт в самой базе, и держать его настройку снаружи значило бы
        // разнести по двум местам то, что убирается одним запросом.
        callLog = CallLogLimits(
            keep = keepForDays(callsState.keepDays),
            rows = callsState.keepRows,
        ),
        callLogRows = callsState.rows,
        onCallLog = { chosen -> callsLog.keep(days = chosen.keep.days, rows = chosen.rows) },
        media = mediaRows,
        onMediaLimits = { kind, keep, mb ->
            val chosen = io.tima.core.media.MediaLimits(keepDays = keep?.days ?: 0, maxMegabytes = mb)
            mediaScope.launch {
                settings?.put("media.$kind.days", chosen.keepDays.toString())
                settings?.put("media.$kind.mb", chosen.maxMegabytes.toString())
                // Новый срок действует сразу, а не при следующем запуске — как у журнала.
                val removed = io.tima.core.media.MediaSweep.sweep(media, kind, chosen, msNow())
                Journal.note(LogCode.FILE_STORE, "уборка по новым пределам", "раздел" to kind, "убрано" to removed)
                mediaTick++
            }
        },
        onMediaClear = { kind ->
            media.clear(kind)
            Journal.note(LogCode.FILE_STORE, "очищено", "раздел" to kind)
            mediaTick++
        },
        // Карточки ссылок (Решения §4): выключены — запросов к сайтам с телефона нет вовсе.
        linkCards = settings?.let { saved[LINK_CARDS_KEY] != "0" },
        onLinkCards = { on -> mediaScope.launch { settings?.put(LINK_CARDS_KEY, if (on) "1" else "0") } },
        // Файлы по мобильной сети (заказчик 2026-10-22): включили — отложенное забирается сразу.
        mobileFiles = settings?.let { saved[PostmanFiles.MOBILE_KEY] != "0" },
        onMobileFiles = { on ->
            mediaScope.launch {
                settings?.put(PostmanFiles.MOBILE_KEY, if (on) "1" else "0")
                if (on) postman?.resume()
            }
        },
    )
}

/**
 * Дни обратно в «месяцы» или «недели» — то же правило, что у [limitsOf].
 *
 * Единица не хранится отдельно и восстанавливается из числа: второе представление одного
 * числа пришлось бы держать в согласии, а оно однажды разъедется.
 */
internal fun keepForDays(days: Int): KeepFor = when {
    days <= 0 -> KeepFor(KeepUnit.Months, 12)
    days % KeepUnit.Months.days == 0 -> KeepFor(KeepUnit.Months, days / KeepUnit.Months.days)
    days % KeepUnit.Weeks.days == 0 -> KeepFor(KeepUnit.Weeks, days / KeepUnit.Weeks.days)
    else -> KeepFor(KeepUnit.Months, 12)
}

/**
 * Пределы журнала словами человека — из того, чем их держит `core-diag`.
 *
 * Единица восстанавливается из числа дней: делится на 30 — месяцы, иначе недели. Ровно
 * то, что могло получиться на этом экране; хранить единицу отдельно значило бы завести
 * второе представление одного числа и следить, чтобы они не разошлись.
 */
internal fun limitsOf(policy: DiaryPolicy): DiaryLimits {
    val keep = when {
        policy.days % KeepUnit.Months.days == 0 ->
            KeepFor(KeepUnit.Months, policy.days / KeepUnit.Months.days)
        policy.days % KeepUnit.Weeks.days == 0 ->
            KeepFor(KeepUnit.Weeks, policy.days / KeepUnit.Weeks.days)
        else -> KeepFor(KeepUnit.Months, 1)
    }
    return DiaryLimits(keep = keep, megabytes = (policy.bytes / DiaryPolicy.MB).toInt().coerceAtLeast(1))
}

/**
 * Отчёт о проблеме — ПЛАН-(Б)-ОТЛАДКИ.md, Б3.
 *
 * Порты объявляет оболочка, а подставляются они здесь: журнал приходит из `core-diag`,
 * отправка — из сети с очередью. Оболочка про них не знает и знать не должна.
 */
@Composable
internal fun Problem(
    facts: ProblemFacts,
    origin: Origin?,
    reporting: Reporting,
    scope: kotlinx.coroutines.CoroutineScope,
    platform: Platform,
    snapshot: () -> Snapshot,
    draft: String = "",
    photos: List<io.tima.feature.shell.ProblemPhoto> = emptyList(),
    kind: io.tima.feature.shell.ProblemKind = io.tima.feature.shell.ProblemKind.Other,
    draftSteps: () -> String = { "" },
) {
    val store = remember {
        ProblemStore(
            // Сброс перед выгрузкой: отчёт составляют ровно тогда, когда приложение
            // ведёт себя плохо, и следующего повода записать на диск может не быть —
            // человек закроет его силой, а система добьёт процесс.
            log = { days ->
                Journal.diary.flush()
                // Шаги неотправленных черновиков — после журнала, без номеров переписок (В3#).
                val steps = runCatching(draftSteps).getOrDefault("")
                Journal.diary.dump(days.toLong() * Diary.DAY) + if (steps.isEmpty()) "" else "\n" + steps
            },
            sender = { report ->
                val result = reporting.send(
                    ProblemPost(
                        kind = report.kind.name.lowercase(),
                        text = report.text,
                        origin = report.origin,
                        platform = platform.packageKind.ifBlank { platform.server },
                        model = report.facts.model,
                        os = report.facts.os,
                        build = report.facts.build,
                        stream = report.facts.stream,
                        nickname = report.facts.nickname,
                        // Снимок идёт первым блоком журнала, а не отдельным полем: он и
                        // есть часть того, что читают. Отдельное поле пришлось бы
                        // добавлять в таблицу, в ручку и в разбор — ради текста, который
                        // и так читается сверху вниз.
                        log = reportBody(report.began, report.snapshot, report.log),
                        images = report.photos.map { io.tima.core.network.ProblemImagePost.of(it.mime, it.bytes) },
                    ),
                )
                when (result) {
                    is ProblemSendResult.Sent -> SendOutcome.Sent(result.number)
                    // Связи нет — отчёт уже лежит в очереди, и человеку говорим именно
                    // это, а не «ошибка отправки»: он должен знать, что жалоба не пропала.
                    is ProblemSendResult.NoConnection -> SendOutcome.Queued
                    is ProblemSendResult.Refused ->
                        if (result.status == 0) SendOutcome.Queued
                        else SendOutcome.Refused("Сервер не принял отчёт (" + result.status + ")")
                }
            },
            scope = scope,
            origin = origin,
            facts = facts,
            snapshot = snapshot(),
            draft = draft,
            kind = kind,
            photos = photos,
        )
    }
    val state by store.state.collectAsState()
    // Фото к отчёту (ПЛАН-(В)-ВИДЕО.md В6): системный выбор на телефоне, файл на ПК. Сжимается
    // здесь, до мегабайтного предела сервера, — не на главном потоке: фото с камеры
    // раскодируется ощутимо.
    val pickPhoto = io.tima.core.media.rememberImagePicker { picked ->
        if (picked == null) return@rememberImagePicker
        scope.launch(kotlinx.coroutines.Dispatchers.Default) {
            val jpeg = io.tima.core.media.reportJpeg(picked.bytes)
            store.addPhoto(jpeg?.let { io.tima.feature.shell.ProblemPhoto("image/jpeg", it) })
        }
    }
    ProblemScreen(
        state = state,
        onText = store::changedText,
        onKind = store::chose,
        onBegan = store::chose,
        onShow = store::toggleShowing,
        onSend = store::send,
        onAddPhoto = pickPhoto,
        onRemovePhoto = store::removePhoto,
        photoPreview = { photo ->
            val picture = remember(photo) { decodeImage(photo.bytes) }
            picture?.let {
                androidx.compose.foundation.Image(
                    bitmap = it,
                    contentDescription = null,
                    modifier = Modifier.height(PHOTO_PREVIEW),
                )
            }
        },
    )
}

/**
 * «Предложить изменения» — вид `suggestion`, текст и фото. Журнала и снимка состояния нет; версия,
 * модель, система и ник — из [ProblemFacts] (заказчик 2026-10-07: «пригодится»).
 */
@Composable
internal fun Suggest(
    facts: ProblemFacts,
    reporting: Reporting,
    scope: kotlinx.coroutines.CoroutineScope,
    platform: Platform,
) {
    // Слова отказа — текущего языка, а не того, что был при создании store (и не глобалом:
    // CurrentWordsTest).
    val wordsNow by rememberUpdatedState(Tima.words)
    val store = remember {
        io.tima.feature.shell.SuggestStore(
            sender = { text, photos ->
                val result = reporting.send(
                    ProblemPost(
                        kind = SUGGESTION_KIND,
                        text = text,
                        origin = "",
                        platform = platform.packageKind.ifBlank { platform.server },
                        model = facts.model,
                        os = facts.os,
                        build = facts.build,
                        stream = facts.stream,
                        nickname = facts.nickname,
                        log = "",
                        images = photos.map { io.tima.core.network.ProblemImagePost.of(it.mime, it.bytes) },
                    ),
                )
                when (result) {
                    is ProblemSendResult.Sent -> SendOutcome.Sent(result.number)
                    is ProblemSendResult.NoConnection -> SendOutcome.Queued
                    is ProblemSendResult.Refused ->
                        if (result.status == 0) SendOutcome.Queued
                        else SendOutcome.Refused(wordsNow.suggest.refused(result.status))
                }
            },
            scope = scope,
        )
    }
    val state by store.state.collectAsState()
    val pickPhoto = io.tima.core.media.rememberImagePicker { picked ->
        if (picked == null) return@rememberImagePicker
        scope.launch(kotlinx.coroutines.Dispatchers.Default) {
            val jpeg = io.tima.core.media.reportJpeg(picked.bytes)
            store.addPhoto(jpeg?.let { io.tima.feature.shell.ProblemPhoto("image/jpeg", it) })
        }
    }
    io.tima.feature.shell.SuggestScreen(
        state = state,
        onText = store::changedText,
        onSend = store::send,
        onAddPhoto = pickPhoto,
        onRemovePhoto = store::removePhoto,
        photoPreview = { photo ->
            val picture = remember(photo) { decodeImage(photo.bytes) }
            picture?.let {
                androidx.compose.foundation.Image(bitmap = it, contentDescription = null, modifier = Modifier.height(PHOTO_PREVIEW))
            }
        },
    )
}

/** Высота снимка в форме отчёта: видно, что уходит, и не заслоняет форму. */
internal val PHOTO_PREVIEW = 160.dp

/**
 * Тело отчёта: снимок сверху, хронология под ним.
 *
 * Снимок отвечает «что сейчас», журнал — «как дошли»; читающий начинает с первого и
 * спускается ко второму, только если первого не хватило. Отдельным полем снимок не
 * заводится: это тот же текст, и отдельное поле пришлось бы вести в таблице, в ручке и в
 * разборе ради того, что и так читается сверху вниз.
 */
internal fun reportBody(began: Began, snapshot: Snapshot, log: String): String = buildString {
    appendLine("СОСТОЯНИЕ")
    // Первой строкой — ответ человека «когда началось». При разборе это первое, что
    // хочется знать, и до 2026-09-06 в отчёте этого не было вовсе.
    // По-русски всегда: тело отчёта читает чинящий (ПЛАН-(Я)-ЯЗЫКА §4).
    appendLine("  началось: " + beganLabel(began).lowercase())
    snapshot.lines().forEach { appendLine("  " + it) }
    appendLine()
    appendLine("ЧТО ПРОИСХОДИЛО")
    append(log)
}

/**
 * Сколько идёт этот запуск — словами.
 *
 * Нужно в снимке отчёта: «сеанс идёт 6 мин» сразу говорит, что глубже журнала нет, и
 * искать вчерашнее в нём бесполезно.
 */
internal fun howLongSince(startedAt: Long): String {
    val minutes = (nowMillis() - startedAt) / 60_000
    return when {
        minutes < 1 -> "идёт меньше минуты"
        minutes < 60 -> "идёт $minutes мин"
        else -> "идёт " + (minutes / 60) + " ч " + (minutes % 60) + " мин"
    }
}
