package io.tima.shared

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import io.tima.core.ui.Name
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

/**
 * Корень приложения — общий для всех платформ.
 *
 * Платформенному входу остаётся окно (или Activity) и открытая база: **правил поведения
 * платформенных не бывает**, и держать их по копии на платформу — это ровно то, из-за чего
 * в v1 Android и Desktop разошлись молча.
 *
 * @param базаУстройства открыть базу этого устройства. На ПК это файл в каталоге данных, на
 *   Android — `androidDatabase(context, имя)`.
 *
 * Развилка ровно одна, и решает её **заведённое устройство**, а не флаг «вошли»: флаг живёт
 * в памяти и врёт после перезапуска. Заведённое означает сессию, а не только секрет —
 * секрет пишется до вызова сервера, и секрет без сессии это незаконченный вход.
 *
 * **Тема живёт здесь, а не у платформенного входа.** До 2026-09-02 обе точки входа сами
 * звали `TimaTheme(dark = isSystemInDarkTheme())`, и тему решала операционная система.
 * Теперь её решает человек в настройках, а значит выбор — состояние приложения, и держать
 * его надо там, где живёт остальное состояние. Платформе остаётся то, что и было её
 * делом: где хранить строку.
 */
@Composable
fun Root(
    entry: Entry,
    /**
     * Открыть базу по имени файла (Д11).
     *
     * Своя база на каждый аккаунт, а не одна на приложение: у аккаунтов разные ключи
     * покоя, и общая база означала бы, что переписка виртуального открывается ключом
     * основного — то есть «отдельный пользователь» остаётся словами. Имя считает
     * `databaseFor`; приложению остаётся каталог.
     */
    deviceDatabase: (String) -> TimaDatabase,
    /** Где платформа хранит выбранное оформление. */
    appearanceStore: AppearanceStore,
    /**
     * Где платформа хранит выбранный язык (ПЛАН-(Я)-ЯЗЫКА Я1).
     *
     * Отдельно от оформления, хотя хранилище то же по устройству: это разные решения
     * человека, и общая строка означала бы, что смена темы трогает язык.
     *
     * Умолчание — «не хранит»: приложение обязано открыться и там, где места ещё нет.
     * Названо честно, а не подделкой: выбранный при таком хранилище язык живёт до
     * перезапуска.
     */
    languageStore: AppearanceStore = AppearanceStore.Forgetful,
    linkCode: String? = null,
    /**
     * Код передачи аккаунта, принесённый снаружи (Д12).
     *
     * Отдельный параметр, а не одна «принесённая ссылка» на оба случая: привязка и
     * передача ведут на разные экраны и означают разное — одно добавляет устройство к
     * своему аккаунту, другое забирает чужой. Разбирать их по префиксу здесь значило бы
     * повторить разбор, который уже делает платформа, принимая переход.
     */
    transferCode: String? = null,
    /**
     * Кто ставит скачанное обновление (ПЛАН-(О)-ОБНОВЛЕНИЯ.md, О3).
     *
     * `null` — платформа этого не умеет: на iOS обновление приходит из App Store, а на
     * сборке без установщика кнопки просто не будет. Кнопка, которая ничего не делает,
     * хуже её отсутствия — по ней судят, что приложение сломано.
     */
    installer: UpdateInstaller? = null,
    /** Аттестация ключа телефона (ДУ8); `null` — платформа не умеет. */
    attester: DeviceAttester? = null,
    /** Установщик запущен — платформе пора закрыть приложение. */
    onLeaving: () -> Unit = {},
    /** «Закрыть приложение» — кнопка в рейке ПК и в подокне переходов, фон останавливается. `null` — кнопки нет. */
    onExit: (() -> Unit)? = null,
    /** «Выйти» — уйти с экрана, фон работает (заказчик 2026-09-30). `null` — платформа не умеет. */
    onLeave: (() -> Unit)? = null,
    /** Сканер кода подключения — только телефон (заказчик 2026-09-30, 1б). `null` — кнопки нет. */
    onScanCode: (() -> Unit)? = null,
    /** Запуск вместе с системой — «Разрешения → Автозагрузка». `null` — раздела нет (телефон). */
    loginStart: LoginStart? = null,
    /**
     * Что платформа знает о себе для отчёта о проблеме (ПЛАН-(Б)-ОТЛАДКИ.md, Б3).
     *
     * Модель и версия системы — единственное, чего общий код о себе не знает, а в разборе
     * они решают половину дела: «на realme не работает, на Xiaomi работает».
     */
    facts: ProblemFacts = ProblemFacts(),
    /** Где платформа держит неотправленные отчёты. */
    reportsStore: ReportsStore = ReportsStore.Forgetful,
    /** Где платформа помнит начатую установку — чтобы сказать при запуске, чем кончилось. */
    updateMemory: UpdateMemory = UpdateMemory.Forgetful,
    /** Где платформа держит выбранный срок хранения журнала. Та же пара лямбд, что у темы. */
    diaryPolicy: AppearanceStore = AppearanceStore.Forgetful,
    /** Номер сборки от платформы: общий код его знать не может и не должен. */
    build: Build = Build(),
    /**
     * Чем исполнять звонок. `null` — платформа звонить не умеет (ПК, iOS): окна 0 тогда
     * нет вовсе, и кнопки «позвонить» тоже.
     *
     * Приходит снаружи, а не собирается здесь: движку нужен `Context`, а общий код его не
     * видит. Собирает его точка входа платформы — там же, где база и установщик.
     */
    callEngine: CallEngine? = null,
) {
    // Системная тема спрашивается ровно один раз и только затем, чтобы решить, с чего
    // начать при первом запуске. Дальше решает человек.
    val systemDark = isSystemInDarkTheme()
    var appearance by remember {
        mutableStateOf(Appearance.read(appearanceStore.load(), systemDark))
    }
    // Язык читается один раз при запуске и меняется только человеком. Незнакомый тег —
    // русский: приложение обязано открыться, а не остаться без надписей.
    var language by remember { mutableStateOf(Language.of(languageStore.load().orEmpty())) }

    val words = language.words ?: RussianWords
    // Тем, кто не рисует, словарь нужен ссылкой: store не @Composable и LocalWords не
    // видит (ПЛАН-(Я)-ЯЗЫКА, Я2-беды). Пишется отсюда и только отсюда — из того же значения,
    // что уходит в тему, поэтому разойтись им негде.
    SideEffect { CurrentWords.value = words }

    // Шрифт — отсюда же, что цвета и словарь (ПЛАН-(Ш)-ШРИФТОВ Ш1): одна подмена на всё.
    TimaTheme(
        colors = appearance.colors,
        words = words,
        font = appearance.text.font,
        text = appearance.text,
    ) {
      // Полосы авторов — из тех же «Цветов»: тип темы и выключатель (заказчик 2026-09-19).
      CompositionLocalProvider(LocalStripLook provides appearance.strips) {
        Inside(
            entry = entry,
            callEngine = callEngine,
            deviceDatabase = deviceDatabase,
            linkCode = linkCode,
            transferCode = transferCode,
            installer = installer,
            attester = attester,
            onLeaving = onLeaving,
            onExit = onExit,
            onLeave = onLeave,
            onScanCode = onScanCode,
            loginStart = loginStart,
            facts = facts,
            reportsStore = reportsStore,
            updateMemory = updateMemory,
            diaryPolicy = diaryPolicy,
            build = build,
            appearance = appearance,
            onAppearance = {
                appearance = it
                appearanceStore.save(it.write())
            },
            language = language,
            onLanguage = {
                // Словари без надписей не выбираются экраном; здесь это ещё раз
                // проверяется, потому что вызов может прийти и не от экрана.
                if (it.available) {
                    language = it
                    languageStore.save(it.tag)
                }
            },
        )
      }
    }
}

/**
 * Где платформа хранит оформление.
 *
 * Две лямбды, а не интерфейс с реализацией на платформу: хранить надо одну строку, и
 * заводить ради неё `expect`/`actual` значило бы завести платформенный слой там, где
 * платформенного ровно столько же, сколько у открытия базы, — то есть нисколько, кроме
 * места.
 *
 * Ошибку чтения хранилище гасит само и отдаёт `null`: не открывшееся оформление означает
 * тему по умолчанию, а не отказ пустить человека в переписку.
 */
class AppearanceStore(
    val load: () -> String?,
    val save: (String) -> Unit,
) {
    companion object {
        /**
         * Хранилище, которое не хранит.
         *
         * Для проверок и для платформы, у которой места ещё нет. Названо честно: тема,
         * выбранная при таком хранилище, живёт до перезапуска.
         */
        val Forgetful: AppearanceStore = AppearanceStore(load = { null }, save = {})
    }
}

@Composable
private fun Inside(
    entry: Entry,
    deviceDatabase: (String) -> TimaDatabase,
    /** Чем исполнять звонок; `null` — платформа звонить не умеет. См. [Root]. */
    callEngine: CallEngine?,
    linkCode: String?,
    transferCode: String?,
    installer: UpdateInstaller?,
    attester: DeviceAttester?,
    onLeaving: () -> Unit,
    onExit: (() -> Unit)?,
    onLeave: (() -> Unit)?,
    onScanCode: (() -> Unit)?,
    loginStart: LoginStart?,
    facts: ProblemFacts,
    reportsStore: ReportsStore,
    updateMemory: UpdateMemory,
    diaryPolicy: AppearanceStore,
    build: Build,
    appearance: Appearance,
    onAppearance: (Appearance) -> Unit,
    language: Language,
    onLanguage: (Language) -> Unit,
) {
    var device by remember { mutableStateOf(entry.created()) }

    val current = device
    if (current == null) {
        // Код, пришедший на устройство без аккаунта, ничего не значит: подтверждать
        // привязку нечем — своего ключа у него нет. Показываем обычный вход, а не
        // сообщение о беде: человек, скорее всего, просто отсканировал код не тем
        // приложением.
        Occurrence(
            entry,
            build,
            // Вернуть отложенный выходом аккаунт (ПЛАН-(А)-ВЫХОДА-ИЗ-АККАУНТА.md, А6).
            onReturn = { userId ->
                entry.returnTo(userId)
                device = entry.created()
            },
        ) { device = entry.created() }
        return
    }

    // Неотправленное по аккаунтам: читается один раз при входе, дальше правится тем
    // аккаунтом, который открыт. Чужие числа — снимки, оставленные их же проходами.
    var unsent by remember(current) {
        mutableStateOf(entry.accountList().associate { it.userId to entry.pendingOf(it.userId) })
    }

    // Сборка живёт в Assembly.kt: здесь навигация, а не «кто из чего состоит».
    // Первый аккаунт сохраняет прежнее имя базы: у того, кто уже пользуется
    // приложением, переписка лежит в `tima.db`, и переименование потеряло бы её.
    val assembled = assemble(entry, current, build, deviceDatabase, entry.accountList().firstOrNull()?.userId)

    // Карточка для экрана входа (заказчик 2026-10-06): имя и номер этого аккаунта — пока он
    // открыт. Отложенный потом покажется «Имя · +799 ••• 01 · с 06.10.2026», а не хвостом id.
    LaunchedEffect(current.session.userId) {
        val me = runCatching { assembled.network.profile.me() }.getOrNull() ?: return@LaunchedEffect
        entry.noteCard(current.session.userId, me.name, me.phone, me.nickname)
    }

    // Выход из аккаунта на этом устройстве (ПЛАН-(А)-ВЫХОДА-ИЗ-АККАУНТА.md, А4): канал
    // отпускается, указатель «текущий» снимается, аккаунт откладывается — и приложение
    // возвращается на экран входа, где есть и QR, и «Вернуть прежний аккаунт».
    val signOut: () -> Unit = {
        ChannelHost.release()
        if (entry.signOut()) device = entry.created()
    }

    // Отключённое устройство (А3): вместо вечных `401` — экран «отключено» и вход заново.
    val noRevoke = remember { kotlinx.coroutines.flow.MutableStateFlow(false) }
    val revoked by (assembled.network.tokenKeeper?.revoked ?: noRevoke).collectAsState()
    if (revoked) {
        // Канал с отозванным токеном переподнимался раз в 20 с впустую (ПК, 2026-09-30):
        // отпускаем его сразу, не дожидаясь «Войти снова».
        LaunchedEffect(Unit) { ChannelHost.release() }
        val why by (assembled.network.tokenKeeper?.revokedWhy ?: remember { kotlinx.coroutines.flow.MutableStateFlow(null) }).collectAsState()
        RevokedDevice(onAgain = signOut, reason = why?.reason.orEmpty(), deleteAt = why?.deleteAt)
        return
    }

    // Пин-код этого аккаунта (ПЛАН-(ПН)): замок при запуске, после 5 минут в фоне (PinGate) и при
    // переходе на аккаунт с пином. Открытый в этом запуске — без вопроса.
    val pinUser = current.session.userId
    val temporary = entry.accountList().firstOrNull { it.userId == pinUser }?.virtual == true
    val pinHost = remember(pinUser) {
        PinHost(entry.pin(pinUser), temporary) { text -> pinPhraseVerdict(assembled.network, pinUser, temporary, text) }
    }
    val relock by PinGate.relock.collectAsState()
    var locked by remember(pinUser, relock) { mutableStateOf(pinHost.lock.isOn() && !PinGate.isOpen(pinUser)) }
    LaunchedEffect(locked) { if (locked) Journal.note(LogCode.PIN_LOCK, "замок поставлен", "переход" to (PinGate.returnTo != null)) }
    val lockWho = remember(pinUser) {
        val card = entry.cardOf(pinUser)
        io.tima.feature.auth.PinWho(
            name = io.tima.domain.account.AccountTitle.of(
                card?.name.orEmpty(),
                card?.nickname?.ifBlank { null } ?: entry.accountList().firstOrNull { it.userId == pinUser }?.nickname.orEmpty(),
                pinUser,
            ),
            detail = card?.phone?.ifBlank { null }?.let { io.tima.feature.auth.maskPhone(it) }.orEmpty(),
            temporary = temporary,
        )
    }

    App(
        assembled = assembled,
        platform = entry.platform,
        callEngine = callEngine,
        deviceSecret = current.secret,
        askSecrets = remember(current.session.userId) { entry.signingKeys(current.session.userId) },
        linkCode = linkCode,
        transferCode = transferCode,
        installer = installer,
        attester = attester,
        onLeaving = onLeaving,
        onExit = onExit,
        onLeave = onLeave,
        onScanCode = onScanCode,
        loginStart = loginStart,
        facts = facts,
        reportsStore = reportsStore,
        updateMemory = updateMemory,
        diaryPolicy = diaryPolicy,
        build = build,
        appearance = appearance,
        onAppearance = onAppearance,
        language = language,
        onLanguage = onLanguage,
        accounts = entry.accountList(),
        cardOf = entry::cardOf,
        newsOf = entry::newsOf,
        onCard = { name, phone, nickname -> entry.noteCard(current.session.userId, name, phone, nickname) },
        unsent = unsent,
        // Число оставляет тот аккаунт, что открыт: чужую очередь не прочитать — её база
        // зашифрована своим ключом покоя (Д11).
        onPending = { howMany ->
            entry.notePending(current.session.userId, howMany)
            unsent = unsent + (current.session.userId to howMany)
        },
        // Переключение меняет указатель и перечитывает устройство. Всё остальное
        // пересобирается само: `assemble` помнит по устройству, а у другого аккаунта
        // другая сессия и другой ключ покоя.
        onSwitchAccount = { userId ->
            // Переход на аккаунт с пином (ПЛАН-(ПН) Р2): сразу его замок, а «Отмена» на замке
            // вернёт сюда — этот аккаунт уже открыт.
            val target = entry.pin(userId)
            PinGate.returnTo = if (target.isOn() && !PinGate.isOpen(userId)) current.session.userId else null
            entry.switchAccount(userId)
            device = entry.created()
        },
        pin = pinHost,
        locked = locked,
        lockWho = lockWho,
        onUnlocked = {
            PinGate.opened(current.session.userId)
            PinGate.returnTo = null
            locked = false
        },
        onLockCancel = PinGate.returnTo?.takeIf { it != current.session.userId }?.let { back ->
            {
                PinGate.returnTo = null
                entry.switchAccount(back)
                device = entry.created()
            }
        },
        lockOthers = entry.accountList().filter { it.userId != current.session.userId }.map { other ->
            val card = entry.cardOf(other.userId)
            LockAccount(other.userId, io.tima.domain.account.AccountTitle.of(card?.name.orEmpty(), card?.nickname?.ifBlank { null } ?: other.nickname, other.userId))
        },
        onSignOut = signOut,
        // Перерегистрация (ДУ9): доказательство прежней фразы готово — выйти и войти тем же
        // номером новой личностью. Прежняя личность остаётся отложенной: она живёт до исхода (Р50).
        onRereg = { ready ->
            ReregHandoff.hold(ready)
            // М6: фраза прежней — новой личности, чтобы перенести её копию к себе.
            if (ready.words.isNotEmpty()) PriorCopyPhrase.hold(ready.words)
            signOut()
        },
        // Заведённый виртуальный аккаунт записывается и становится текущим — то есть
        // приложение сразу входит в него. Иначе человек, только что придумавший ему ник
        // и записавший фразу, оставался бы в прежнем аккаунте и гадал, что произошло.
        onVirtualCreated = { created ->
            entry.rememberAccount(
                Account(
                    userId = created.session.userId,
                    nickname = created.nickname,
                    virtual = true,
                ),
                created.session,
                created.deviceSecret,
            )
            device = entry.created()
        },
        // Принятый по передаче аккаунт записывается и становится текущим — тем же путём,
        // что и заведённый. Ник его сюда не приходит: сервер отдаёт при передаче только
        // то, чем входить, а имя аккаунта в списке подтянется после первого захода в
        // профиль. Виртуальным он помечается сразу: телефона у него нет и не будет.
        onTransferTaken = { taken ->
            entry.rememberAccount(
                Account(userId = taken.session.userId, virtual = true),
                taken.session,
                taken.deviceSecret,
            )
            device = entry.created()
        },
    )
}

@Composable
private fun Occurrence(entry: Entry, build: Build, onReturn: (String) -> Unit = {}, onEntered: () -> Unit) {
    val scope = rememberCoroutineScope()
    val authWords = Tima.words.auth
    var listed by remember { mutableStateOf(entry.accountList()) }
    var gone by remember { mutableStateOf(emptySet<String>()) }
    var checking by remember { mutableStateOf<String?>(null) }
    val store = remember {
        AuthStore(
            register = entry.registration,
            identities = AccountIdentitiesOverKodium,
            scope = scope,
            link = entry.link,
            deviceName = entry.platform.deviceName,
            // Вход по фразе — слова один раз до просьбы о ключе служебной группы (2а).
            onEnteredByPhrase = PhraseOnce::hold,
            onPhraseKnown = KeyCopyPhrase::hold,
            rereg = ReregHandoff.take(),
            checkPhrase = io.tima.core.encryption.PhraseCheckOverKodium,
        )
    }
    val state by store.state.collectAsState()

    // Оба конечных состояния означают одно: устройство есть. «Готово» и «уже заведено»
    // различаются только тем, кто его завёл, а приложению дальше всё равно.
    if (state is AuthState.Done || state is AuthState.CreatedAlready) onEntered()

    EntryScreen(
        state = state,
        onNumber = store::changedNumber,
        onCodeCountry = store::changedCountryCode,
        onCode = store::changedCode,
        onRequest = store::requestCode,
        onConfirm = store::confirm,
        onBack = store::back,
        onPhrase = store::changedPhrase,
        onEnterByPhrase = store::enterByPhrase,
        onStartAnew = store::startAnew,
        onPhraseSaved = store::savedPhrase,
        onConnect = store::connect,
        buildVersion = build.name,
        // Отложенные выходом аккаунты (А6): имя, номер с закрытой серединой, с какого дня
        // (заказчик 2026-10-06). Нажатие сначала спрашивает сервер: аккаунта нет — «Забыть».
        returnable = listed.map { account ->
            val card = entry.cardOf(account.userId)
            io.tima.feature.auth.ReturnAccount(
                userId = account.userId,
                label = io.tima.feature.auth.returnLabel(
                    userId = account.userId,
                    name = card?.name.orEmpty(),
                    nickname = card?.nickname?.ifBlank { null } ?: account.nickname,
                    phone = card?.phone.orEmpty(),
                    since = card?.since?.takeIf { it > 0 }?.let { authWords.returnSince(reregDate(it).substringBefore(' ')) },
                ),
                gone = account.userId in gone,
                checking = checking == account.userId,
            )
        },
        onReturn = { userId ->
            if (checking == null) {
                scope.launch {
                    checking = userId
                    val alive = runCatching { entry.aliveOnServer(userId) }.getOrNull()
                    checking = null
                    // Не узнали (нет сети) — возвращаем как раньше: отключение покажет экран аккаунта.
                    if (alive == false) gone = gone + userId else onReturn(userId)
                }
            }
        },
        onForget = { userId ->
            entry.forget(userId)
            gone = gone - userId
            listed = entry.accountList()
        },
    )
}

/** Что открыто в главной области. Вся навигация верхнего уровня — эти три случая. */
private sealed interface Where {
    /** Ничего: на телефоне это список, на широком формате — пустая главная область. */
    data object Nothing : Where

    /** Подокно «новая переписка». */
    data object New : Where

    /**
     * Экран профиля: имя и ник (Д8).
     *
     * Отдельный экран, а не модалка: ник требует проверки занятости и показа ошибки,
     * аватар — загрузки картинки, и в модалке для этого тесно. Входа два и оба ведут
     * сюда: «Изменить» в переключении окон и «Профиль» в настройках.
     */
    data object Profile : Where

    /**
     * Своя страница «Я» (заказчик 2026-09-25): что известно о своём аккаунте, в том
     * числе что он временный. Правка профиля — кнопкой на ней, то есть [Profile].
     */
    data object SelfPage : Where

    /**
     * Подокно «новая группа».
     *
     * **Входа в него сейчас нет.** Чип «Группа» внизу списка переписок убран
     * 2026-09-03: в макете его нет, и появился он тогда, когда графического
     * интерфейса ещё не было. Подокно при этом собрано и работает; место входа —
     * каталог окна 2. Числится в `doc_mig/ИНТЕРФЕЙС/02-телефон/ФУНКЦИОНАЛ.md`,
     * раздел «нет входа», — иначе «сделано» неотличимо от «человек может этим
     * пользоваться».
     */
    data object NewGroup : Where

    /**
     * Подокно «участники группы».
     *
     * Открывается из окна переписки, а не из списка: состав — свойство открытой группы, и
     * попасть в него, не открыв её, значит спрашивать «чей состав».
     */
    data class Members(val groupId: String, val name: String?) : Where

    /** Переписка. Имя хранится здесь, потому что строка в списке появится позже — потоком. */
    data class Chat(val chatId: String, val name: String?) : Where

    /** Доступ к закрытым записям: просьбы и выдача. Открывается из состава группы. */
    data class Access(val groupId: String, val name: String?) : Where

    /**
     * Личная страница человека — **заглушка** (заказчик 2026-09-19).
     *
     * Хранится только идентификатор: имя, ник и номер берутся из справочника и книги —
     * там же, где их берут списки. Второе имя здесь разошлось бы с первым.
     *
     * Входа два, и оба — «нажали на человека»: аватар в книге и аватар с именем в шапке
     * переписки. Так в макете `страница-гостя.html`: «у строки две цели».
     */
    data class Person(val userId: String) : Where

    /**
     * Страница сообщества: состав, описание, подписка (ПЛАН-(СО)-СООБЩЕСТВ С6).
     *
     * Хранится только идентификатор: название приезжает вместе со страницей, и держать
     * его здесь значило бы иметь два источника одного слова.
     */
    data class Community(val communityId: String) : Where

    /**
     * Разговор под записью (ADR-0024).
     *
     * Адрес — канал и запись в нём: страница человека тоже канал, поэтому одного вида
     * достаточно и для ленты, и для канала.
     */
    data class Comments(val channelId: String, val postId: Long) : Where

    /**
     * Подтверждение привязки нового устройства.
     *
     * Приходит **снаружи**: человек навёл штатную камеру на код, та увидела `tima://link/…`
     * и открыла нас. Своего сканера у нас поэтому нет вовсе — и не нужно: чужой уже стоит
     * на каждом телефоне, а свой потребовал бы доступа к камере и объяснений, зачем он.
     */
    data class Link(val code: String) : Where

    /** Заверить устройство по отсканированному коду (Р32). */
    data class Certify(val code: String) : Where

    /**
     * Настройки: подокно с вкладками.
     *
     * Раньше «⚙» вело прямо в список устройств — тогда это было честно, потому что из
     * всех разделов существовал ровно один. Разделов стало три, и дверь снова одна.
     *
     * Вкладка хранится в состоянии, а не внутри подокна: человек, ушедший из настроек в
     * привязку устройства и вернувшийся назад, обязан вернуться на ту же вкладку, а не в
     * начало.
     */
    data class Settings(val item: SettingsItem? = null) : Where

    /**
     * Заведение виртуального аккаунта (ПЛАН-(Д)-КОНТАКТОВ.md, Д11).
     *
     * Открывается из переключения окон — оттуда же, где виден список аккаунтов. Это не
     * настройка: человек заводит второго себя, и место этому там, где он этих себя
     * выбирает.
     */
    data object NewVirtual : Where

    /**
     * Передача виртуального аккаунта (ПЛАН-(Д)-КОНТАКТОВ.md, Д12).
     *
     * @param virtualUserId кого передаём. `null` — мы принимающая сторона: принимающий
     *   не знает идентификатора до предъявления кода, и знать не должен.
     * @param brought код, принесённый снаружи: человек навёл штатную камеру на QR, та
     *   увидела `tima://transfer/…` и открыла нас. Пусто — пришли из настроек и код
     *   впишут руками.
     */
    data class Transfer(val virtualUserId: String?, val brought: String = "") : Where
}

/**
 * Окно приложения: стан из полос, в нём список и то, что открыто.
 *
 * **Навигация здесь — три случая, и ни одного стека.** Стек на этом этапе был бы механизмом
 * без нужды: из подокна выходят «назад» в список, из списка открывают одно. На телефоне
 * подокно ЗАМЕНЯЕТ список, на широком стоит рядом — и это решает [Стан], а не код
 * навигации. Семь окон и рейка — К5.4.
 */
@Composable
private fun App(
    assembled: Assembled,
    platform: Platform,
    deviceSecret: ByteArray,
    /** Ключ подписи устройств этого телефона (ДУ1–ДУ2). */
    askSecrets: AskSecrets,
    linkCode: String?,
    /** Код передачи, принесённый камерой (Д12). См. пояснение у [Root]. */
    transferCode: String? = null,
    /** Кто ставит обновление; `null` — платформа не умеет. См. пояснение у [Root]. */
    installer: UpdateInstaller? = null,
    /** Аттестация ключа телефона (ДУ8); `null` — платформа не умеет. */
    attester: DeviceAttester? = null,
    /** Установщик запущен — пора закрыть приложение. */
    onLeaving: () -> Unit = {},
    /** «Закрыть приложение» — кнопка в рейке ПК и в подокне переходов, фон останавливается. `null` — кнопки нет. */
    onExit: (() -> Unit)? = null,
    /** «Выйти» — уйти с экрана, фон работает (заказчик 2026-09-30). `null` — платформа не умеет. */
    onLeave: (() -> Unit)? = null,
    /** Сканер кода подключения — только телефон (заказчик 2026-09-30, 1б). `null` — кнопки нет. */
    onScanCode: (() -> Unit)? = null,
    /** Запуск вместе с системой; `null` — раздела нет. См. [Root]. */
    loginStart: LoginStart? = null,
    /** Что платформа знает о себе для отчёта о проблеме (Б3). */
    facts: ProblemFacts = ProblemFacts(),
    /** Где платформа держит неотправленные отчёты. */
    reportsStore: ReportsStore = ReportsStore.Forgetful,
    /** Где платформа помнит начатую установку. */
    updateMemory: UpdateMemory = UpdateMemory.Forgetful,
    /** Где платформа держит выбранный срок хранения журнала. */
    diaryPolicy: AppearanceStore = AppearanceStore.Forgetful,
    /** Номер сборки — показывается в «Устройствах», см. пояснение там. */
    build: Build,
    appearance: Appearance,
    onAppearance: (Appearance) -> Unit,
    language: Language,
    onLanguage: (Language) -> Unit,
    /** Аккаунты этого устройства: основной и его виртуальные (Д11). */
    accounts: List<Account> = emptyList(),
    /** Карточка аккаунта на устройстве — имя, номер, ник (подпись одна на всё приложение, 2026-10-07). */
    cardOf: (String) -> io.tima.core.secrets.AccountCard? = { null },
    /** Профиль текущего пришёл или изменился — обновить его карточку. */
    onCard: (name: String, phone: String, nickname: String) -> Unit = { _, _, _ -> },
    /** Сколько нового у неоткрытого аккаунта — спрашивается сервер его входом; `null` — не узнали. */
    newsOf: suspend (String) -> Int? = { null },
    onSwitchAccount: (String) -> Unit = {},
    /** Выйти из аккаунта на этом устройстве (ПЛАН-(А)-ВЫХОДА-ИЗ-АККАУНТА.md, А4). */
    onSignOut: () -> Unit = {},
    /** Перерегистрация подготовлена (ДУ9): выйти и войти тем же номером новой личностью. */
    onRereg: (io.tima.domain.account.PrepareRereg.Ready) -> Unit = {},
    /**
     * Заведён виртуальный аккаунт: записать его в список и войти в него.
     *
     * Записывает не экран: список аккаунтов и секреты живут в хранилище платформы, о
     * котором ни `feature`, ни `domain` не знают, — а войти значит пересобрать
     * окружение, то есть выйти на уровень выше.
     */
    onVirtualCreated: (VirtualStep.Created) -> Unit = {},
    /** Неотправленное по аккаунтам — для метки в переключателе (Д11). */
    unsent: Map<String, Int> = emptyMap(),
    /** Сколько осталось в очереди этого аккаунта после прохода. */
    onPending: (Int) -> Unit = {},
    /** Принятый по передаче аккаунт: записать в список и войти (Д12). */
    onTransferTaken: (TransferAcceptStep.Taken) -> Unit = {},
    /** Чем исполнять звонок; `null` — платформа звонить не умеет. См. [Root]. */
    callEngine: CallEngine? = null,
    /** Пин-код этого аккаунта (ПЛАН-(ПН)); `null` — проверки, снимки. */
    pin: PinHost? = null,
    /** Замок стоит — поверх всего, кроме идущего звонка (Р11). */
    locked: Boolean = false,
    lockWho: io.tima.feature.auth.PinWho? = null,
    onUnlocked: () -> Unit = {},
    /** «Отмена» на замке после перехода на аккаунт с пином; `null` — кнопки нет. */
    onLockCancel: (() -> Unit)? = null,
    /** Другие аккаунты устройства — «Другой аккаунт» на замке. */
    lockOthers: List<LockAccount> = emptyList(),
) {
    val environment = assembled.environment
    val network = assembled.network
    val session = assembled.session
    val scope = rememberCoroutineScope()
    // Заблокированные не показываются в окне «Телефон» и не считаются (Л8). Потоком,
    // а не разовым списком: разблокировали — переписка возвращается сама.
    val list = remember { ChatsStore(environment.chats, scope, blocked = environment.book.blocked()) }
    var where by remember { mutableStateOf<Where>(Where.Nothing) }
    // Куда человек ходил — второй вопрос правила журнала («что он делал»). Пишется смена,
    // а не каждая перерисовка: журнал должен читаться, а не разбухать.
    // Кодом, а не названием (заказчик 2026-09-27): код один на любом языке и находится
    // поиском в любом отчёте. Что значит код — в РЕЕСТР, для человека.
    LaunchedEffect(where) { Journal.note(LogCode.SCREEN_OPEN, whereCode(where)) }

    // Какое окно открыто. Приложение начинается с окна 1: личная связь — то, ради
    // чего его открывают чаще всего, а остальные окна пока пусты по существу.
    //
    // Хранится у процесса ([CallKeep]), а не в окне: Android пересоздаёт окно сам (Redmi
    // 2026-09-30), и открытое окно 0 или 6 не должно пропадать вместе с ним.
    var window by remember(session.deviceId) { CallKeep.window(session.deviceId) }

    /**
     * Показать окно 0 — **и убрать то, что его закрывает**.
     *
     * ── ДВА ШАГА, И ВТОРОЙ ЗАБЫВАЕТСЯ ───────────────────────────────────────
     *
     * Переписка, книга, личная страница, настройки — всё это подокна, и лежат они ПОВЕРХ
     * окна. Сменить окно под подокном значит открыть звонок невидимым: он идёт, а человек
     * видит прежний экран.
     *
     * Так уже было с исходящим (ЗВ1) — там второй шаг дописали. **На входящем он остался
     * забытым**, и это оказалось хуже: звонящий волен нажать «позвонить» откуда угодно, а
     * принимающий чаще всего сидит именно в переписке. Вызов приходил, журнал честно
     * писал «входящий звонок», экран не менялся, и через сорок пять секунд сторож клал
     * трубку. Со стороны выглядело как «позвонить нельзя в принципе» (2026-09-20).
     *
     * Поэтому вход в окно 0 теперь один на всех: и набор, и входящий, и плашка.
     */
    fun showCall() {
        window = Window.Call
        where = Where.Nothing
    }
    // Смена окна — тоже «что человек делал»: половина жалоб про конкретное окно.
    // В журнал — ключ, а не надпись: журнал читает чинящий, и запись не должна менять
    // вид от языка приложения.
    // Номер — тот, которым окна зовёт заказчик: окно 0 — звонок, окно 6 — стенд.
    LaunchedEffect(window) { Journal.note(LogCode.WINDOW_OPEN, window.name, "номер" to window.ordinal) }
    var windowSwitcher by remember { mutableStateOf(false) }
    // Панель «Переключение окон» — подокно без своего `Where`, и без этой строки её в
    // журнале не было вовсе.
    LaunchedEffect(windowSwitcher) { if (windowSwitcher) Journal.note(LogCode.SCREEN_OPEN, "switcher") }
    // Куда уходим, если очередь непуста. null — вопрос не задан: отдельного флага
    // «спрашиваем» не заводим, чтобы «спрашиваем, но некуда» не стало возможным.
    var leavingTo by remember { mutableStateOf<String?>(null) }
    // «Закрыть приложение» сначала спрашивает: закрыть или выйти (заказчик 2026-09-30, 3а).
    var closeAsked by remember { mutableStateOf(false) }
    // Подокно «Вид» вкладки «Контакты»: настроек три группы и они независимы, перебор
    // по кругу не дал бы угадать следующее состояние.
    var bookView by remember { mutableStateOf(false) }
    /** Разделы набора сообществ — поток из базы, читают Страница, меню чата и мастер. */
    val communityRows by environment.communitySections.sections().collectAsState(initial = emptyList())
    /** Обычные разделы набора сообществ и отдельно «Общий» — как у книги. */
    val communityShelves = communityRows.filterNot { it.common }
    val communityCommon = communityRows.firstOrNull { it.common }
    /** Выбранный раздел на вкладке «Группы» Страницы. */
    var groupSection by remember { mutableStateOf("") }
    // ── «Вид» набора сообществ: каталог Социума (решение заказчика 2026-09-18) ──
    // Свой вид под своим префиксом настроек: у набора сообществ свои разделы, и как их
    // показывать — тоже своё. Читается тем же потоком настроек, что и вид книги.
    // В памяти — сразу, в настройки — следом: `save` пишет шесть ключей по одному, и вид,
    // выведенный из настроек на полпути, затирал бы второе нажатие первым (так и вышло на
    // Redmi 2026-09-18: «Папки» не удержались после «Ярлычков»).
    var communityView by remember { mutableStateOf(BookView()) }
    LaunchedEffect(environment) {
        environment.settings.all().collect { communityView = BookView.from(it, BookView.COMMUNITY) }
    }
    var communityViewSheet by remember { mutableStateOf(false) }
    /** Вкладка Социума — здесь, чтобы пережить подокно «Вид» (оно перестраивает окно). */
    // «Социум» открывается на «Каталоге» — он первый (заказчик 2026-10-05).
    var socialTab by remember { mutableStateOf(WindowTab.Catalogue) }
    // Люди за идентификаторами — одно место на книгу, реплики и состав (2026-09-18).
    val people = remember(assembled) { People(network.directory, environment.bookStorage, scope, network.media) }
    val peopleCards by people.cards.collectAsState()
    val peopleFaces by people.faces.collectAsState()
    val peopleHues by people.hues.collectAsState()
    val wordsNow = Tima.words
    // Штамп отправителя из каждого события о сообщении (сервер 0052/0053): счётчик профиля
    // и цвет в группе. Карточка переспрашивается только при разнице счётчика.
    LaunchedEffect(assembled) {
        assembled.senderStamps.collect { people.stamp(it.userId, it.profileRev, it.groupId, it.hue) }
    }
    var communitySectionsScreen by remember { mutableStateOf(false) }
    /** Выбранный раздел в каталоге и свёрнутые разделы гармошки. */
    var catalogSection by remember { mutableStateOf("") }
    var catalogCollapsed by remember { mutableStateOf(setOf<String>()) }
    /** Экран управления разделами — из меню «Вид» (ПЛАН-(РЗ)-РАЗДЕЛОВ Р2). */
    var sectionsScreen by remember { mutableStateOf(false) }
    // Кого приглашаем. null — подокно закрыто: отдельного флага не заводим, чтобы
    // «открыто, но некого» не стало возможным состоянием.
    var inviting by remember { mutableStateOf<BookEntry?>(null) }
    var newContact by remember { mutableStateOf(false) }

    // Обновление живёт на уровне окна, а не внутри вкладки настроек, и причина не в
    // экономии: у него два потребителя. Вкладка «Обновление» — один; второй — порог
    // совместимости (уровень 2), который обязан сработать до того, как человек куда-то
    // нажал. Два магазина означали бы два разных ответа сервера на один вопрос.
    val update = remember {
        UpdateStore(
            versions = { versionOffer(network, platform) },
            scope = scope,
            installed = build.name,
            installedCode = build.code,
            stream = build.stream,
            installer = installer,
            onLeaving = onLeaving,
            memory = updateMemory,
        )
    }
    val updateState by update.state.collectAsState()

    // Отчёты, не ушедшие в прошлый раз, — включая записанные при падении. Досылка при
    // запуске, а не по кнопке: человек, у которого приложение закрылось само, второй раз
    // за отчётом не пойдёт (ПЛАН-(Б)-ОТЛАДКИ.md, Б7).
    val reporting = remember { Reporting(network.problems, ReportQueue(reportsStore)) }
    LaunchedEffect(assembled) {
        // Отметка запуска: без неё непонятно, к какому открытию приложения относятся
        // строки ниже, а отчёт присылают после нескольких запусков подряд.
        Journal.note(
            LogCode.APP_START,
            "приложение запущено",
            "версия" to build.name,
            "поток" to build.stream,
            "платформа" to platform.packageKind.ifBlank { platform.server },
            "аккаунт" to session.userId,
        )

        // Токен обновляется ДО первых вызовов, а не по первому отказу: приложение,
        // открытое через сутки, иначе начинало бы работу с череды 401 — их бы починил
        // перехват, но человек успел бы увидеть пустые списки (находка 2026-09-06).
        network.tokenKeeper?.renewIfStale()

        val sent = reporting.deliver()
        if (sent > 0) Journal.note(LogCode.REPORT_SENT, "досланы отложенные отчёты", "сколько" to sent)
    }

    val contacts = remember {
        NewContactStore(
            add = AddContact(environment.bookStorage, network.friends, network.discovery),
            book = environment.bookStorage,
            discovery = network.discovery,
            scope = scope,
            // Поиск по нику: единственный способ завести того, у кого номера нет вовсе.
            nicknames = network.directory,
            // Карточка найденного — показать, кого добавляют (заказчик 2026-09-26).
            people = { userId -> people.person(userId) },
        )
    }
    val contactsState by contacts.state.collectAsState()
    val invite = remember { platformInvite() }
    val profile = remember {
        // Номер сюда не приходит: в сессии его нет (userId, deviceId, токен), а
        // сервер отдаёт телефон только собеседникам по переписке. Строка номера в
        // профиле поэтому пуста — до тех пор, пока номер не начнёт храниться рядом
        // с сессией. Числится в ПЛАН-(Д)-КОНТАКТОВ.md, Д8.
        ProfileStore(profile = network.profile, phone = "", scope = scope, media = network.media)
    }
    val profileState by profile.state.collectAsState()
    // Карточка аккаунта — свежая, когда профиль пришёл или изменился (2026-10-07): по ней подписан
    // аккаунт на замке, на входе и в панели переходов, и устаревшее имя там видно сразу. Пустой
    // профиль (ещё не загружен) карточку не затирает.
    LaunchedEffect(profileState.loadedName, profileState.savedNickname, profileState.phone) {
        if (profileState.loadedName.isNotBlank() || profileState.savedNickname.isNotBlank() || profileState.phone.isNotBlank()) {
            onCard(profileState.loadedName, profileState.phone, profileState.savedNickname)
        }
    }
    // Кто я — с сервера, один раз на сборку корня. Телефон отсюда уходит и в шапку
    // переключения окон: сессия его не хранит (0050).
    LaunchedEffect(profile) { profile.refresh() }

    // Виртуальный аккаунт: ник проверяется тем же профилем, что и свой, а создание
    // заверяется фразой владельца — кода из SMS у аккаунта без телефона не будет.
    val newVirtual = remember {
        NewVirtualStore(
            create = CreateVirtual(
                api = network.virtuals,
                keys = DeviceKeyFactoryOverKodium,
                identities = AccountIdentitiesOverKodium,
                signer = IdentitySignerOverKodium,
                platform = platform.server,
            ),
            profile = network.profile,
            scope = scope,
        )
    }
    val newVirtualState by newVirtual.state.collectAsState()

    // Свои виртуальные аккаунты и передача: оба про распоряжение аккаунтом, оба живут
    // столько же, сколько окно.
    val virtuals = remember { VirtualsStore(network.virtuals, scope) }
    val virtualsState by virtuals.state.collectAsState()
    val transfer = remember {
        TransferStore(
            transfer = TransferVirtual(
                api = network.transfers,
                keys = DeviceKeyFactoryOverKodium,
                prover = TransferProverOverKodium,
                platform = platform.server,
            ),
            payloadOf = TransferQr::payload,
            parse = TransferQr::parse,
            scope = scope,
        )
    }
    val transferState by transfer.state.collectAsState()

    // Контакты: своя книга — прочитанное с телефона плюс заведённое руками (Д2…Д5).
    // Поток из базы: прочитанное и итог сверки появляются сами, без опроса.
    val book = remember {
        BookStore(
            book = environment.book,
            settings = environment.settings,
            sync = SyncBook(platformPhoneBook(), environment.bookStorage, network.discovery),
            scope = scope,
            edit = environment.bookStorage,
            lists = RemoveContact(environment.bookStorage, network.friends),
        )
    }
    // Журнал звонков (Ж2): поток из базы плюс поход на сервер при открытии вкладки.
    //
    // Читается как переписка, наполняется иначе: переписка приезжает кадрами событий и
    // расшифровывается, а журнал сервер знает сам — сходили, записали, показали.
    val callsLog = remember {
        CallsStore(
            log = environment.callLog,
            history = network.callHistory,
            scope = scope,
            me = session.userId,
            settings = environment.settings,
            now = { msNow() },
            // Просмотрено — на все устройства человека: сервер разошлёт «seen» лентой,
            // а здесь строки снимаются сразу, не дожидаясь её.
            seenRemote = { ids ->
                ids.forEach { assembled.notices.missedSeen(it) }
                network.calls.seen(ids)
            },
        )
    }
    val callsState by callsLog.state.collectAsState()

    // Числа вкладок, окон и строк — из журнала уведомлений (ПЛАН-(ЖУ)-ЖУРНАЛА-УВЕДОМЛЕНИЙ.md,
    // ЖУ2): по сущностям, а не по сообщениям, и из одного места.
    // Сверка журнала с базой при запуске (ЖУ1): после обновления журнал пуст, а
    // непрочитанное есть; прочитанное могли отметить мимо журнала.
    LaunchedEffect(assembled) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            runCatching {
                assembled.notices.reconcile(
                    unread = environment.readState.unreadChats(),
                    missed = environment.readState.missedUnseen(session.userId),
                )
            }.onFailure { Journal.trouble(LogCode.NOTICE, "журнал уведомлений не сверен", "почему" to it.message.orEmpty()) }
        }
    }
    val noticeCounts by remember {
        environment.noticeJournal.active().let { flow -> kotlinx.coroutines.flow.flow { flow.collect { emit(io.tima.domain.chat.NoticeCounts(it)) } } }
    }.collectAsState(io.tima.domain.chat.NoticeCounts.NONE)

    // Окно 2 «Социум»: свои группы и карточки, которые открыли контакты. Списки живут
    // здесь, а не в оболочке: рама знает раму, работа с сервером — дело feature-group.
    val social = remember { SocialStore(GroupsOverHttp(network.groups), scope, network.communities) }
    // Окно 5 «Страница»: своя лента — своё и принесённое. Один Store на приложение: одна
    // страница у человека, и второй показывал бы то же самое со своим отставанием.
    val page = remember { PageStore(network.pages, scope, switches = network.commentSwitches) }

    // Звонок: окно 0 живёт, пока идёт разговор (макет 21-call.md, решение заказчика
    // 2026-09-19). Движок приходит от платформы — общий код Context не видит; `null`
    // означает «эта платформа звонить не умеет», и тогда окна 0 нет вовсе.
    // Стенд звонков — испытательный режим за флагом (ПЛАН-(С)-СТЕНДА-ЗВОНКОВ §4).
    //
    // **Заводится всегда, даже когда флаг выключен**, и это не расточительство: он
    // хранит выбранный набор публикации, а набор работает и без стенда — выключение
    // флага уносит обвязку, но не выбор. Замеров при выключенном флаге он не делает.
    //
    // Стенд и ведущий звонка — у процесса ([CallKeep], заказчик 2026-09-30, 1а): окно Android
    // пересоздаётся системой, и прогон со звонком не должны уходить вместе с ним.
    val kept = remember(callEngine, session.deviceId) {
        CallKeep.kept(session.deviceId, callEngine) { keepScope ->
            val bench = BenchStore(environment.settings, callEngine, keepScope)
            // Набор — ЛЯМБДОЙ, а не значением: `CallHost` живёт от запуска до запуска, а набор
            // меняют на экране стенда посреди его жизни. Снятый значением, он застыл бы на том,
            // что было выбрано при старте.
            //
            // Прогон — звонок при нажатой «Начать прогон» (`armed`), и кодек пресета в нём не
            // заменяется (заказчик 2026-09-25). Без неё звонок обычный, даже при включённом
            // испытательном режиме, — кодек по умению телефона.
            //
            // Испытательный режим выключен — набора нет вовсе: обычный звонок, H.264 или VP8,
            // потолок от сервера (ПЛАН-(В)-ВИДЕО.md В3, В5б). Стенд при этом не меняется.
            // «Видео при сворачивании» (1в) — ссылкой на поток настроек: меняют посреди жизни.
            val cameraInBackground = environment.settings.all()
                .map { io.tima.core.call.HardwareCodingKeys.cameraInBackground(it) }
                .stateIn(keepScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)
            val host = CallHost(
                network.calls,
                callEngine,
                keepScope,
                preset = { bench.state.value.let { if (it.on) it.preset.copy(exact = it.armed) else null } },
                cameraInBackground = { cameraInBackground.value },
                ringback = io.tima.core.call.CallTones::ringback,
                busyTone = io.tima.core.call.CallTones::busy,
            )
            CallKeep.Kept(callEngine, bench, host)
        }
    }
    val bench = kept.bench
    // Выбор микрофона, колонок и камеры — в движок с запуска (ПЛАН-(ПК)-ЗВОНКОВ-ПК).
    followCallSetup(callEngine as? io.tima.core.call.CallDevices, environment.settings)
    // Переключатели «Настройки → Звонки» — в движок, и в идущий звонок тоже (ПЛАН-(В)-ВИДЕО.md В4).
    LaunchedEffect(callEngine) {
        val live = callEngine ?: return@LaunchedEffect
        environment.settings.all().collect { live.setHardwareCoding(io.tima.core.call.HardwareCodingKeys.read(it)) }
    }
    val benchState by bench.state.collectAsState()

    val callHost = kept.host

    // Входящий звонок приходит каналом событий. Имя собеседника берём тем же механизмом,
    // что везде (Д14): карточка справочника поверх книги — иначе человек увидит
    // идентификатор вместо имени ровно в тот момент, когда решает, брать ли трубку.
    //
    // Каждое событие — по порядку, из очереди (`CallPings`, отчёт FTPB 2026-09-27). Раньше
    // окно читало одно значение, и «звонит» нового звонка затиралось «завершён» прошлого.
    // `callPingsSeen` — сколько событий съедено: по нему поручение из строки звонка ниже
    // узнаёт, что звонок мог стать нашим.
    var callPingsSeen by remember { mutableStateOf(0L) }
    // Названия групп для вызова в групповой звонок: список переписок собирается ниже, а
    // события звонка разбираются здесь (заполняется по списку, см. `groupTitleOf`).
    val groupTitles = remember { androidx.compose.runtime.mutableStateMapOf<String, String>() }
    LaunchedEffect(assembled) {
        assembled.callPings.pings.collect { callPing ->
            val parts = callPing.split("|")
            when {
                // **Сверяем, о каком звонке речь.** Раньше любой кадр `call.state` клал нашу
                // трубку: у человека может идти один звонок и висеть отказ по другому, и
                // чужой конец обрывал живой разговор.
                // Ответили на другом устройстве этого же человека: закрываем окно, звонок
                // не трогаем — он идёт там.
                parts.size == 3 && parts[0] == "перехвачен" ->
                    if (callHost.active && callHost.callIs(parts[1])) callHost.takenElsewhere()
                parts.size == 3 && parts[0] == "конец" ->
                    // Слово сервера едет дальше: `busy` означает, что собеседник занят
                    // другим разговором, и сказал это его собственный телефон.
                    if (callHost.active && callHost.callIs(parts[1])) callHost.ended(parts[2])
                // Собеседник вышел из комнаты — по вебхуку SFU, а не нажатием. Так кончается
                // звонок, у которого вторую сторону убили или она потеряла сеть насовсем:
                // нажимать «Завершить» там было некому.
                //
                // **Проверяем, чей уход.** Сегодня звонок один на один, и уход собеседника
                // его кончает; в группе уход одного из пятерых разговора не кончит, и менять
                // придётся здесь.
                parts.size == 3 && parts[0] == "ушёл" ->
                    if (callHost.active && callHost.peerIs(parts[2])) callHost.hangUp()
                // Вызов не забрало ни одно устройство собеседника. Трубку не кладём: это
                // слово о связи, а не о человеке (ADR-0025 §1а).
                parts.size == 3 && parts[0] == "недоступен" -> callHost.peerOffline(parts[1])
                // Вызов дошёл до телефона собеседника (ВЗ0а): «Вызов…» становится «Звонит».
                parts.size == 3 && parts[0] == "доставлен" -> callHost.peerRinging(parts[1])
                // Зовут в групповой звонок (ГЗ3): принять — войти в звонок группы.
                parts.size == 5 && parts[0] == "группа" -> {
                    val callId = parts[1]
                    val fromId = parts[2]
                    people.want(listOf(fromId))
                    val title = groupTitles[parts[4]] ?: wordsNow.groupCall.title
                    callHost.ringGroup(callId, parts[4], title, fromId, video = parts[3] == "video")
                    showCall()
                }
                // Команда создателя группового звонка — мне (решения 5, 6, 17).
                parts.size == 4 && parts[0] == "команда" -> callHost.controlled(parts[1], parts[2], parts[3])
                parts.size == 3 && parts[0].isNotEmpty() -> {
                    val (callId, fromId, kind) = parts
                    people.want(listOf(fromId))
                    callHost.ring(
                        callId = callId,
                        fromId = fromId,
                        // Словарь «Вида» здесь ещё не собран — он ниже; для входящего
                        // довольно того, как человек назвал себя сам. Имя из книги подставит
                        // экран, когда звонок откроется.
                        fromName = peopleCards[fromId]?.line(PersonLook.DEFAULT, PERSON_FIRST_LINE).orEmpty(),
                        video = kind == "video",
                    )
                    showCall()
                }
            }
            callPingsSeen++
        }
    }

    // ── ПОРУЧЕНИЕ ИЗ СТРОКИ ЗВОНКА (ВЗ1, ВЗ2) ──────────────────────────────
    //
    // «Принять» на замке или нажатие на полноэкранный вызов поднимает окно; звонок к этому
    // времени мог ещё не дойти до `CallHost` (окно новое, лента применится через миг).
    // Поэтому ждём, пока звонок станет нашим, и только тогда исполняем.
    val callOrder by CallRequests.order.collectAsState()
    LaunchedEffect(callOrder, callHost.active, callPingsSeen) {
        val order = callOrder ?: return@LaunchedEffect
        if (!callHost.active || !callHost.callIs(order.callId)) return@LaunchedEffect
        CallRequests.done(order.callId)
        showCall()
        people.want(listOf(callHost.peerUserId))
        if (order.accept && callHost.incoming && callHost.state.stage != CallStage.Connected) {
            assembled.notices.callOver(order.callId)
            Journal.note(LogCode.CALL, "приняли из строки уведомления", "звонок" to order.callId.take(8))
            callHost.accept()
        }
    }

    // Звонок кончился и окно закрыли — уходим туда, откуда пришли. Оставить человека в
    // окне, которого больше нет, нельзя: свайп и переключатель его уже не показывают.
    LaunchedEffect(callHost.active) {
        if (!callHost.active && window == Window.Call) window = Window.Phone
        // Разговор кончился — за строкой о нём идём сейчас, а не при следующем заходе
        // во вкладку. На журнал смотрят СРАЗУ после звонка, и строка, появляющаяся
        // через минуту, читается как потеря.
        //
        // Срабатывает и при запуске, когда звонка нет вовсе, — и это не лишнее: счётчик
        // пропущенных обязан быть верным до того, как во вкладку заглянут, а не после.
        if (!callHost.active) callsLog.callEnded()
    }
    // Страна, язык письма и два переключателя отбора. Один магазин на приложение: настройка
    // одна, и второй показывал бы то же самое со своим отставанием.
    val locale = remember { LocaleStore(network.locales, environment.settings, scope) }
    // Под записью ответили — перечитываем страницу, если она открыта. Счётчик под записью
    // меняется сам, без нажатия (ADR-0024, следствие 5).
    //
    // Полки уведомлений в приложении нет вовсе: ни списка событий, ни значка, ни push.
    // Поэтому «уведомление автору» выполнено настолько, насколько есть куда его показать;
    // остальное заводится вместе с полкой, отдельной работой.
    val commentPing by assembled.commentPings.collectAsState()
    LaunchedEffect(commentPing) {
        if (commentPing != 0L) page.refresh()
    }
    var phoneTab by remember { mutableStateOf(WindowTab.Chats) }

    // Откуда ушли в настройки (ПЛАН-(Б)-ОТЛАДКИ.md, Б2). Запоминается ЗДЕСЬ, в момент
    // перехода: к моменту отправки отчёта «текущее окно» будет «Настройки», то есть
    // бесполезным. Вкладка есть только у окна «Телефон» — у остальных её пока нет, и
    // выдумывать нечего.
    var cameFrom by remember { mutableStateOf<Origin?>(null) }
    /** Черновик отчёта о проблеме — подставляется из подокна неотправленного сообщения. */
    var problemDraft by remember { mutableStateOf("") }
    // Отчёт из окна 0: о звонках и с кадром собеседника (ПЛАН-(В)-ВИДЕО.md В7, В8).
    var problemPhotos by remember { mutableStateOf(emptyList<io.tima.feature.shell.ProblemPhoto>()) }
    var problemKind by remember { mutableStateOf(io.tima.feature.shell.ProblemKind.Other) }

    // Когда начался этот запуск. В снимке отчёта из него получается строка «сеанс идёт
    // 6 мин» — она говорит, сколько журнала мы вообще застали: журнал живёт в памяти
    // процесса, и после убийства приложения в нём только новое.
    val startedAt = remember { nowMillis() }
    val startedWords = { howLongSince(startedAt) }
    val toSettings: () -> Unit = {
        cameFrom = Origin(
            window,
            // Вкладка в отчёте называется ПО-РУССКИ и всегда: отчёт читает тот, кто
            // чинит, и переведённый он перестаёт совпадать с тем, что ищут поиском
            // (ПЛАН-(Я)-ЯЗЫКА, «что не переводится»).
            if (window == Window.Phone) RussianWords.tabs.label(phoneTab) else "",
        )
        where = Where.Settings()
    }

    val new = remember {
        NewChatStore(
            start = StartPersonalChat(
                directory = network.directory,
                chats = SqlChatBook(environment.db, environment.cipher),
                ids = PersonalChatIdsOverKodium,
            ),
            myUserId = session.userId,
            scope = scope,
        )
    }

    // Код снаружи открывает подтверждение поверх всего: человек только что навёл камеру и
    // ждёт ответа именно на это.
    LaunchedEffect(linkCode) {
        // Код заверения уже подключённого устройства (Р32) приходит тем же сканером.
        linkCode?.let { where = if (io.tima.core.network.CertifyQr.isCertify(it)) Where.Certify(it) else Where.Link(it) }
    }

    // Код передачи приходит тем же путём и ведёт на приём: человек навёл камеру на чужой
    // QR, и единственное, чего он ждёт, — поле для фразы. Сторона задаётся здесь, а не
    // переключателем на экране: принёсший код — принимающий, других вариантов нет.
    LaunchedEffect(transferCode) {
        transferCode?.let { where = Where.Transfer(virtualUserId = null, brought = it) }
    }

    val socialState by social.state.collectAsState()
    val listState by list.state.collectAsState()
    LaunchedEffect(listState.chats) {
        for (chat in listState.chats) {
            if (chat.kind == ChatKind.Group) chat.title?.takeIf { it.isNotBlank() }?.let { groupTitles[chat.chatId] = it }
        }
    }
    /** Название группы для звонка и приглашения; неизвестной — «Групповой звонок». */
    fun groupTitleOf(groupId: String): String =
        listState.chats.firstOrNull { it.chatId == groupId }?.title?.takeIf { it.isNotBlank() }
            ?: wordsNow.groupCall.title

    // ── Р4: разделы у личных переписок ─────────────────────────────────────────
    // Раздел переписки — раздел собеседника в книге. Считается здесь, где видны и книга,
    // и переписки; своего поля у переписки нет и заводить его незачем.
    var chatSection by remember { mutableStateOf("") }
    val bookStateForChats by book.state.collectAsState()
    // ── ЕДИНОЕ ОТОБРАЖЕНИЕ ЧЕЛОВЕКА В ОКНЕ «ТЕЛЕФОН» ──────────────────────────
    //
    // Решение заказчика 2026-09-19: «поправь отображение во вкладке „Чаты“, чтобы
    // отображение встало единым для окна „Телефон“; единый механизм сделай».
    //
    // Собеседник личной переписки — тот же человек, что строка книги: карточка справочника
    // плюс имя из книги поверх неё. Отсюда и имя по «Виду», и аватар, и раздел переписки
    // (Р4). У группы собеседника нет — там остаётся название переписки.
    //
    // **Кто это, спрашивается один раз.** До 2026-09-19 раздел искал человека по `userId`, а
    // имя не искало вовсе — и на одном экране получались два ответа про одного. Ищется
    // по `userId`, а **если не нашлось — по номеру карточки**: то же правило, что в
    // `People.whoIs`. Оно нужно не для красоты — на стенде нашлась переписка, чей
    // `peer_id` не совпал ни с одним `user_id` книги, хотя номер у человека тот же:
    // аккаунт был заведён заново, а книга держит прежний идентификатор.
    val entryOfChat: (ChatSummary) -> BookEntry? = { chat ->
        chat.peerId?.takeIf { chat.kind == ChatKind.Personal }?.let { id ->
            val card = peopleCards[id]
            bookStateForChats.all.firstOrNull {
                it.userId == id || (card?.phone != null && it.phone == card.phone)
            }
        }
    }
    val sectionOfChat: (ChatSummary) -> String = { chat -> entryOfChat(chat)?.sectionId ?: "" }

    // ── СТРОКА ПЕРЕПИСКИ ЗАВОДИТСЯ ПРИ ВХОДЕ, А НЕ ПРИ ПЕРВОМ СООБЩЕНИИ ───────
    //
    // Беда 2026-09-19, найдена заказчиком на realme и Samsung: вход в переписку из
    // «Контактов» строку `chats` НЕ заводил — он только считал `chat_id` из пары и
    // переходил. Список переписок выводится из сообщений и потому такую переписку
    // показывал; собеседника у неё при этом не было, и следствий выходило три сразу:
    //
    //   1. в списке она звалась «Без имени» — имени неоткуда взять;
    //   2. личная страница из её шапки не открывалась — некого открывать;
    //   3. **сообщения молча не уходили**: `Sender.prepare` отказывается, не зная, кому
    //      адресовать, и человек видел «ждёт» без объяснения.
    //
    // Redmi работал потому, что там переписку завели «Новой перепиской» по номеру — а
    // там строка пишется (`StartPersonalChat`). То есть беда зависела от того, каким
    // входом человек воспользовался, и потому выглядела как «работает не на всех».
    val chatBook = remember(environment) { SqlChatBook(environment.db, environment.cipher) }
    fun openPersonalChat(peerId: String, name: String?, phone: String?): String {
        val chatId = PersonalChatIdsOverKodium.personalChatId(session.userId, peerId)
        if (!environment.chatFacts.knows(chatId)) {
            chatBook.remember(
                chatId = chatId,
                kind = ChatKind.Personal,
                // Имя — то, что знаем сейчас; показывается оно всё равно по «Виду» (Д14),
                // а эта строка нужна отправке и странице, а не экрану.
                title = name?.takeIf { it.isNotBlank() } ?: phone,
                peerId = peerId,
            )
            Journal.note(LogCode.CHAT_PEER, "переписке дописан собеседник", "откуда" to "контакты")
        }
        return chatId
    }

    // ── ГРУППОВОЙ ЗВОНОК (ПЛАН-(ГЗ)-ГРУППОВЫХ-ЗВОНКОВ ГЗ5–ГЗ7) ──────────────────────
    //
    // Настройка, журнал звонка, полоса «Идёт звонок» и приглашения — у [GroupCallDesk];
    // сам звонок — у [CallHost]. Приглашение — обычное сообщение со ссылкой на группу в
    // личную переписку: формат конверта не меняется (Plan.md §0.0, решение 5), а войти по
    // ссылке может только участник группы — это проверяет сервер.
    val groupDesk = remember(assembled) {
        GroupCallDesk(
            calls = network.calls,
            groups = GroupsOverHttp(network.groups),
            createChat = CreateGroupChat(
                groups = GroupsOverHttp(network.groups),
                directory = network.directory,
                chats = SqlChatBook(environment.db, environment.cipher),
                rotator = { groupId, reason ->
                    if (assembled.keyOrchestrator.rotate(groupId, reason)) RotateStep.Rotated else RotateStep.Refused("ротация не удалась")
                },
            ),
            host = callHost,
            scope = scope,
            me = session.userId,
            sendTo = { userId, text -> environment.send.send(openPersonalChat(userId, null, null), text) },
            // Новая временная группа — сразу в «Чаты»: её срок узнаём сверкой групп.
            onCallGroupCreated = { scope.launch { runCatching { assembled.receiver.syncGroups() } } },
            onShowCall = { showCall() },
            invitedBefore = { groupId ->
                runCatching { environment.settings.all().first()[INVITED_PREFIX + groupId] }.getOrNull()
                    ?.split(',')?.filter { it.isNotBlank() }?.toSet().orEmpty()
            },
            rememberInvited = { groupId, who -> runCatching { environment.settings.put(INVITED_PREFIX + groupId, who.joinToString(",")) } },
            myTitle = {
                peopleCards[session.userId]?.let { me -> me.nick?.takeIf { it.isNotBlank() }?.let { "@$it" } ?: io.tima.domain.chat.selfName(me.userName) ?: me.name }
            },
        )
    }
    LaunchedEffect(groupDesk) {
        people.want(listOf(session.userId))
        callHost.myUserId = { session.userId }
        callHost.onStarted = { groupId, _, invited -> groupDesk.sendInvites(groupId, invited) }
        // Строки звонка в переписке группы — у себя, в переписку не уходят (8б).
        callHost.onGroupLine = { groupId, key, text -> runCatching { environment.journal.note(groupId, key, text, msNow()) } }
    }
    val callGroupsTtl by assembled.callGroups.collectAsState()
    // Сроки временных групп — при запуске, чтобы «удалится через» было видно сразу.
    LaunchedEffect(assembled) { runCatching { assembled.receiver.syncGroups() } }
    LaunchedEffect(assembled) {
        assembled.groupEvents.collect { (groupId, state) ->
            if (state == "deleted") groupDesk.live.remove(groupId) else groupDesk.refresh(groupId)
            groupDesk.inviteChanged(groupId, state, groupTitleOf(groupId))
        }
    }

    // Починка уже заведённых переписок без собеседника: у кого в книге есть
    // идентификатор, тому считается тот же `chat_id`, и если строки о переписке нет —
    // она дописывается. Разовая: после неё `knows` уже правда.
    LaunchedEffect(listState.chats, bookStateForChats.all) {
        val broken = listState.personal.filter { it.peerId == null }.map { it.chatId }.toSet()
        if (broken.isEmpty()) return@LaunchedEffect
        for (entry in bookStateForChats.all) {
            val id = entry.userId ?: continue
            val chatId = PersonalChatIdsOverKodium.personalChatId(session.userId, id)
            if (chatId !in broken) continue
            chatBook.remember(chatId, ChatKind.Personal, entry.name ?: entry.phone, id)
            Journal.note(LogCode.CHAT_PEER, "переписке дописан собеседник", "откуда" to "починка")
        }
    }
    /**
     * Человек за строкой книги: имя пользователя и ник — со справочника, имя — из книги
     * поверх них.
     *
     * Вынесено из места вызова, когда у книги появилась кнопка «позвонить» (ЗВ13): она
     * тоже должна звать человека так же, как зовёт его строка. Второй способ назвать
     * человека — ровно та беда, которую заказчик нашёл 2026-09-19 у «Чатов».
     */
    val personOfBook: (BookEntry) -> ChatPerson = { entry ->
        entry.userId?.let { people.want(listOf(it)) }
        (entry.userId?.let { peopleCards[it] } ?: ChatPerson()).withBookName(entry.name, entry.phone)
    }

    /**
     * Человек за идентификатором — **одним правилом** для вкладки «Звонки» и окна 0
     * (заказчик 2026-10-08): карточка сервера (как назвал себя, ник) и имя из книги поверх —
     * то же, что у «Чатов». До этого окно 0 брало только карточку, а «Звонки» — только книгу,
     * и незнакомый в журнале звался «Без имени».
     */
    val personOfId: (String) -> ChatPerson = { id ->
        people.want(listOf(id))
        val entry = bookStateForChats.all.firstOrNull { it.userId == id }
        (peopleCards[id] ?: ChatPerson()).withBookName(entry?.name, entry?.phone)
    }

    val mutedAll by (assembled.liveStates?.muted ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptySet<String>()) }).collectAsState()
    // Отметки и «печатает» для списка «Чаты» (ПЛАН-(ОП)); секунды — чтобы «печатает» гасло само.
    val liveReceipts by (assembled.liveStates?.receipts ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyMap<String, io.tima.feature.chat.ChatReceipt>()) }).collectAsState()
    val liveTyping by (assembled.liveStates?.typing ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyMap<String, Long>()) }).collectAsState()
    var liveTick by remember { mutableStateOf(msNow()) }
    LaunchedEffect(liveTyping) {
        liveTick = msNow()
        while (liveTyping.values.any { it > liveTick }) {
            kotlinx.coroutines.delay(1_000)
            liveTick = msNow()
        }
    }

    // Короткое слово во вкладке «Звонки»: «Чат удалён», «Звонок сейчас не идёт» — и гаснет.
    var callsNote by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(callsNote) {
        if (callsNote != null) {
            kotlinx.coroutines.delay(CALLS_NOTE_MS)
            callsNote = null
        }
    }

    val personOfChat: (ChatSummary) -> ChatPerson? = { chat ->
        chat.peerId?.takeIf { chat.kind == ChatKind.Personal }?.let { id ->
            people.want(listOf(id))
            val entry = entryOfChat(chat)
            (peopleCards[id] ?: ChatPerson()).withBookName(entry?.name, entry?.phone)
        }
    }
    // ── ГРУППА ЗВОНКА: СОЗДАТЕЛЬ ─────────────────────────────────────────────
    //
    // Заказчик 2026-10-02: имя человека (не ник), создавшего групповой звонок, — отдельной
    // строкой в окне звонка, в списках «Чаты» и «Группы», в шапке переписки; его аватар — с
    // «ГЗ» поверх. Создатель временной группы звонка — её владелец.
    // Владелец — из сверки групп при запуске; окно «Социум» знает его тоже, но загружается
    // только при открытии, и до того строки «кто создал» не было (проверка 2026-10-02).
    val callOwners by assembled.callOwners.collectAsState()
    // ДУ6: с номера начали заново (событие или проверка при запуске); заявки в группы.
    // Аттестация ключа телефона (ДУ8, Р22): один раз на установку — сервер в режиме «записывать»
    // видит, какие телефоны и прошивки проходят. Выключенный сервер ответит «пусто», и это не беда.
    // Вторая дорога — требование сервера у этого телефона (ПЛАН-(ЗБ)-ЗАЩИТЫ-ОТ-БОТОВ ЗБ1): под ним
    // сервер отказывает во всём, кроме аттестации, и отметка «уже аттестован» тогда не в счёт.
    val attestKey = "trust.attested.v2"
    suspend fun attestNow(at: DeviceAttester, why: String) {
        val token = network.directory.identityChallenge() ?: return
        val identity = deviceIdentityFrom(deviceSecret)
        val signed = io.tima.core.encryption.DeviceTrustCheck.deviceCertBytes(identity.encryptionPublic, identity.signingPublic)
        val proof = runCatching { at.attest(token, signed) }
            .onFailure { Journal.trouble(LogCode.DEVICE_TRUST, "аттестация не сделалась", "причина" to (it.message ?: "?"), "зачем" to why) }
            .getOrNull() ?: return
        val state = network.keys.sendAttestation(token, proof.chain, proof.signature)
        Journal.note(LogCode.DEVICE_TRUST, "аттестация отправлена", "итог" to (state ?: "не дошла"), "сертификатов" to proof.chain.size, "зачем" to why)
        // Отметка — только когда сервер её принял: не дошла или не прошла — повторим при запуске.
        if (state == "verified") runCatching { environment.settings.put(attestKey, "1") }
    }
    LaunchedEffect(assembled.session.userId, attester) {
        val at = attester ?: return@LaunchedEffect
        // v2: прежняя отметка ставилась и при неудаче — Honor тогда не прошёл из-за строгого
        // разбора сертификата на сервере, и повторять было некому.
        if (runCatching { environment.settings.all().first()[attestKey] }.getOrNull() != null) return@LaunchedEffect
        attestNow(at, "установка")
    }
    LaunchedEffect(assembled.session.userId, attester) {
        // Требований бывает очередь — на каждый отказанный запрос; проверка одна на пачку и не
        // чаще раза в полминуты: сама она секунды, а повторять её на каждом отказе — петля.
        var last = 0L
        io.tima.core.network.AttestationDemand.raised.collect { count ->
            if (count == 0L) return@collect
            val at = attester
            if (at == null) {
                // ПК: аттестации нет вовсе — требование к нему означает остановку, сказать один раз.
                if (last == 0L) Journal.trouble(LogCode.DEVICE_TRUST, "сервер требует аттестацию, а на этом устройстве её нет")
                last = 1L
                return@collect
            }
            val now = msNow()
            if (now - last < ATTEST_AGAIN_MS) return@collect
            last = now
            Journal.note(LogCode.DEVICE_TRUST, "сервер требует аттестацию телефона — прохожу")
            attestNow(at, "требование сервера")
        }
    }

    // Двойники из-за округлённого номера сообщения (сервер до 2026-10-02) — убрать один раз.
    LaunchedEffect(assembled.session.userId) {
        val key = "repair.rounded.v1"
        if (runCatching { environment.settings.all().first()[key] }.getOrNull() != null) return@LaunchedEffect
        val dropped = runCatching { io.tima.core.database.SqlMessageRepair(environment.db).dropRoundedTwins() }.getOrNull() ?: return@LaunchedEffect
        Journal.note(LogCode.DEVICE_TRUST, "двойники из-за округлённого номера убраны", "строк" to dropped)
        runCatching { environment.settings.put(key, "1") }
    }

    // История личных переписок (ИУ1, ИУ3): своё устройство передало — забрать; при первом
    // запуске — строки всех своих переписок и всё, что уже можно прочесть.
    LaunchedEffect(assembled.session.userId) {
        assembled.historyReady.collect { chat -> runCatching { assembled.receiver.pullHistory(chat, null) } }
    }
    LaunchedEffect(assembled.session.userId) {
        val key = "history.swept.v1"
        // История групп (ИУ3) — своей отметкой: устройства, прошедшие личную сверку до неё,
        // свежими уже не считаются и групп не забирают.
        val groupsKey = "history.groups.swept.v1"
        // «Свежее» решается один раз, до первой забранной страницы, и запоминается: после
        // личной истории переписки на устройстве уже есть, и второй раз решить было бы не по
        // чему. Не удалось забрать группы — попытка повторится при следующем запуске.
        val groupsPending = "history.groups.pending.v1"
        // Фраза, введённая при входе или выданная новому аккаунту, — копии ключей: вывести пару,
        // опубликовать открытый ключ, на телефоне сохранить секрет (модель Matrix, М1, Р46).
        KeyCopyPhrase.take()?.let { words -> runCatching { assembled.keyCopy?.onPhrase(words) } }
        // М6 (Р55): новая личность после перерегистрации — копию прежней в свою, прежнюю — в свои.
        PriorCopyPhrase.take()?.let { oldWords ->
            val prior = runCatching { network.directory.reregState() }.getOrNull()
                ?.takeIf { it.active && it.role == "new" }?.oldUserId?.takeIf { it.isNotBlank() }
            if (prior != null) {
                environment.priorIdentities.add(prior)
                val adopted = runCatching { assembled.keyCopy?.adoptPrior(oldWords, prior) }.getOrNull()
                Journal.note(LogCode.DEVICE_TRUST, "перенос копии прежней личности", "исход" to (adopted?.name ?: "сбой"))
            }
        }
        val marks = runCatching { environment.settings.all().first() }.getOrNull() ?: return@LaunchedEffect
        if (marks[key] == null) {
            // Только на свежем устройстве — без единой личной переписки. На работающем сверка
            // вернула бы в переписку то, что человек у себя удалил: сервер этого не знает.
            val fresh = runCatching { io.tima.core.database.SqlChatRehome(environment.db).personalPeers().isEmpty() }.getOrDefault(false)
            if (!fresh) {
                runCatching { environment.settings.put(key, "1") }
                runCatching { environment.settings.put(groupsKey, "1") }
                return@LaunchedEffect
            }
            runCatching { environment.settings.put(groupsPending, "1") }
            // Сначала копия ключей (М3): она даёт всё за срок хранения и ключи групп; передача и
            // обёртки за 90 дней — следом, на случай если копии нет.
            assembled.keyCopy?.copyIdentity()?.let { copy ->
                val fromCopy = runCatching { assembled.receiver.pullFromCopy(copy) }.getOrNull()
                Journal.note(LogCode.DEVICE_TRUST, "история из копии при первом запуске", "новых" to (fromCopy ?: -1))
            }
            val added = runCatching { assembled.receiver.pullAllHistory() }.getOrNull() ?: return@LaunchedEffect
            Journal.note(LogCode.DEVICE_TRUST, "история при первом запуске забрана", "новых" to added)
            runCatching { environment.settings.put(key, "1") }
        } else if (marks[groupsKey] == null && marks[groupsPending] == null) {
            runCatching { environment.settings.put(groupsKey, "1") }
            return@LaunchedEffect
        }
        if (marks[groupsKey] != null) return@LaunchedEffect
        val groupsAdded = runCatching { assembled.receiver.pullAllGroupHistory() }.getOrNull() ?: return@LaunchedEffect
        Journal.note(LogCode.DEVICE_TRUST, "история групп при первом запуске забрана", "новых" to groupsAdded)
        runCatching { environment.settings.put(groupsKey, "1") }
    }

    // Копия ключей заведена (этим устройством раньше или другим) — дозалить в неё то, что здесь
    // уже есть, один раз на эпоху (Р44). Позже сверки истории: свежее устройство сначала
    // поднимает своё из копии. Не вышло — повтор через пятнадцать минут.
    LaunchedEffect(assembled.session.userId) {
        val copy = assembled.keyCopy ?: return@LaunchedEffect
        kotlinx.coroutines.delay(60_000L)
        while (runCatching { copy.backfillOnce() }.getOrDefault(false).not()) kotlinx.coroutines.delay(15 * 60_000L)
    }

    // Один человек — одна переписка (ДУ6, Р26): при запуске и раз в пять минут — пока окно на
    // экране. Свёрнутое приложение не сверяет: композиция живёт и в фоне, и цикл будил телефон
    // каждые пять минут впустую (заказчик 2026-10-06, батарея). Вернулись позже — сверка сразу.
    LaunchedEffect(assembled.session.userId) {
        val chain = IdentityChain(environment, network.directory, assembled.session.userId, ::msNow)
        while (true) {
            assembled.notices.shown.first { it }
            runCatching { chain.refresh() }
            kotlinx.coroutines.delay(5 * 60_000L)
        }
    }
    val identityReplaced by assembled.identityReplaced.collectAsState()
    val deviceAdded by assembled.deviceAdded.collectAsState()
    val reregEvent by assembled.rereg.collectAsState()
    // Р54: идёт перерегистрация, а это прежняя личность (С) — собеседники пишут новому ключу,
    // и в личной переписке С предупреждается, что ответ придёт не ей. Узнаём при запуске и на
    // каждом событии перерегистрации.
    var reregOld by remember { mutableStateOf(false) }
    LaunchedEffect(assembled.session.userId, reregEvent) {
        reregOld = runCatching { network.directory.reregState() }.getOrNull()?.let { it.active && it.role == "old" } == true
    }
    // Это устройство не заверено, а сервер в «требовать» (отчёт QMTG, заказчик 2026-10-06):
    // собеседники его не видят, и человек узнаёт об этом событием с кнопкой, как о разрешениях.
    // Узнаём при запуске и по выходе из настроек — там его и заверяют. Сеть не ответила —
    // «не знаю», и событие не показывается.
    var uncertified by remember { mutableStateOf<Boolean?>(null) }
    val inSettings = where is Where.Settings
    LaunchedEffect(assembled.session.userId, inSettings) {
        if (inSettings) return@LaunchedEffect
        val own = (assembled.identity ?: deviceIdentityFrom(deviceSecret)).signingPublic
        val answer = runCatching { network.keys.devicesOf(assembled.session.userId) }.getOrNull()
        if (answer is io.tima.core.network.DeviceKeysResult.Devices) uncertified = OwnDeviceTrust.needsCertify(answer, own)
    }
    val identityClaims by assembled.identityClaims.collectAsState()
    LaunchedEffect(assembled.session.userId) {
        val me = assembled.session.userId
        val status = runCatching { network.directory.identities(listOf(me)) }.getOrNull()?.get(me)
        if (status != null && !status.current && !status.cancelled) assembled.identityReplaced.value = true
    }
    val callOwnerOf: (String) -> String? = { groupId ->
        groupId.takeIf { it in callGroupsTtl }?.let { gid ->
            callOwners[gid] ?: socialState.mine.firstOrNull { it.groupId == gid }?.ownerId?.ifBlank { null }
        }
    }
    // Имя человека: из книги, иначе из его карточки; ник — только если имени нет вовсе.
    val creatorNameOf: (String) -> String? = { id ->
        people.want(listOf(id))
        val card = peopleCards[id]
        bookStateForChats.all.firstOrNull { it.userId == id }?.name?.takeIf { it.isNotBlank() }
            ?: card?.name?.takeIf { it.isNotBlank() }
            ?: io.tima.domain.chat.selfName(card?.userName)
            ?: card?.nick?.takeIf { it.isNotBlank() }?.let { "@$it" }
    }
    val creatorFaceOf: (String) -> ImageBitmap? = { id ->
        people.wantFace(id)
        peopleFaces[id]
    }
    val callCreatorName: (String) -> String? = { groupId -> callOwnerOf(groupId)?.let(creatorNameOf) }
    val callCreatorFace: (String) -> ImageBitmap? = { groupId -> callOwnerOf(groupId)?.let(creatorFaceOf) }
    val callGroupOf: (ChatSummary) -> io.tima.feature.chat.CallGroupLook? = { chat ->
        if (chat.kind == ChatKind.Group && chat.chatId in callGroupsTtl) {
            io.tima.feature.chat.CallGroupLook(callCreatorName(chat.chatId), callCreatorFace(chat.chatId))
        } else {
            null
        }
    }
    val faceOfChat: (ChatSummary) -> ImageBitmap? = { chat ->
        chat.peerId?.takeIf { chat.kind == ChatKind.Personal }?.let { id ->
            people.wantFace(id)
            peopleFaces[id]
        }
    }
    // Янтарная цифра раздела — сколько людей раздела написали новое. Считается по личным
    // перепискам с новым **в журнале уведомлений** — том же, что у строк, вкладки и значка
    // приложения (ЖУ2). До 2026-10-05 считалось по `unread` базы, а в нём навсегда оставались
    // не открывшиеся сообщения: у раздела стояло 3, у строк и на значке — ничего (заказчик, Г).
    val newInSection: (String) -> Int = { key ->
        val id = if (key == COMMON_SECTION) "" else key
        val everyone = key.isEmpty() || key == ALL_SECTION
        listState.personal.count { chat -> noticeCounts.chat(chat.chatId, null) > 0 && (everyone || sectionOfChat(chat) == id) }
    }
    // Полоса разделов у личных переписок (Р4) — тем же сбором, что книга и сообщества:
    // все заведённые разделы, «Всё» и «Общий». До 2026-09-19 здесь стояло своё правило —
    // «только разделы, где есть переписка», — и заведённый раздел на полосе не появлялся.
    // Слова читаются ДО `remember`, а не внутри: внутри композиции нет, и `Tima.words`
    // там недоступен. Раньше вместо этого звался глобал `CurrentWords` — и, кроме
    // нарушения правила «глобал читается только в умолчании ссылки», это молча ломало
    // смену языка: словарь не был ключом, и полоса разделов оставалась на прежнем языке
    // до следующей пересборки по другой причине.
    val bookWords = Tima.words.book
    val chatSections: List<SectionTab> = remember(
        bookStateForChats.sections,
        bookStateForChats.common,
        listState.chats,
        bookWords,
    ) {
        sectionTabs(bookStateForChats.sections, bookStateForChats.common, bookWords)
    }
    val bookState by book.state.collectAsState()
    val newState by new.state.collectAsState()

    // Переписка начата — открываем её. Один и тот же признак ведёт и к открытию, и к
    // закрытию подокна: иначе они однажды разойдутся.
    LaunchedEffect(newState.started) {
        val chatId = newState.started ?: return@LaunchedEffect
        where = Where.Chat(chatId, newState.number)
        new.reset()
    }


    // Копия аккаунта между устройствами (Р2а, ЖУ9): книга и отметки «просмотрено до».
    // Один раз на сборку — `remember`, иначе каждая перерисовка заводила бы новый цикл.
    val accountCopy = remember(assembled) {
        BookCopySync(
            environment = environment,
            store = network.accountStore,
            keys = assembled.keyOrchestrator,
            deviceId = session.deviceId,
            scope = scope,
            // Копия аккаунта — одна отправка за сессию, забор по событию (ЖУ9).
            shown = assembled.notices.shown,
            changes = assembled.storeChanges,
            readState = environment.readState,
            onReadElsewhere = { chatId -> assembled.notices.viewed(chatId, "прочитано на другом устройстве") },
            readsStore = network.readsStore,
            // Вошли по фразе — попросить ключ служебной группы, подписав словами (2а).
            keyRequest = io.tima.core.network.GroupKeyRecoveryOverHttp(network.keyRecovery) { gid, words ->
                io.tima.core.encryption.RecoverySignature.sign(words, gid, session.deviceId)
            },
        ).also { it.start() }
    }

    // Фоновые циклы — в своём файле: это политика времени, а не навигация.
    //
    // Очередь убыла — сообщение ушло, сеть поднята: копия аккаунта уходит в тот же момент,
    // если в ней есть неотданное (ЖУ9). Иначе — в конце сессии.
    var pendingBefore by remember { mutableStateOf(-1) }
    BackgroundLoops(assembled, platform, changeSign = listState, onPending = { howMany ->
        if (pendingBefore > howMany) accountCopy.sessionEnded()
        pendingBefore = howMany
        onPending(howMany)
    })

    /**
     * Начать звонок откуда угодно — из шапки переписки, из книги, с личной страницы.
     *
     * **Одно место, а не три копии.** Звонок — это три шага подряд: позвать `CallHost`,
     * перейти в окно 0 и **закрыть подокно**. Забытый третий шаг и был бедой первого
     * живого звонка (ЗВ1): звонок шёл под открытой перепиской, и его не было видно.
     * Три копии этой тройки разошлись бы на первой же правке.
     */
    val callPerson: (String, String, Boolean) -> Unit = { peerId, name, video ->
        callHost.start(peerId, name, video)
        showCall()
    }

    // Свайп по средней зоне ведёт к соседнему окну в порядке переключателя, и ряд
    // **замкнут в кольцо**: с последнего окна свайп ведёт на первое и обратно.
    //
    // ── ЭТО ОБРАТНОЕ ПРЕЖНЕМУ РЕШЕНИЮ, И ВОТ ПОЧЕМУ ─────────────────────────
    //
    // Здесь стояло «края не заворачиваются: человек, дойдя до края, видит, что край
    // есть». Довод был не пустой, но его перевесило то, что временные окна стоят по
    // разным концам ряда: звонок первым, стенд последним. Без кольца из окна звонка до
    // стенда пять свайпов — ровно в тот момент, когда нужен один (заказчик 2026-09-21).
    //
    // Кольцо при этом ничего не прячет: переключатель окон показывает весь ряд целиком,
    // и где он кончается, видно там.
    val switchWindow: (InSide) -> Unit = { where_ ->
        // По показанным окнам, а не по перечню: окно 0 есть только во время звонка, и
        // свайпом в него попадать, когда его нет в переключателе, было бы странно.
        val order = Window.shown(callHost.active, benchState.on)
        val at = order.indexOf(window)
        if (at >= 0) {
            // Прибавляем размер до остатка: у отрицательного числа остаток в Kotlin
            // отрицателен, и свайп с первого окна ушёл бы в -1, то есть никуда.
            val step = if (where_ == InSide.Next) 1 else -1
            window = order[(at + step + order.size) % order.size]
            where = Where.Nothing
        }
    }

    // Переключатель окон лежит ПОВЕРХ стана, а не внутри колонки: он перекрывает
    // всё окно, включая главную область, и затемняет то, из чего его открыли.
    inviting?.let { person ->
        InviteScreen(
            person = person,
            // Текст один на все три способа: разница только в том, чем его понесут.
            onSms = { invite.sms(person.phone, INVITE_TEXT); inviting = null },
            onCall = { invite.call(person.phone); inviting = null },
            onShare = { invite.share(INVITE_TEXT); inviting = null },
            onClose = { inviting = null },
        )
        return
    }

    if (newContact) {
        // Лицо найденного качается по требованию, как у страницы человека.
        LaunchedEffect(contactsState.foundUserId) { contactsState.foundUserId?.let(people::wantFace) }
        NewContactScreen(
            state = contactsState,
            onPhone = contacts::changedPhone,
            onPhoneLeft = contacts::leftPhone,
            onCountryCode = contacts::changedCountryCode,
            onName = contacts::changedName,
            onSection = contacts::changedSection,
            // Закрытие — сохранили или ушли — чистит форму: иначе следующее открытие
            // показывало прошлого человека и работало как правка (заказчик 2026-09-26).
            onSave = { contacts.save { contacts.reset(); newContact = false } },
            onBack = { contacts.reset(); newContact = false },
            onOpenPerson = { userId ->
                contacts.reset()
                newContact = false
                where = Where.Person(userId)
            },
            onBy = contacts::chooseBy,
            face = contactsState.foundUserId?.let { peopleFaces[it] },
            // Завести раздел прямо из подокна контакта: человек уже набрал его имя, и
            // отсылать его за тем же именем в другое место значит набрать дважды.
            onCreateSection = contacts::createTypedSection,
            onNick = contacts::changedNick,
            onFindNick = contacts::searchNick,
            onPickNick = contacts::pickNick,
        )
        return
    }

    // Имя «Общего» — заранее и вне обработчиков: словарь читается композицией, а
    // перестановка разделов происходит в обычном обработчике нажатия.
    val commonName = Tima.words.book.commonSection

    if (sectionsScreen) {
        SectionsScreen(
            sections = bookState.sections,
            countOf = bookState::countIn,
            onAdd = book::addSection,
            onRename = book::renameSection,
            // Имя «Общего» уходит в store: если строки у него ещё нет, её заводят
            // при перестановке — переставить можно только записанное.
            onMove = { id, up -> book.moveSection(id, up, commonName = commonName) },
            onRemove = book::removeSection,
            onBack = { sectionsScreen = false },
            common = bookState.common,
            onRenameCommon = book::renameCommon,
        )
        return
    }

    // Управление набором сообществ — тот же экран, что у книги, другой набор.
    if (communitySectionsScreen) {
        val shelves = environment.communitySections
        SectionsScreen(
            sections = communityShelves,
            // «Общий» приходит своим ключом, а у групп он — ПУСТОЙ раздел: переводим.
            countOf = { key ->
                val id = if (key == COMMON_SECTION) "" else key
                listState.groups.count { it.sectionId == id }
            },
            onAdd = { name, icon -> scope.launch { shelves.add(name.trim(), icon) } },
            onRename = { id, name, icon -> scope.launch { shelves.rename(id, name.trim(), icon) } },
            onMove = { id, up ->
                // Ровно как у книги (`BookStore.moveSection`): «Общий» стоит в общем
                // порядке, места проставляются всему списку заново, а его строка заводится
                // только если после перестановки он перестал быть последним.
                val list = orderedSections(communityShelves, communityCommon, commonName)
                val at = list.indexOfFirst { it.id == id }
                val to = if (up) at - 1 else at + 1
                if (at >= 0 && to in list.indices) {
                    val moved = list.toMutableList().apply { add(to, removeAt(at)) }
                    scope.launch {
                        if (communityCommon == null && moved.last().id != COMMON_SECTION) {
                            shelves.setCommon(commonName, 0)
                        }
                        moved.forEachIndexed { place, section -> shelves.place(section.id, place) }
                    }
                }
            },
            onRemove = { id ->
                scope.launch { shelves.remove(id) }
                if (catalogSection == id) catalogSection = ""
                if (groupSection == id) groupSection = ""
            },
            onBack = { communitySectionsScreen = false },
            // Набор про сообщества: на полках группы, а не люди, и слова экрана — их.
            forPeople = false,
            common = communityCommon,
            onRenameCommon = { name, icon -> scope.launch { shelves.setCommon(name.trim(), icon) } },
        )
        return
    }

    if (communityViewSheet) {
        BookViewSheet(
            view = communityView,
            onChange = { changed ->
                communityView = changed
                scope.launch { changed.save(environment.settings, BookView.COMMUNITY) }
            },
            onClose = { communityViewSheet = false },
            onSections = {
                communityViewSheet = false
                communitySectionsScreen = true
            },
            forPeople = false,
        )
        return
    }

    // Выбор мелодии для выделенных в журнале контактов (ВЗ8) и выбранное сейчас — для
    // строки «♪ мелодия» у каждого.
    var ledgerSound by remember { mutableStateOf<List<BookEntry>?>(null) }
    var soundsNow by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val silentWord = Tima.words.settings2.soundSilent
    LaunchedEffect(Unit) { environment.settings.all().collect { soundsNow = it } }
    if (bookView) {
        Box(Modifier.fillMaxSize()) {
        BookViewSheet(
            view = bookState.view,
            onChange = book::changedView,
            onClose = { bookView = false },
            onSections = {
                bookView = false
                sectionsScreen = true
            },
            // У журнала звонков разделов нет: предлагать их там значило бы обещать
            // несуществующее.
            withSections = phoneTab != WindowTab.Calls,
            // Списки — только у контактов: в журнале звонков книги нет, и вход в неё
            // оттуда вёл бы в чужое окно.
            everyone = if (phoneTab == WindowTab.Calls) emptyList() else bookState.everyone,
            onPickList = book::movedTo,
            personOf = personOfBook,
            // Журнал контактов (ВЗ8): раздел, список и своя мелодия — нескольким сразу.
            sections = bookState.sections,
            onPickSection = { ids, sectionId ->
                scope.launch { ids.forEach { environment.bookStorage.moveTo(it, sectionId) } }
            },
            soundTitleOf = { entry ->
                entry.userId?.let { uid -> soundsNow[SoundKeys.ringOf(uid)] }
                    ?.takeIf { it.isNotBlank() }
                    ?.let { soundTitle(soundChoiceOf(it), silentWord) }
            },
            onOpenPerson = { entry ->
                entry.userId?.let {
                    bookView = false
                    where = Where.Person(it)
                }
            },
            onSound = { chosen -> ledgerSound = chosen },
            faceOf = { entry -> entry.userId?.let { people.wantFace(it); peopleFaces[it] } },
        )
        ledgerSound?.let { targets ->
            ContactSoundSheet(
                settings = environment.settings,
                targets = targets,
                onClose = { ledgerSound = null },
            )
        }
        }
        return
    }

    // Вид группового — один на окно 0, область 3 ПК и настройку звонка; выбор сохраняется
    // и становится видом следующего звонка (заказчик 2026-10-01).
    val groupView = remember(assembled) { io.tima.feature.call.GroupView() }
    LaunchedEffect(groupView) {
        runCatching { environment.settings.all().first()[GROUP_VIEW_KEY] }.getOrNull()?.let { groupView.restore(it) }
        snapshotFlow { groupView.saved() }.collect { runCatching { environment.settings.put(GROUP_VIEW_KEY, it) } }
    }
    // Новый звонок — с первой страницы, ничего не развёрнуто, места наверху заново.
    LaunchedEffect(callHost.group?.groupId) {
        groupView.page = 0
        groupView.expanded = null
        groupView.choosing = false
        groupView.eventsOpen = false
        groupView.eventsSeenLast = null
    }
    // Окна группового звонка — поверх всего: настройка и журнал звонка (ГЗ5, ГЗ6).
    val groupNameOf: (String) -> String = { uid ->
        if (uid == session.userId) {
            peopleCards[uid]?.line(PersonLook.DEFAULT, PERSON_FIRST_LINE)?.takeIf { it.isNotBlank() } ?: wordsNow.groupCall.stateSelf
        } else {
            people.want(listOf(uid))
            bookState.everyone.firstOrNull { it.userId == uid }?.let { personOfBook(it).line(bookState.view.look(), PERSON_FIRST_LINE) }
                ?: peopleCards[uid]?.line(bookState.view.look(), PERSON_FIRST_LINE)
                ?: wordsNow.chat.nameless
        }
    }
    val groupFaceOf: (String) -> ImageBitmap? = { uid -> people.wantFace(uid); peopleFaces[uid] }
    if (GroupCallOverlays(
            desk = groupDesk,
            host = callHost,
            me = session.userId,
            nameOf = groupNameOf,
            faceOf = groupFaceOf,
            book = bookState.everyone.mapNotNull { e ->
                val uid = e.userId?.takeIf { it != session.userId } ?: return@mapNotNull null
                val name = personOfBook(e).line(bookState.view.look(), PERSON_FIRST_LINE) ?: e.name ?: e.phone
                io.tima.feature.chat.CallCandidate(uid, name, lettersOf(name), groupFaceOf(uid), e.sectionId)
            }.distinctBy { it.userId },
            sections = bookState.sections,
            view = bookState.view,
            onShowCall = { showCall() },
            onOpenChat = { groupId, title ->
                where = Where.Chat(groupId, title)
                groupDesk.trouble = null
            },
            groupView = groupView,
        )
    ) return

    if (closeAsked && onExit != null) {
        CloseQuestionSheet(
            onClose = {
                closeAsked = false
                onExit()
            },
            onLeave = onLeave?.let { leave ->
                {
                    closeAsked = false
                    windowSwitcher = false
                    leave()
                }
            },
            onCancel = { closeAsked = false },
        )
        return
    }

    // Уход из аккаунта с непустой очередью — вопрос, а не сообщение (Д11). Оба ответа
    // законны: молча уйти значит соврать про «отправляется», молча ждать — задержать
    // того, кто спешит.
    val leaving = leavingTo
    if (leaving != null) {
        AccountLeavingSheet(
            howMany = unsent[session.userId] ?: 0,
            // «Подождать» ничего не запускает: отправка и так идёт, пока мы в аккаунте.
            // Обещать здесь «сейчас дошлём» значило бы обещать сеть.
            onWait = { leavingTo = null },
            onLeaveNow = {
                leavingTo = null
                windowSwitcher = false
                onSwitchAccount(leaving)
            },
            onClose = { leavingTo = null },
        )
        return
    }

    // ── ЖИВОЙ КАНАЛ ТОЖЕ УМЕЕТ СКАЗАТЬ «УСТАРЕЛО» ───────────────────────────
    //
    // Ручку версии спрашивают при запуске и по кнопке, а соединение живёт сутками.
    // Сборка, переставшая понимать кадры, до сих пор просто переставала получать
    // события — неотличимо от плохой сети. Теперь сервер отказывает словом, а мы по
    // этому слову перечитываем версию: порог показывает она, и двух мест, решающих
    // «пора обновиться», не заводим.
    val outdated by assembled.outdated.collectAsState()
    LaunchedEffect(outdated) { if (outdated) update.check() }

    // Порог совместимости (уровень 2, О5). Стоит раньше всего остального: сервер сказал,
    // что с этой сборкой больше не работает, и показывать список переписок значило бы
    // обещать доставку, которой не будет. Обходного пути нет намеренно — обходить нечего.
    LaunchedEffect(updateState.mustUpdate) {
        if (updateState.mustUpdate) Journal.note(LogCode.SCREEN_OPEN, "update.required")
    }
    if (updateState.mustUpdate) {
        UpdateGate(
            state = updateState,
            onInstall = update::ask,
            onConfirm = update::install,
            onDismiss = update::dismiss,
            canInstall = update.canInstall,
        )
        return
    }

    // ── СОБЫТИЯ — ОДНОЙ ОЧЕРЕДЬЮ (ПЛАН-(СБ)-СОБЫТИЙ, заказчик 2026-09-27) ─────────────
    //
    // Здесь только сведения: есть ли каждое событие — да, нет или ещё не знаю. Что
    // открыть, что в списке и что «новое», решает [EventQueue]. До 2026-09-27 каждое
    // событие было своим `if`, важность — порядком этих `if`, и отсюда пять бед плана:
    // мелькание, подмена под пальцем, возврат после переписки, неправдивый журнал, два
    // события подряд.
    //
    // После порога обновления, а не до: там работать нельзя вовсе.

    // Беды фона (ВЗ0г). Сверка событийная: запуск процесса, возврат окна, входящий
    // (`BackgroundWatch`), — каждая сверка обновляет сведения.
    var bgFacts by remember { mutableStateOf(backgroundFacts()) }
    DisposableEffect(Unit) {
        BackgroundWatch.onCheck = { bgFacts = it }
        onDispose { BackgroundWatch.onCheck = null }
    }
    // `null` — отметки «Позже» ещё не прочитаны. До 2026-09-27 здесь стоял пустой список,
    // и спрятанное на неделю мелькало при каждом запуске (Redmi, отчёт QN4N). Теперь
    // это «не знаю», и очередь такое событие не показывает.
    var bgLater by remember { mutableStateOf<Map<String, String>?>(null) }
    LaunchedEffect(Unit) { environment.settings.all().collect { bgLater = it } }
    // Исправленная беда забывает своё «Позже»: иначе, вернувшись, она молчала бы неделю.
    LaunchedEffect(bgFacts) {
        val fixed = buildList {
            if (bgFacts.notices == true) add(BackgroundTrouble.Notices)
            if (bgFacts.calls == true) add(BackgroundTrouble.Calls)
            if (bgFacts.awake == true) add(BackgroundTrouble.Battery)
        }
        val later = bgLater ?: environment.settings.all().first()
        for (t in fixed) {
            if (!later[bgLaterKey(t)].isNullOrEmpty()) environment.settings.put(bgLaterKey(t), "")
        }
    }

    // ВЗ0б: разрешение на уведомления спрашивается само — один раз на установку, окном
    // системы. **Пока вопрос не отвечен, событие про уведомления — «не знаю»** (заказчик
    // 2026-09-27): иначе под системным окном стояло бы наше «включите уведомления» — два
    // вопроса об одном. Отказали — событие ждёт следующего запуска: переспрашивать в ту же
    // секунду незачем.
    var noticesAsk by remember { mutableStateOf(NoticesAsk.Deciding) }
    LaunchedEffect(Unit) {
        val asked = environment.settings.all().first()[NOTICES_AUTO_ASKED]
        if (asked.isNullOrEmpty() && notifyAccessWay() == NotifyAccessWay.Ask) {
            noticesAsk = NoticesAsk.Asking
            environment.settings.put(NOTICES_AUTO_ASKED, "1")
            Journal.note(LogCode.BG_NOTICES, "спрашиваем разрешение на уведомления — первый запуск")
            askNotifyAccess {
                BackgroundWatch.check("ответ на разрешение уведомлений")
                noticesAsk = if (notifyAccessWay() == NotifyAccessWay.Given) NoticesAsk.Settled else NoticesAsk.DeniedNow
            }
        } else {
            noticesAsk = NoticesAsk.Settled
        }
    }

    val news = updateState.news
    // Факт установки пишется ЗДЕСЬ, а не в UpdateStore: оболочка про журнал не знает и
    // знать не должна — она зависит только от core-ui. Здесь же сходятся оба.
    LaunchedEffect(news) {
        when (news) {
            is UpdateNews.Installed -> Journal.note(
                LogCode.UPD_INSTALLED,
                "обновление встало",
                "версия" to news.versionName,
            )
            is UpdateNews.Broken -> Journal.trouble(
                LogCode.UPD_BROKEN,
                "установку начали и не довели",
                "хотели" to news.wanted,
                "осталось" to news.current,
            )
            else -> Unit
        }
    }

    // ── Сведения для очереди ───────────────────────────────────────────────────
    val later = bgLater
    fun bgPresence(fact: Boolean?, trouble: BackgroundTrouble): Presence = when {
        // `null` — платформе спрашивать нечего (ПК): беды нет, а не «не знаю».
        fact != false -> Presence.No
        later == null -> Presence.Unknown
        (later[bgLaterKey(trouble)]?.toLongOrNull() ?: 0L) > msNow() -> Presence.No
        else -> Presence.Yes
    }
    // «Установку не довели» и важное обновление — одна карточка (заказчик 2026-09-27):
    // действие у них одно. Важное известно только от сервера: пока он не ответил и
    // другого повода нет — «не знаю».
    val brokenNews = news as? UpdateNews.Broken
    val importantOffer = updateState.offer?.takeIf { updateState.important }
    val eventPresence: Map<EventKind, Presence> = mapOf(
        EventKind.Update to when {
            brokenNews != null || importantOffer != null -> Presence.Yes
            updateState.expect -> Presence.Unknown
            else -> Presence.No
        },
        EventKind.Notices to when (noticesAsk) {
            NoticesAsk.Deciding, NoticesAsk.Asking -> Presence.Unknown
            NoticesAsk.DeniedNow -> Presence.No
            NoticesAsk.Settled -> bgPresence(bgFacts.notices, BackgroundTrouble.Notices)
        },
        // Без уведомлений неважно, выключен ли канал: включать его человек пойдёт в то же
        // место и увидит сам.
        EventKind.Calls to if (bgFacts.notices == false) Presence.No else bgPresence(bgFacts.calls, BackgroundTrouble.Calls),
        EventKind.Battery to bgPresence(bgFacts.awake, BackgroundTrouble.Battery),
        EventKind.Installed to if (news is UpdateNews.Installed) Presence.Yes else Presence.No,
        // ДУ6: с номера начали заново — важнее всего; заявки новых личностей в группы.
        EventKind.IdentityReplaced to if (identityReplaced) Presence.Yes else Presence.No,
        EventKind.IdentityClaim to if (identityClaims.isNotEmpty()) Presence.Yes else Presence.No,
        // Р48: к аккаунту добавлено новое устройство.
        EventKind.DeviceAdded to if (deviceAdded != null) Presence.Yes else Presence.No,
        EventKind.Uncertified to when (uncertified) {
            true -> Presence.Yes
            false -> Presence.No
            null -> Presence.Unknown
        },
        // ДУ9: перерегистрация — извещение стороне.
        EventKind.Rereg to if (reregEvent != null) Presence.Yes else Presence.No,
    )
    var eventMemory by remember { mutableStateOf(EventMemory()) }
    // Перерегистрация (ДУ9) идёт шагами — запуск, спор, окно, исход, — и каждое следующее событие
    // обязано показаться, даже если прежнее уже закрыли: очередь помнит закрытое до конца
    // запуска, и живьём 2026-10-05 «окно открылось» и «исход» на ПК не показались.
    LaunchedEffect(reregEvent) {
        if (reregEvent != null) eventMemory = eventMemory.copy(closed = eventMemory.closed - EventKind.Rereg)
    }
    // Показывать — на главном экране и не во время звонка: окно поверх разговора его бы
    // закрыло, а поверх переписки — оторвало бы от неё. Нельзя — очередь просто ждёт.
    // Панель «Переключение окон» тоже ждём (заказчик 2026-09-27): человек в ней выбирает,
    // куда идти, и событие, закрывшее её собой, отняло бы этот выбор.
    val eventView = EventQueue.view(
        eventPresence,
        eventMemory,
        allowed = !callHost.active && where == Where.Nothing && !windowSwitcher,
    )

    // Журнал: состав очереди — при каждом изменении; «показано» — когда на экране.
    LaunchedEffect(eventView.waiting) {
        Journal.note(LogCode.NOTICE, "очередь событий", "стоят" to eventView.waiting.joinToString(", ") { it.name }.ifEmpty { "—" })
    }
    LaunchedEffect(eventView.current) {
        val shown = eventView.current ?: return@LaunchedEffect
        eventMemory = EventQueue.shown(eventMemory, eventView)
        Journal.note(LogCode.NOTICE, "событие показано", "что" to shown.name, "номер" to "${eventView.position} из ${eventView.total}")
    }

    val openEvent = eventView.current
    if (openEvent != null) {
        val warn = Tima.words.settings2
        val upd = Tima.words.update
        fun close(kind: EventKind, how: String) {
            Journal.note(LogCode.NOTICE, "событие закрыто", "что" to kind.name, "чем" to how)
            eventMemory = EventQueue.close(eventMemory, kind)
        }
        val authWords = Tima.words.auth
        val socialWords = Tima.words.social
        fun lineTitle(kind: EventKind): String = when (kind) {
            EventKind.IdentityReplaced -> authWords.replacedTitle
            EventKind.IdentityClaim -> socialWords.identityClaims
            EventKind.DeviceAdded -> authWords.deviceAddedTitle
            EventKind.Uncertified -> authWords.uncertifiedTitle
            EventKind.Rereg -> authWords.reregTitle
            EventKind.Update -> upd.importantOut.takeIf { importantOffer != null } ?: upd.broken
            EventKind.Notices -> warn.eventsLineNotices
            EventKind.Calls -> warn.eventsLineCalls
            EventKind.Battery -> warn.eventsLineBattery
            EventKind.Installed -> upd.installed
        }
        fun background(kind: EventKind, trouble: BackgroundTrouble, text: String) = NoticeEntry(
            notice = io.tima.feature.shell.Notice(title = warn.warnTitle, text = text, details = listOf(warn.warnWhere)),
            actions = listOf(
                NoticeAction(warn.warnFix) {
                    close(kind, "Включить")
                    where = Where.Settings(SettingsItem.PERMISSIONS)
                },
                NoticeAction(warn.warnLater, ButtonKind.Quiet) {
                    close(kind, "Позже — на неделю")
                    scope.launch {
                        environment.settings.put(bgLaterKey(trouble), (msNow() + BG_LATER_MS).toString())
                    }
                },
            ),
        )
        val entry: NoticeEntry = when (openEvent) {
            // ДУ6: «С вашего номера начали заново» — отменить фразой на экране «Устройства».
            EventKind.IdentityReplaced -> NoticeEntry(
                notice = io.tima.feature.shell.Notice(title = Tima.words.auth.replacedTitle, text = Tima.words.auth.replacedAbout),
                actions = listOf(
                    NoticeAction(Tima.words.auth.replacedCancel, ButtonKind.Dangerous) {
                        close(EventKind.IdentityReplaced, "Отменить")
                        where = Where.Settings(SettingsItem.DEVICES)
                    },
                    NoticeAction(Tima.words.auth.replacedItsMe, ButtonKind.Quiet) {
                        close(EventKind.IdentityReplaced, "Это я")
                    },
                ),
            )
            // ДУ9: перерегистрация — текст стороны по §2б, «Открыть» ведёт к панели.
            EventKind.Rereg -> NoticeEntry(
                notice = io.tima.feature.shell.Notice(
                    title = if (reregEvent?.kind?.startsWith("phone_") == true) Tima.words.auth.phoneChangeTitle else Tima.words.auth.reregTitle,
                    text = reregNoticeText(reregEvent, assembled.session.userId, Tima.words.auth),
                ),
                actions = listOf(
                    NoticeAction(Tima.words.auth.deviceAddedOpen) {
                        close(EventKind.Rereg, "Открыть устройства")
                        assembled.rereg.value = null
                        where = Where.Settings(SettingsItem.DEVICES)
                    },
                    NoticeAction(warn.eventsGotIt, ButtonKind.Quiet) {
                        close(EventKind.Rereg, "Понятно")
                        assembled.rereg.value = null
                    },
                ),
            )
            // Р48: новое своё устройство. Обычно это смена телефона; не вы — отключить.
            EventKind.DeviceAdded -> NoticeEntry(
                notice = io.tima.feature.shell.Notice(
                    title = Tima.words.auth.deviceAddedTitle,
                    text = Tima.words.auth.deviceAddedText(deviceAdded.orEmpty()),
                ),
                actions = listOf(
                    NoticeAction(Tima.words.auth.deviceAddedOpen) {
                        close(EventKind.DeviceAdded, "Открыть устройства")
                        assembled.deviceAdded.value = null
                        where = Where.Settings(SettingsItem.DEVICES)
                    },
                    NoticeAction(warn.eventsGotIt, ButtonKind.Quiet) {
                        close(EventKind.DeviceAdded, "Понятно")
                        assembled.deviceAdded.value = null
                    },
                ),
            )
            // Устройство не заверено — к «Секретной фразе и устройствам», там «Подтвердить
            // фразой» или код заверения для телефона. «Позже» — до следующего запуска, а не на
            // неделю, как у разрешений: без заверения переписка не идёт вовсе.
            EventKind.Uncertified -> NoticeEntry(
                notice = io.tima.feature.shell.Notice(title = authWords.uncertifiedTitle, text = authWords.uncertifiedText),
                actions = listOf(
                    NoticeAction(authWords.uncertifiedConfirm) {
                        close(EventKind.Uncertified, "Подтвердить фразой")
                        where = Where.Settings(SettingsItem.DEVICES)
                    },
                    NoticeAction(warn.warnLater, ButtonKind.Quiet) { close(EventKind.Uncertified, "Позже") },
                ),
            )
            // Заявка новой личности в группу — к составу группы, там «Подтвердить».
            EventKind.IdentityClaim -> NoticeEntry(
                notice = io.tima.feature.shell.Notice(title = Tima.words.social.identityClaims, text = Tima.words.social.identityClaimConfirm),
                actions = listOf(
                    NoticeAction(Tima.words.social.identityClaimConfirm) {
                        close(EventKind.IdentityClaim, "Открыть")
                        identityClaims.firstOrNull()?.let { g ->
                            assembled.identityClaims.value = assembled.identityClaims.value - g
                            where = Where.Members(g, null)
                        }
                    },
                    NoticeAction(warn.warnLater, ButtonKind.Quiet) { close(EventKind.IdentityClaim, "Позже") },
                ),
            )
            // Действие уводит туда, где обновление и живёт, — на вкладку настроек
            // (решение заказчика 2026-09-06): установки внутри события нет вовсе.
            EventKind.Update -> NoticeEntry(
                notice = if (importantOffer != null) {
                    io.tima.feature.shell.Notice(
                        title = upd.importantOut,
                        text = upd.availableVersion(importantOffer.versionName),
                        details = listOfNotNull(
                            brokenNews?.let { upd.brokenText(it.wanted, it.current.ifBlank { upd.version }) },
                            importantOffer.notes.takeIf { it.isNotBlank() }?.let { upd.whatChanged(it) },
                            upd.oldMayMisbehave,
                        ),
                    )
                } else {
                    (brokenNews ?: UpdateNews.Broken("", "", "")).notice(upd)
                },
                actions = listOf(
                    NoticeAction(warn.eventsGoToUpdate) {
                        close(EventKind.Update, "Перейти к обновлению")
                        update.dismissNews()
                        where = Where.Settings(SettingsItem.UPDATE)
                    },
                    NoticeAction(warn.warnLater, ButtonKind.Quiet) {
                        close(EventKind.Update, "Позже")
                        update.dismissNews()
                    },
                ),
            )
            EventKind.Notices -> background(openEvent, BackgroundTrouble.Notices, warn.warnNotices)
            EventKind.Calls -> background(openEvent, BackgroundTrouble.Calls, warn.warnCalls)
            EventKind.Battery -> background(openEvent, BackgroundTrouble.Battery, warn.warnBattery)
            // Установилось — решать нечего, и вести некуда.
            EventKind.Installed -> NoticeEntry(
                notice = (news as? UpdateNews.Installed ?: UpdateNews.Installed("", "")).notice(upd),
                actions = listOf(
                    NoticeAction(warn.eventsGotIt) {
                        close(EventKind.Installed, "Понятно")
                        update.dismissNews()
                    },
                ),
            )
        }
        NoticeQueueScreen(
            current = entry,
            position = eventView.position,
            total = eventView.total,
            rest = eventView.rest.map { line ->
                NoticeLine(lineTitle(line.kind), line.fresh) {
                    Journal.note(LogCode.NOTICE, "открыто из списка", "что" to line.kind.name)
                    eventMemory = EventQueue.open(eventMemory, line.kind)
                }
            },
            onNext = if (eventView.rest.isNotEmpty()) ({ close(openEvent, "Следующее") }) else null,
            onSkipAll = {
                Journal.note(LogCode.NOTICE, "пропущены все — до следующего запуска", "сколько" to eventView.waiting.size)
                eventMemory = EventQueue.skipAll(eventMemory)
            },
        )
        return
    }

    // Полученное у других аккаунтов — спрашивается при открытии панели (заказчик 2026-10-07): у
    // каждого свой вход, и сообщения им сейчас не приходят — число знает только сервер.
    var accountNews by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    LaunchedEffect(windowSwitcher) {
        if (!windowSwitcher) return@LaunchedEffect
        accounts.filter { it.userId != session.userId }.forEach { other ->
            launch {
                val n = runCatching { newsOf(other.userId) }.getOrNull() ?: return@launch
                accountNews = accountNews + (other.userId to n)
            }
        }
    }

    if (windowSwitcher) {
        // Подпись аккаунта — одна на всё приложение (2026-10-07): имя, @ник, служебное имя. У
        // текущего — из профиля: он свежее карточки.
        val accountTitles = accounts.map { account ->
            val card = cardOf(account.userId)
            account.userId to if (account.userId == session.userId) {
                io.tima.domain.account.AccountTitle.of(profileState.loadedName, profileState.savedNickname, account.userId)
            } else {
                io.tima.domain.account.AccountTitle.of(card?.name.orEmpty(), card?.nickname?.ifBlank { null } ?: account.nickname, account.userId)
            }
        }
        WindowSwitchingScreen(
            current = window,
            inCall = callHost.active,
            // Звонок первой строкой панели: окно 0 её не замещает, и без этой строки
            // входящий, пришедший при открытой панели, было нечем принять.
            onCall = if (callHost.active) {
                {
                    window = Window.Call
                    where = Where.Nothing
                    windowSwitcher = false
                }
            } else {
                null
            },
            callRinging = callHost.incoming && callHost.state.stage != CallStage.Connected &&
                callHost.state.stage != CallStage.Ended,
            callPeer = callHost.peer,
            bench = benchState.on,
            // Имя, ник и телефон — из профиля (0050). До этого здесь стояли заглушки:
            // userId вместо имени и «@» с восемью знаками id вместо ника.
            name = profileState.name.ifBlank { Tima.words.chat.nameless },
            alias = profileState.savedNickname.takeIf { it.isNotBlank() }?.let { "@$it" } ?: "",
            phone = profileState.phone,
            avatar = remember(profileState.avatarBytes) { profileState.avatarBytes?.let(::decodeImage) },
            counters = windowCounters(noticeCounts),
            onSelect = { selected ->
                window = selected
                where = Where.Nothing
                windowSwitcher = false
            },
            onSettings = {
                where = Where.Settings()
                windowSwitcher = false
            },
            // Значок и своё имя в шапке — на свою страницу «Я»; правка профиля — с неё.
            onProfile = {
                where = Where.SelfPage
                windowSwitcher = false
            },
            // Аккаунты — здесь же: это единственное место, где человек видит, от чьего
            // лица он в приложении, и менять это надо там же, где смотрят.
            accounts = accountTitles,
            currentAccount = session.userId,
            unsent = unsent,
            // У текущего — непрочитанное окон: оно уже посчитано для чисел на окнах выше.
            news = accountNews + (session.userId to windowCounters(noticeCounts).values.sum()),
            onAccount = { userId ->
                if ((unsent[session.userId] ?: 0) > 0) {
                    leavingTo = userId
                } else {
                    windowSwitcher = false
                    onSwitchAccount(userId)
                }
            },
            // Виртуальный не заводит виртуальных — сервер это отвергает (Д10), и
            // предлагать здесь то, что не сработает, нельзя.
            // «Выйти» — сразу, «Закрыть приложение» — через вопрос (заказчик 2026-09-30).
            onLeave = onLeave?.let { leave ->
                {
                    windowSwitcher = false
                    leave()
                }
            },
            onExit = onExit?.let { { closeAsked = true } },
            onNewAccount = if (accounts.none { it.userId == session.userId && it.virtual }) {
                {
                    windowSwitcher = false
                    where = Where.NewVirtual
                }
            } else {
                null
            },
            onClose = { windowSwitcher = false },
        )
        return
    }

    // Ширины полос, выставленные мышью (заказчик 2026-09-27), — в настройках устройства:
    // у другого ПК свой экран, синхронизировать тут нечего.
    var stageStored by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(Unit) { environment.settings.all().collect { stageStored = it } }

    // Плашка идущего звонка — во всех окнах, кроме самого звонка: предлагать «перейти в
    // звонок» тому, кто в нём стоит, незачем. Окно 0 временное, и без плашки оно
    // теряется: ушёл свайпом в «Чаты» — и не знаешь, разговор идёт или уже кончился.
    CompositionLocalProvider(
        LocalActiveCall provides ActiveCall(
            seconds = callHost.seconds,
            onOpen = { showCall() },
        ).takeIf { callHost.active && window != Window.Call },
    ) {
    // Групповой звонок для окна 0 и области 3 широкого формата (ГЗ4, ГЗ8).
    val forbiddenMics = callHost.group?.groupId?.let { gid ->
        groupDesk.live[gid]?.call?.members?.filter { it.micForbidden }?.map { it.userId }?.toSet()
    }.orEmpty()
    val forbiddenVideos = callHost.group?.groupId?.let { gid ->
        groupDesk.live[gid]?.call?.members?.filter { it.videoForbidden }?.map { it.userId }?.toSet()
    }.orEmpty()
    val groupStage = callHost.group?.let { g ->
        // Порядок — по входу (4б): вошедший в конец, ушедший выпадает.
        val order = callHost.peerOrder
        val peers = callHost.peers.collectAsState().value.sortedBy { p -> order.indexOf(p.identity).let { if (it < 0) Int.MAX_VALUE else it } }
        val me = session.userId
        val selfName = Tima.words.groupCall.stateSelf
        val tiles = listOf(
            io.tima.feature.call.GroupTile(
                key = "me", name = selfName,
                letters = lettersOf(peopleCards[me]?.line(PersonLook.DEFAULT, PERSON_FIRST_LINE) ?: selfName),
                video = callHost.localVideo.collectAsState().value,
                microphoneOn = callHost.state.microphoneOn, speaking = callHost.state.selfSpeaking, paused = false, self = true,
                cameraOn = callHost.state.cameraOn,
                userId = me,
                micForbidden = callHost.micForbidden,
                videoForbidden = callHost.videoForbidden,
                face = peopleFaces[me],
            ),
        ) + peers.map { p ->
            val name = peopleCards[p.userId]?.line(PersonLook.DEFAULT, PERSON_FIRST_LINE)
                ?: bookStateForChats.all.firstOrNull { it.userId == p.userId }?.let { it.name ?: it.phone }
                ?: Tima.words.chat.nameless
            io.tima.feature.call.GroupTile(
                key = p.identity, name = name, letters = lettersOf(name), video = p.video,
                microphoneOn = p.microphoneOn, speaking = p.speaking, paused = p.paused, self = false,
                // Показывает себя — клетка; нет — строка «голосом» (заказчик 2026-10-01).
                cameraOn = p.cameraOn,
                userId = p.userId,
                micForbidden = forbiddenMics.contains(p.userId),
                videoForbidden = forbiddenVideos.contains(p.userId),
                // Аватар — тот же, что в списках (заказчик 2026-10-02: были только буквы).
                face = peopleFaces[p.userId],
                // Пропажа видео — на клетке того, у кого пропало (заказчик 2026-10-01).
                bench = p.bench,
                incoming = p.incoming,
                videoTrouble = when (val loss = p.videoLoss) {
                    null -> null
                    io.tima.core.call.RemoteVideoLoss.NotArriving -> wordsNow.groupCall.tileVideoNotArriving
                    is io.tima.core.call.RemoteVideoLoss.NotDecoding -> wordsNow.groupCall.tileVideoNotDecoding(loss.codec)
                },
            )
        }
        LaunchedEffect(peers.map { it.userId }) {
            people.want(peers.map { it.userId }.filter { it != me })
            (peers.map { it.userId } + me).distinct().forEach { people.wantFace(it) }
        }
        io.tima.feature.call.GroupStage(
            title = g.title,
            // В окне — кто начал звонок; у группы звонка это и её создатель.
            creator = g.creatorId.ifBlank { null }?.let(creatorNameOf) ?: callCreatorName(g.groupId),
            creatorFace = g.creatorId.ifBlank { null }?.let(creatorFaceOf) ?: callCreatorFace(g.groupId),
            tiles = tiles,
            count = peers.distinctBy { it.userId }.size + 1,
            max = g.rules.max,
            paused = callHost.state.roomPaused,
            mine = g.mine,
            onParticipants = groupDesk::openLive,
            onStopAll = { callHost.control(io.tima.core.call.GroupControl.Stop) },
            view = groupView,
            pinnedKey = callHost.state.roomPinned.takeIf { it.isNotEmpty() }?.let { pin ->
                if (pin == me) "me" else peers.firstOrNull { it.userId == pin }?.identity
            },
            // «Голос» и «📌» — только у создателя (заказчик 2026-10-01).
            onVoice = if (g.mine) ({ t ->
                callHost.control(
                    if (t.micForbidden) io.tima.core.call.GroupControl.AllowMic else io.tima.core.call.GroupControl.MuteMic,
                    t.userId,
                )
                groupDesk.refreshSoon(g.groupId)
            }) else null,
            // Конец группового — своё окно: автор создаёт заново, остальные присоединяются,
            // пока звонок в группе идёт (заказчик 2026-10-02).
            onCreateAgain = { callHost.createAgain() },
            onJoinAgain = {
                groupDesk.live[g.groupId]?.call?.let { live -> groupDesk.join(live.callId, g.groupId, g.title) }
            },
            joinLive = groupDesk.live[g.groupId]?.call?.let { it.callId != callHost.state.callId } == true,
            onPin = if (g.mine) ({ t ->
                val pinnedNow = callHost.state.roomPinned == t.userId
                callHost.control(if (pinnedNow) io.tima.core.call.GroupControl.Unpin else io.tima.core.call.GroupControl.Pin, t.userId)
            }) else null,
        )
    }
    // Правило мест вида «Говорящий» — раз в полсекунды: замолчавший становится вытесняемым
    // по времени, а не по событию.
    val stageNow by rememberUpdatedState(groupStage)
    LaunchedEffect(callHost.group?.groupId, groupView.mode) {
        if (callHost.group == null || groupView.mode != io.tima.feature.call.GroupMode.Speaker) return@LaunchedEffect
        while (true) {
            val st = stageNow ?: break
            val peersKeys = st.tiles.filter { !it.self }.map { it.key }
            val order = peersKeys + "me"
            val speaking = st.tiles.filter { it.speaking }.map { it.key }.toSet()
            groupView.slots = groupView.speaker.update(order, speaking, st.pinnedKey, msNow())
            delay(500)
        }
    }
    // Запреты голоса — со слов сервера, пока идёт групповой: пузыри и журнал звонка.
    LaunchedEffect(callHost.group?.groupId) {
        val g = callHost.group ?: return@LaunchedEffect
        while (true) {
            groupDesk.refresh(g.groupId)
            delay(5_000)
        }
    }
    // Принимаем видео только видимых на странице (2а): ушла страница — отписка.
    val visibleNow = groupStage?.let { st ->
        io.tima.feature.call.groupVisible(io.tima.feature.call.groupPages(io.tima.feature.call.gridTiles(st.tiles, groupView), groupView.perPage), groupView)
    }
    LaunchedEffect(visibleNow) { visibleNow?.let { callHost.showPeers(it) } }
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
    Stage(
        modifier = Modifier.fillMaxSize(),
        sizes = StageSizes(
            railCaption = when (stageStored[STAGE_RAIL]) {
                RAIL_CAPTIONS -> true
                RAIL_ICONS -> false
                else -> null
            },
            column = stageStored[STAGE_COLUMN]?.toIntOrNull()?.dp,
        ),
        onSizes = { chosen ->
            scope.launch {
                chosen.railCaption?.let { environment.settings.put(STAGE_RAIL, if (it) RAIL_CAPTIONS else RAIL_ICONS) }
                chosen.column?.let { environment.settings.put(STAGE_COLUMN, it.value.roundToInt().toString()) }
            }
        },
        // Рейка есть только на широких форматах: на телефоне окна меняют подокном.
        // Решает это Стан — он и не позовёт рейку там, где её нет в раскладке.
        rail = { layout ->
            Rail(
                layout = layout,
                current = window,
                onSelect = { selected ->
                    window = selected
                    // Смена окна закрывает подокно: оно принадлежало прежнему окну.
                    where = Where.Nothing
                },
                counters = windowCounters(noticeCounts),
                onSettings = toSettings,
                inCall = callHost.active,
                bench = benchState.on,
                onExit = onExit?.let { { closeAsked = true } },
            )
        },
        column = {
            // Собеседник окна 0: (незнакомый ли, как назвать). `null` — звонок групповой
            // или собеседник не известен: тогда название из CallHost.
            val callPeer: Pair<Boolean, String>? = callHost.peerUserId.takeIf { window == Window.Call && it.isNotEmpty() && groupStage == null }?.let { id ->
                val who = personOfId(id)
                val look = bookStateForChats.view.look()
                if (bookStateForChats.all.none { it.userId == id }) {
                    true to look.order.filter { it == PersonField.UserName || it == PersonField.Nick }
                        .firstNotNullOfOrNull { who.field(it) }.orEmpty()
                } else {
                    false to (who.line(look, PERSON_FIRST_LINE) ?: callHost.peer)
                }
            }
            // Телефон и лицо собеседника окна 0 (заказчик 2026-10-08): номер второй строкой,
            // лицо — настоящим аватаром в голосовом звонке.
            val callPeerId = callHost.peerUserId.takeIf { window == Window.Call && it.isNotEmpty() && groupStage == null }
            val callPeerPhone = callPeerId?.let { personOfId(it).phone }
            val callPeerFace = callPeerId?.let { id ->
                people.wantFace(id)
                peopleFaces[id]
            }
            when (window) {
                // Окно 0 — звонок. Временное: пока идёт разговор. Экран чистый, всю
                // работу держит CallHost.
                Window.Call -> CallScreen(
                    state = callHost.state,
                    // Окно, поднятое строкой звонка, открывается раньше, чем приезжают
                    // карточки людей, — имя подтягивается, как только карточка есть.
                    // Имя собеседника — тем же правилом, что у «Контактов» и «Звонков»
                    // (заказчик 2026-10-08): книга поверх карточки, по «Виду». Кого нет в
                    // книге — «Незнакомый» и первое из имени или ника. Групповой звонок
                    // зовётся своим названием.
                    peer = callPeer?.second ?: callHost.peer,
                    stranger = callPeer?.first == true,
                    peerPhone = callPeerPhone,
                    peerFace = callPeerFace,
                    incoming = callHost.incoming,
                    peerRinging = callHost.delivered,
                    seconds = callHost.seconds,
                    // Лента событий и обе картинки. Дорожки приходят потоками, а не
                    // полем состояния: состояние сравнивается на равенство при каждой
                    // перерисовке, а у живой дорожки равенства нет.
                    events = callHost.events,
                    // Номер набора в забеге — им два телефона сверяются между собой.
                    // Показываем только при включённом стенде: обычному звонку это
                    // ничего не говорит.
                    // Полоса появляется только при нажатой «Начать прогон»: не нажата —
                    // звонок обычный, и номер прогона над лентой событий говорил бы о
                    // замере, которого нет.
                    bench = if (benchState.armed && benchState.total > 0) {
                        BenchLine(
                            at = benchState.at,
                            total = benchState.total,
                            preset = benchState.preset.name,
                            last = benchState.samples.lastOrNull(),
                            speakerOff = benchState.speakerOff,
                        )
                    } else {
                        null
                    },
                    remoteVideo = callHost.remoteVideo.collectAsState().value,
                    // Широкий формат: видео собеседника — в области 3, здесь кнопки и своё
                    // окошко (заказчик 2026-09-26). На телефоне области 3 нет — всё здесь.
                    remoteHere = LayoutLocal.current.phone,
                    localVideo = callHost.localVideo.collectAsState().value,
                    onRemoteVideo = callHost::remoteVideo,
                    // «Пожаловаться» (ПЛАН-(В)-ВИДЕО.md В7, В8): кадр собеседника — в момент
                    // нажатия, отчёт — о звонках, текст пишет человек.
                    onEventAction = { action ->
                        if (action == io.tima.core.call.CallAction.Report) {
                            scope.launch {
                                val frame = callHost.remoteFrame()?.let { io.tima.core.media.reportJpeg(it) }
                                problemPhotos = listOfNotNull(frame?.let { io.tima.feature.shell.ProblemPhoto("image/jpeg", it, fromCall = true) })
                                problemKind = io.tima.feature.shell.ProblemKind.Calls
                                problemDraft = ""
                                cameFrom = Origin(Window.Call)
                                where = Where.Settings(SettingsItem.PROBLEM)
                            }
                        } else {
                            callHost.act(action)
                        }
                    },
                    // Жест берётся тот же, что у остальных окон, и вешается ЗДЕСЬ:
                    // у пяти настоящих окон он живёт в их оправе, а окно 0 рисуется
                    // голым — оправы с шапкой и вкладками у звонка нет. Без этой
                    // строки окно 0 было окном, из которого нельзя выйти пальцем
                    // (живой прогон 2026-09-20, ЗВ2).
                    modifier = Modifier.windowSwipe(
                        onLeft = { switchWindow(InSide.Next) },
                        onRight = { switchWindow(InSide.Previous) },
                    ),
                    // «Принять» гасит строку звонка и мелодию сразу, не дожидаясь ленты.
                    // Нет движка (ПК без `livekit_ffi.dll`) — нет и кнопки: принять нечем,
                    // и обещать это нельзя (ПК0).
                    onAccept = if (callHost.possible) {
                        {
                            callHost.state.callId.takeIf { it.isNotEmpty() }?.let { assembled.notices.callOver(it) }
                            callHost.accept()
                        }
                    } else {
                        null
                    },
                    onDecline = callHost::hangUp,
                    onHangUp = callHost::hangUp,
                    onMicrophone = callHost::microphone,
                    onCamera = callHost::camera,
                    onSpeaker = callHost::speaker,
                    onSwitchCamera = callHost::switchCamera,
                    // Групповой — сетка участников, «👥» — журнал звонка (ГЗ4, ГЗ6).
                    group = groupStage,
                    onCallAgain = if (callHost.possible) callHost::again else null,
                    redialVideo = callHost.video,
                    onRedialKind = callHost::redialAs,
                    onClose = {
                        callHost.close()
                        window = Window.Phone
                    },
                )

                Window.Phone -> PhoneWindow(
                    tagOf = { chat ->
                        callGroupsTtl[chat.chatId]?.let { wordsNow.groupCall.ttl(((it - msNow()) / 3_600_000L).toInt().coerceAtLeast(0)) }
                    },
                    tab = phoneTab,
                    onTab = { phoneTab = it },
                    myUserId = session.userId,
                    callsState = callsState,
                    // Имя и лицо в журнале — тем же механизмом, что везде (Д14): сервер
                    // знает только `user_id`, а «Аня Борисова» живёт в книге.
                    // У группового — создатель звонка: его имя и телефон в строке (2026-10-08).
                    personOfCall = { record -> personOfId(if (record.group) record.initiatorId else record.other(session.userId)) },
                    faceOfCall = { record ->
                        val id = record.other(session.userId)
                        people.wantFace(id)
                        peopleFaces[id]
                    },
                    // Повтор звонит ТЕМ ЖЕ видом, каким звонили тогда (Ж6). Кнопка,
                    // молча звонящая голосом вместо видео, выглядит поломкой ровно один
                    // раз — а потом ей перестают верить.
                    // Строка группового звонка (заказчик 2026-10-08): аватар создателя с «ГЗ».
                    groupCallOf = { record ->
                        if (record.group) io.tima.feature.chat.GroupCallLine(face = creatorFaceOf(record.initiatorId)) else null
                    },
                    // Нажали на строку группового: чат звонка, а его нет — «Чат удалён».
                    onOpenGroupCall = { record ->
                        val chat = listState.chats.firstOrNull { it.chatId == record.groupId }
                        if (chat == null) callsNote = wordsNow.groupCall.chatDeleted
                        else where = Where.Chat(chat.chatId, chat.title)
                    },
                    callsNote = callsNote,
                    onCallAgain = if (callHost.possible) {
                        { record ->
                            // Кнопка группового: автору — повторить звонок, участнику — войти в
                            // идущий; чата нет — то же «Чат удалён», что у строки.
                            if (record.group) {
                                val chat = listState.chats.firstOrNull { it.chatId == record.groupId }
                                if (chat == null) {
                                    callsNote = wordsNow.groupCall.chatDeleted
                                } else {
                                    groupDesk.again(
                                        record.groupId, groupTitleOf(record.groupId), record.video,
                                        mine = record.initiatorId == session.userId,
                                    ) { callsNote = it }
                                }
                            } else {
                                val id = record.other(session.userId)
                                val name = personOfId(id).line(bookState.view.look(), PERSON_FIRST_LINE).orEmpty()
                                callPerson(id, name, record.video)
                            }
                        }
                    } else {
                        null
                    },
                    // Открыли «Звонки» — число вкладки обнуляется (ЖУ6): сущностей для
                    // просмотра там нет, всё и так увидели.
                    onOpenedCalls = {
                        callsLog.opened()
                        assembled.notices.callsViewed()
                    },
                    noticeCounts = noticeCounts,
                    // Имя пользователя и ник у контактов — со справочника, пачкой по разу.
                    personOf = personOfBook,
                    // Картинка аватара — по карточке справочника, приезжает потоком.
                    faceOf = { entry -> entry.userId?.let { people.wantFace(it); peopleFaces[it] } },
                    // ТОЛЬКО личные. Группы ушли на свою вкладку окна 5 — решение
                    // заказчика 2026-09-17; до него они стояли здесь вперемешку с
                    // личными, и это было временным размещением, записанным в
                    // `ИНТЕРФЕЙС/04-социум/ФУНКЦИОНАЛ.md`.
                    // Только личные. Группы звонка ушли во вкладку «Звонки» строкой с «ГЗ»
                    // (заказчик 2026-10-08: в «Чатах» они раздвоились).
                    list = listState.copy(chats = listState.personal),
                    // Ники — со справочника: в книге их нет, а искать по ним надо
                    // (Л19). У человека без номера ник — единственное, чем его найти.
                    book = bookState.copy(
                        nicks = peopleCards.mapNotNull { (id, card) -> card.nick?.let { id to it } }.toMap(),
                    ),
                    onSearchInBook = book::changedSearch,
                    personOfChat = personOfChat,
                    faceOfChat = faceOfChat,
                    callGroupOf = callGroupOf,
                    receiptOfChat = { chat -> liveReceipts[chat.chatId] },
                    typingOfChat = { chat -> (liveTyping[chat.chatId] ?: 0) > liveTick },
                    mutedKeys = mutedAll,
                    onOpen = { where = Where.Chat(it.chatId, it.title) },
                    // Открыть можно только того, кто в TIMa: у остальных переписки нет
                    // и завести её не из чего — им «Пригласить».
                    onOpenPerson = { person ->
                        val id = person.userId
                        if (id != null) {
                            where = Where.Chat(openPersonalChat(id, person.name, person.phone), person.name)
                        }
                    },
                    onNew = { where = Where.New },
                    onSettings = toSettings,
                    onSwitchWindows = { windowSwitcher = true },
                    onNeighbourWindow = switchWindow,
                    onView = { bookView = true },
                    onToggleSection = book::openedSection,
                    onChooseSection = book::choseSection,
                    onSections = { sectionsScreen = true },
                    sectionOfChat = sectionOfChat,
                    newInSection = newInSection,
                    chatSections = chatSections,
                    chatSection = chatSection,
                    onChooseChatSection = { chatSection = it },
                    onAddContact = { newContact = true },
                    // Аватар в строке книги ведёт на личную страницу; остальная строка —
                    // в переписку. Того, кого нет в TIMa, открывать нечем: страница
                    // принадлежит аккаунту, а не номеру.
                    onFacePerson = { entry -> entry.userId?.let { where = Where.Person(it) } },
                    onFaceUser = { id -> where = Where.Person(id) },
                    // Строка «Звонков» — переписка; имя — из книги, если человек в ней есть.
                    onOpenUser = { id ->
                        val entry = bookState.all.firstOrNull { it.userId == id }
                        where = Where.Chat(openPersonalChat(id, entry?.name, entry?.phone), entry?.name)
                    },
                    // Звонок из строки книги. Только тому, у кого есть аккаунт: остальным
                    // звонит системный набиратель из подокна «Пригласить», и это другой
                    // звонок (ЗВ13).
                    onCallPerson = if (callHost.possible) {
                        { entry ->
                            entry.userId?.let { peerId ->
                                callPerson(peerId, personOfBook(entry).line(bookState.view.look(), PERSON_FIRST_LINE).orEmpty(), false)
                            }
                        }
                    } else {
                        null
                    },
                    // Видеозвонок оттуда же и тем же путём — разница в одном доводе
                    // `video` (Ж3, решение заказчика 2026-09-23). Своего пути у него
                    // нет и быть не должно: два способа завести звонок разошлись бы
                    // на первой же правке одного из них.
                    onVideoCallPerson = if (callHost.possible) {
                        { entry ->
                            entry.userId?.let { peerId ->
                                callPerson(peerId, personOfBook(entry).line(bookState.view.look(), PERSON_FIRST_LINE).orEmpty(), true)
                            }
                        }
                    } else {
                        null
                    },
                    onInvite = { inviting = it },
                    onOpenedContacts = book::refresh,
                    // ── КНОПКА РАЗРЕШЕНИЯ НАЗЫВАЕТ СЕБЯ (Л1) ────────────────
                    //
                    // Путь спрашивается у платформы при составе, а не в момент
                    // нажатия: надпись обязана быть верной ДО того, как нажали.
                    // `None` — телефонной книги нет вовсе, и кнопки тогда нет.
                    onAllowContacts = if (contactsAccessWay() == ContactsAccessWay.None) {
                        null
                    } else {
                        { askContactsAccess { granted -> if (granted) book.refresh() } }
                    },
                    allowInSettings = contactsAccessWay() == ContactsAccessWay.Settings,
                    // Сверка по требованию (Л2). На ПК сверять неоткуда — кнопки нет.
                    onRefreshContacts = if (contactsAccessWay() == ContactsAccessWay.None) {
                        null
                    } else {
                        book::refresh
                    },
                )

                Window.Social -> {
                    // Открыли «Социум» — новое в каналах увидели (заказчик 2026-10-08).
                    LaunchedEffect(Unit) {
                        assembled.notices.channelsViewed()
                        assembled.liveStates?.channelsSeen()
                    }
                    // Списки обновляются при входе в окно: возвращаясь из группы, человек
                    // должен видеть её на месте, а не прежний снимок.
                    LaunchedEffect(Unit) { social.refresh() }
                    // Раздел группы — из местной базы (`chats.section_id`); сервер разделов
                    // не знает. Группы без строки в базе — общий раздел.
                    val sectionOfGroup: (String) -> String = { id ->
                        listState.groups.firstOrNull { it.chatId == id }?.sectionId.orEmpty()
                    }
                    // Тот же журнал уведомлений, что у строк (ЖУ2), — не `unread` базы.
                    val freshInCatalog: (String) -> Int = { key ->
                        val id = if (key == COMMON_SECTION) "" else key
                        val everyone = key.isEmpty() || key == ALL_SECTION
                        listState.groups.count { noticeCounts.chat(it.chatId, null) > 0 && (everyone || it.sectionId == id) }
                    }
                    val catalogTabs = sectionTabs(communityShelves, communityCommon, Tima.words.book)
                    SocialWindow(
                        onSwitchWindows = { windowSwitcher = true },
                        onSearch = {},
                        onSettings = toSettings,
                        onNeighbourWindow = switchWindow,
                        onCatalogView = { communityViewSheet = true },
                        tab = socialTab,
                        onTab = { socialTab = it },
                        // Полоса разделов (В/Г) — только в виде «полоса» и когда есть что выбирать.
                        catalogRow = if (!communityView.folders && communityShelves.isNotEmpty()) {
                            {
                                SectionsRow(
                                    tabs = catalogTabs,
                                    chosen = catalogSection,
                                    icons = communityView.icons,
                                    onPick = { catalogSection = it },
                                    newIn = freshInCatalog,
                                )
                            }
                        } else {
                            null
                        },
                        catalog = {
                            CatalogTab(
                                state = socialState,
                                countOf = { groupId -> noticeCounts.chat(groupId, null) },
                                onOpen = { where = Where.Chat(it.groupId, it.title) },
                                onNew = { where = Where.NewGroup },
                                onOpenCommunity = { where = Where.Community(it) },
                                // Разделы каталога — набор сообществ по «Виду» каталога.
                                layout = { groups, line ->
                                    CatalogSectioned(
                                        groups = groups,
                                        line = line,
                                        view = communityView,
                                        sections = communityShelves,
                                        sectionOf = sectionOfGroup,
                                        chosen = catalogSection,
                                        onChoose = { catalogSection = it },
                                        collapsed = catalogCollapsed,
                                        onToggle = { id ->
                                            catalogCollapsed = if (id in catalogCollapsed) catalogCollapsed - id else catalogCollapsed + id
                                        },
                                        freshIn = freshInCatalog,
                                        onSections = { communitySectionsScreen = true },
                                        common = communityCommon,
                                    )
                                },
                            )
                        },
                        friends = { FriendsTab(state = socialState, onAsk = social::ask) },
                    )
                }

                Window.Media -> MediaWindow(
                    onSwitchWindows = { windowSwitcher = true },
                    onSearch = {},
                    onSettings = toSettings,
                    onNeighbourWindow = switchWindow,
                )

                Window.Activity -> ActivityWindow(
                    onSwitchWindows = { windowSwitcher = true },
                    onSearch = {},
                    onSettings = toSettings,
                    onNeighbourWindow = switchWindow,
                )

                // Окно стенда — временное, как и окно 0: его видно, только пока
                // включён испытательный режим. Экран чистый, всю работу держит
                // BenchStore.
                Window.Bench -> {
                    // Наборы перечитываются при каждом открытии окна: файл могли
                    // заменить с ПК, и список обязан стать тем, что задали там.
                    LaunchedEffect(Unit) { bench.reload() }
                    BenchScreen(
                    preset = benchState.preset,
                    presets = benchState.presets,
                    running = benchState.running,
                    samples = benchState.samples,
                    runs = benchState.runs,
                    // Изменённый набор становится текущим сразу, без «Запомнить»: прогон
                    // начинают, покрутив ручки, а не сохранив их. Имя нужно только тем
                    // наборам, к которым вернутся.
                    inCall = callHost.state.stage == CallStage.Connected,
                    lastFile = benchState.lastFile,
                    skip = benchState.skip,
                    armed = benchState.armed,
                    onChange = bench::choose,
                    onSave = bench::save,
                    onForget = bench::forget,
                    // Порядок обязателен: прогон закрывается ДО перезахода в комнату.
                    // Внутри одного прогона не бывает двух наборов, иначе в отчёт
                    // попадёт одно число про минуту, где было два разных кодека.
                    onApply = {
                        bench.stop()
                        callHost.applyPreset()
                    },
                    onArm = bench::arm,
                    onStep = bench::step,
                    onSkip = bench::skipSeconds,
                    onStop = bench::stop,
                    probing = benchState.probing,
                    probeStep = benchState.probeStep,
                    probeFile = benchState.probeFile,
                    onProbe = bench::probe,
                    speakerOff = benchState.speakerOff,
                    onSpeakerOff = bench::speakerOff,
                    // Жест тот же, что у окна 0, и по той же причине: у стенда нет оправы
                    // с шапкой, а окно, из которого нельзя выйти пальцем, — не окно.
                    modifier = Modifier.windowSwipe(
                        onLeft = { switchWindow(InSide.Next) },
                        onRight = { switchWindow(InSide.Previous) },
                    ),
                    )
                }

                Window.Page -> PageWindow(
                    // Число «Групп» — сколько групп с новым (ЖУ2).
                    countOf = { which -> if (which == WindowTab.Groups) noticeCounts.tab(io.tima.domain.chat.NoticeTab.Groups) else 0 },
                    onSwitchWindows = { windowSwitcher = true },
                    onSearch = {},
                    onSettings = toSettings,
                    onNeighbourWindow = switchWindow,
                    // Своя страница: принесённое и своё вперемешку. Обновляется при
                    // открытии вкладки — список меняется от чужих действий (автор удалил,
                    // автор сузил), и держать его закэшированным значило бы показывать то,
                    // чего уже нет.
                    // Группы человека. Тот же список из базы, что у окна 1, только
                    // другого рода: второго похода в базу для этого не нужно.
                    groups = {
                        Column {
                            // Полоса разделов сообществ — тот же механизм, что у книги
                            // (В/Г по «Виду»), набор свой. Появляется, когда есть что выбирать.
                            val tabs = sectionTabs(communityShelves, communityCommon, Tima.words.book)
                            if (communityShelves.isNotEmpty()) {
                                SectionsRow(
                                    tabs = tabs,
                                    chosen = groupSection,
                                    // Значки или слова — по «Виду» набора сообществ, как в каталоге.
                                    icons = communityView.icons,
                                    onPick = { groupSection = it },
                                    newIn = { key ->
                                        val id = if (key == COMMON_SECTION) "" else key
                                        val everyone = key.isEmpty() || key == ALL_SECTION
                                        listState.groups.count { noticeCounts.chat(it.chatId, null) > 0 && (everyone || it.sectionId == id) }
                                    },
                                )
                            }
                            GroupsScreen(
                                state = listState,
                                onOpen = { where = Where.Chat(it.chatId, it.title) },
                                chosen = groupSection,
                                countOf = { chat -> noticeCounts.chat(chat.chatId, null) },
                                tagOf = { chat ->
                                    callGroupsTtl[chat.chatId]?.let { wordsNow.groupCall.ttl(((it - msNow()) / 3_600_000L).toInt().coerceAtLeast(0)) }
                                },
                                callGroupOf = callGroupOf,
                                mutedOf = { chat -> "group:${chat.chatId}" in mutedAll },
                            )
                        }
                    },
                    feed = {
                        val state by page.state.collectAsState()
                        LaunchedEffect(Unit) { page.refresh() }
                        PageScreen(
                            state = state,
                            onRemove = page::remove,
                            onComments = { postId ->
                                // Канал страницы приходит вместе с ней: адрес разговора —
                                // канал и запись, и вычислять его здесь было бы вторым
                                // источником того же знания.
                                if (state.channelId.isNotBlank()) {
                                    where = Where.Comments(state.channelId, postId)
                                }
                            },
                            // Своя страница — значит выключатели свои. На чужой их нет
                            // вовсе: PageStore получает их только для «me».
                            onPageComments = page::commentsOnPage,
                            onPostComments = { postId, closed -> page.commentsOnPost(postId, closed) },
                            onCloseTrouble = page::troubleDismissed,
                        )
                    },
                )
            }
        },
        // Видео собеседника на широком формате — в области 3, пока открыто окно звонка.
        // Групповой — сетка участников там же, где видео собеседника (ГЗ8: ПК).
        wideMain = if (groupStage != null && window == Window.Call && callHost.state.stage == CallStage.Connected) {
            { io.tima.feature.call.GroupCallBody(groupStage, groupView, withSelf = false, modifier = Modifier.fillMaxSize()) }
        } else {
            callHost.remoteVideo.collectAsState().value
                ?.takeIf { window == Window.Call }
                ?.let { remote -> { CallVideo(remote, Modifier.fillMaxSize()) } }
        },
        main = when (val current = where) {
            Where.Nothing -> null

            is Where.Transfer -> {
                {
                    LaunchedEffect(current) {
                        // Сторона задаётся тем, откуда пришли, а не переключателем на
                        // экране: «передаю» и «принимаю» — разные намерения, и путать их
                        // здесь дороже всего.
                        val virtualId = current.virtualUserId
                        if (virtualId != null) transfer.giveCode(virtualId) else transfer.takingSide(current.brought)
                    }
                    TransferScreen(
                        state = transferState,
                        onGiveCode = { current.virtualUserId?.let(transfer::giveCode) },
                        onCancel = { transfer.cancel() },
                        onCode = transfer::changedCode,
                        onPhrase = transfer::changedPhrase,
                        onTake = transfer::take,
                        onBack = { where = Where.Settings(SettingsItem.VIRTUALS) },
                        onDone = {
                            where = Where.Nothing
                            transferState.taken?.let(onTransferTaken)
                        },
                    )
                }
            }

            is Where.Community -> {
                {
                    val store = remember(current) {
                        CommunityStore(network.communities, scope, current.communityId)
                    }
                    val communityState by store.state.collectAsState()
                    LaunchedEffect(current) { store.refresh() }
                    CommunityScreen(
                        state = communityState,
                        onBack = { where = Where.Nothing },
                        onSubscribe = store::subscribe,
                        // Элемент состава открывается тем же экраном, что и обычно:
                        // сообщество ничем не владеет, и «открыть группу изнутри» — это
                        // просто открыть группу.
                        onOpenItem = { item ->
                            if (item.kind == CommunityKinds.GROUP) {
                                where = Where.Chat(item.id, item.title)
                            }
                        },
                        // Вносить и вынимать может владелец: у остальных кнопок нет
                        // вовсе, а не «есть, но отвергается».
                        onLink = if (communityState.owner) store::link else null,
                        onUnlink = if (communityState.owner) store::unlink else null,
                    )
                }
            }

            is Where.Comments -> {
                {
                    val comments = remember(current) {
                        CommentsStore(network.comments, scope, current.channelId, current.postId)
                    }
                    val commentsState by comments.state.collectAsState()
                    LaunchedEffect(current) { comments.refresh() }
                    CommentsScreen(
                        state = commentsState,
                        // Имя берётся из книги: экран её не читает сам, иначе у каждого
                        // экрана завёлся бы свой способ звать человека.
                        nameOf = { userId ->
                            bookState.all.firstOrNull { it.userId == userId }?.name
                                // Имени нет — показываем короткий номер, а не пустоту:
                                // строка без подписи читается как поломка списка.
                                ?: userId.take(8)
                        },
                        onBack = { where = Where.Nothing },
                        onDraft = comments::draft,
                        onSend = comments::send,
                        onReply = comments::reply,
                        onCloseTrouble = comments::closeTrouble,
                    )
                }
            }

            Where.NewVirtual -> {
                {
                    NewVirtualScreen(
                        state = newVirtualState,
                        onNickname = newVirtual::changedNickname,
                        onNext = newVirtual::toPhrase,
                        onPhrase = newVirtual::changedPhrase,
                        onConfirm = newVirtual::confirm,
                        onBack = {
                            // «Назад» с первого шага закрывает экран, со второго —
                            // возвращает к нику. Решает это магазин: он знает, какой
                            // шаг открыт, а навигация — нет.
                            if (newVirtualState.step == NewVirtualStep.Nickname) {
                                where = Where.Nothing
                            } else {
                                newVirtual.back()
                            }
                        },
                        onDone = {
                            // Слова записаны. Дальше вход в заведённый аккаунт: список и
                            // секреты — уровнем выше, здесь только сказать, что готово.
                            where = Where.Nothing
                            newVirtualState.created?.let(onVirtualCreated)
                        },
                    )
                }
            }

            Where.SelfPage -> {
                {
                    SelfPageScreen(
                        state = profileState,
                        onBack = { where = Where.Nothing },
                        onEdit = { where = Where.Profile },
                        face = remember(profileState.avatarBytes) { profileState.avatarBytes?.let(::decodeImage) },
                    )
                }
            }

            Where.Profile -> {
                {
                    ProfileScreen(
                        state = profileState,
                        onName = profile::changedName,
                        onNickname = profile::changedNickname,
                        onSave = profile::save,
                        onBack = { where = Where.Nothing },
                        onAvatar = profile::croppedAvatar,
                        onAvatarRemove = profile::removeAvatar,
                    )
                }
            }

            Where.New -> {
                {
                    NewChatScreen(
                        state = newState,
                        onNumber = new::changedNumber,
                        onCountryCode = new::changedCountryCode,
                        onFind = new::find,
                        onBack = {
                            where = Where.Nothing
                            new.reset()
                        },
                    )
                }
            }

            is Where.Settings -> {
                {
                    Settings(
                        // «Отключённые» — названия по списку переписок (ПЛАН-(ОУ)).
                        mutedList = mutedAll.map { key ->
                            val id = key.substringAfter(':')
                            val chat = listState.chats.firstOrNull { it.chatId == id }
                            key to (chat?.let { c -> personOfChat(c)?.line(bookStateForChats.view.look(), io.tima.feature.chat.PERSON_FIRST_LINE) ?: c.title } ?: id.take(8))
                        },
                        onUnmute = { key -> assembled.liveStates?.mute(key.substringBefore(':'), key.substringAfter(':'), false) },
                        pin = pin,
                        opened = current.item,
                        onOpen = { where = Where.Settings(it) },
                        onSignOut = onSignOut,
                        onRereg = onRereg,
                        onScanCode = onScanCode,
                        accountCopy = accountCopy,
                        network = network,
                        scope = scope,
                        platform = platform,
                        build = build,
                        appearance = appearance,
                        onAppearance = onAppearance,
                        deviceTrust = remember(askSecrets) {
                            DeviceTrustActionsOverNetwork(
                                keys = network.keys,
                                users = network.directory,
                                sms = network.sms,
                                onPhrase = { w -> assembled.keyCopy?.onPhrase(w) },
                                keyCopy = assembled.keyCopy,
                                userId = assembled.session.userId,
                                identity = deviceIdentityFrom(deviceSecret),
                                asks = askSecrets,
                                phone = platform.server in io.tima.domain.account.PHONES,
                                // Заверенному по коду — ключи групп и история переписок (ИУ2).
                                onCertified = { id, pub ->
                                    scope.launch {
                                        runCatching { assembled.keyOrchestrator.handOver(id, pub) }
                                        HistoryHandover(network.history, assembled.session.deviceId, assembled.identity ?: deviceIdentityFrom(deviceSecret), network.keyCopy, assembled.keyCopy?.copyIdentity()).handOver(id, pub)
                                    }
                                },
                            )
                        },
                        language = language,
                        onLanguage = onLanguage,
                        locale = locale,
                        onIdentityRestored = { assembled.identityReplaced.value = false },
                        onBack = { where = Where.Nothing },
                        profile = profile,
                        profileState = profileState,
                        virtuals = virtuals,
                        virtualsState = virtualsState,
                        onNewVirtual = { where = Where.NewVirtual },
                        accountRows = accountRows(accounts, session.userId, profileState, cardOf, virtualsState),
                        onTransfer = { userId -> where = Where.Transfer(userId) },
                        update = update,
                        updateState = updateState,
                        bench = bench,
                        benchState = benchState,
                        problemFacts = facts.copy(
                            build = build.name,
                            stream = build.stream,
                            nickname = profileState.nickname,
                            userId = session.userId,
                            deviceId = session.deviceId,
                            signedIn = session.accessToken.isNotBlank(),
                        ),
                        origin = cameFrom,
                        problemDraft = problemDraft,
                        problemPhotos = problemPhotos,
                        problemKind = problemKind,
                        reporting = reporting,
                        diaryPolicy = diaryPolicy,
                        callsLog = callsLog,
                        callsState = callsState,
                        deviceSettings = environment.settings,
                        callDevices = callEngine as? io.tima.core.call.CallDevices,
                        loginStart = loginStart,
                        // Снимок считается ЗДЕСЬ и в момент открытия экрана: человек
                        // жалуется тогда, когда у него не работает, — это и есть нужный
                        // момент. Собрать его может только сборка: у неё есть и токен, и
                        // очередь, и платформа.
                        snapshot = {
                            Snapshot(
                                auth = network.tokenKeeper?.words() ?: "неизвестно",
                                queued = unsent[session.userId] ?: 0,
                                // Фон как есть сейчас (ВЗ0в): уведомления, канал «Звонки»,
                                // экономия батареи, сколько живёт служба канала.
                                permissions = io.tima.core.notify.BackgroundWatch.describe(),
                                sessionFor = startedWords(),
                                events = EventQueue.describe(
                                    presence = eventPresence,
                                    memory = eventMemory,
                                    laterUntil = mapOf(
                                        EventKind.Notices to BackgroundTrouble.Notices,
                                        EventKind.Calls to BackgroundTrouble.Calls,
                                        EventKind.Battery to BackgroundTrouble.Battery,
                                    ).mapNotNull { (kind, trouble) ->
                                        bgLater?.get(bgLaterKey(trouble))?.toLongOrNull()?.let { kind to it }
                                    }.toMap(),
                                    now = msNow(),
                                ),
                            )
                        },
                    )
                }
            }

            is Where.Certify -> {
                {
                    val certifier = remember(askSecrets) {
                        io.tima.feature.auth.DevicesStore(
                            network.myFleet, scope,
                            trust = DeviceTrustActionsOverNetwork(
                                keys = network.keys,
                                users = network.directory,
                                sms = network.sms,
                                onPhrase = { w -> assembled.keyCopy?.onPhrase(w) },
                                keyCopy = assembled.keyCopy,
                                userId = assembled.session.userId,
                                identity = deviceIdentityFrom(deviceSecret),
                                asks = askSecrets,
                                phone = platform.server in io.tima.domain.account.PHONES,
                                // Заверенному по коду — ключи групп и история переписок (ИУ2).
                                onCertified = { id, pub ->
                                    scope.launch {
                                        runCatching { assembled.keyOrchestrator.handOver(id, pub) }
                                        HistoryHandover(network.history, assembled.session.deviceId, assembled.identity ?: deviceIdentityFrom(deviceSecret), network.keyCopy, assembled.keyCopy?.copyIdentity()).handOver(id, pub)
                                    }
                                },
                            ),
                        )
                    }
                    val cs by certifier.state.collectAsState()
                    io.tima.feature.auth.CertifyScreen(
                        busy = cs.trusting,
                        notice = cs.trustNotice,
                        onCertify = { certifier.certifyByCode(current.code) },
                        onCancel = { where = Where.Nothing },
                    )
                }
            }

            is Where.Link -> {
                {
                    LinkConfirmation(
                        network = network,
                        deviceSecret = deviceSecret,
                        signingKey = askSecrets.get(),
                        code = current.code,
                        scope = scope,
                        onClose = { where = Where.Nothing },
                        // Ключи групп — новому устройству в том же нажатии (ЖУ8); история личных
                        // переписок — следом, в фоне: страниц может быть много (ИУ2).
                        onTrusted = { id, pub ->
                            assembled.keyOrchestrator.handOver(id, pub)
                            scope.launch {
                                HistoryHandover(network.history, assembled.session.deviceId, assembled.identity ?: deviceIdentityFrom(deviceSecret), network.keyCopy, assembled.keyCopy?.copyIdentity()).handOver(id, pub)
                            }
                        },
                    )
                }
            }

            is Where.Chat -> {
                {
                    Chat(
                        liveStates = assembled.liveStates,
                        onPullGroupNew = { id -> assembled.receiver.pullGroupNew(id) },
                        myDeviceId = assembled.session.deviceId,
                        reregWarn = reregOld,
                        notices = assembled.notices,
                        shelves = communityShelves,
                        currentShelf = listState.chats.firstOrNull { it.chatId == current.chatId }?.sectionId ?: "",
                        onMoveToShelf = { chatId, sectionId ->
                            scope.launch { environment.communitySections.moveTo(chatId, sectionId) }
                        },
                        heal = assembled.keyOrchestrator.heal,
                        environment = environment,
                        network = network,
                        people = people,
                        authorLook = communityView.look(),
                        // Владелец — из списка групп; список свежий: он сверяется при входе
                        // в Социум и при открытии группы ниже.
                        ownerId = socialState.mine.firstOrNull { it.groupId == current.chatId }?.ownerId?.ifBlank { null },
                        hues = peopleHues[current.chatId].orEmpty(),
                        kind = socialState.mine.firstOrNull { it.groupId == current.chatId }?.kind,
                        myUserId = session.userId,
                        reopenUnreadable = { assembled.receiver.reopenUnreadable() },
                        // Вид группы — один на сообщества (2026-10-07); нажатие на аватар — страница.
                        avatarLook = communityView.avatarLook,
                        onAuthor = { id -> where = Where.Person(id) },
                        myName = profileState.name.ifBlank { profileState.nickname.ifBlank { session.userId.take(2) } },
                        // Список групп (владелец) и участники (цвета) — при открытии группы,
                        // чтобы полосы стояли верно с первого кадра, а не после чьего-то сообщения.
                        onOpened = { chatId ->
                            social.refresh()
                            scope.launch {
                                (network.groups.members(chatId) as? MembersResult.Members)?.let { answer ->
                                    people.setHues(chatId, answer.members.mapNotNull { m -> m.hue?.let { m.userId to it } }.toMap())
                                }
                            }
                        },
                        onMyColor = { chatId, hue, done ->
                            // Слова — снаружи корутины: внутри неё @Composable недоступны.
                            val taken = wordsNow.chat.myColorTaken
                            val retry = wordsNow.trouble.retryIn(5)
                            scope.launch {
                                when (val outcome = network.groups.setMyHue(chatId, hue)) {
                                    MemberResult.Done -> {
                                        people.setHue(chatId, session.userId, hue)
                                        done(null)
                                    }
                                    is MemberResult.Refused -> done(if (outcome.code == "hue_taken") taken else outcome.code)
                                    is MemberResult.NoConnection -> done(retry)
                                    else -> done(outcome.toString())
                                }
                            }
                        },
                        // Личная переписка: раздел — у собеседника в книге (Р4), меню «•••»
                        // переносит его туда же (заказчик 2026-09-18).
                        bookSections = bookStateForChats.sections,
                        currentBookSection = listState.chats.firstOrNull { it.chatId == current.chatId }?.let(sectionOfChat) ?: "",
                        onMoveToBookSection = { chatId, sectionId ->
                            val peer = listState.chats.firstOrNull { it.chatId == chatId }?.peerId
                            val entry = bookStateForChats.all.firstOrNull { it.userId != null && it.userId == peer }
                            scope.launch {
                                if (entry != null) {
                                    environment.bookStorage.moveTo(entry.id, sectionId)
                                } else {
                                    // Собеседника в книге нет — раздел положить некуда. Номер
                                    // собеседника сервер отдаёт: заводим его в книге вручную,
                                    // сразу в выбранном разделе. Номера нет — перенос молчит.
                                    val phone = peer?.let { people.person(it).phone }
                                    if (phone != null) environment.bookStorage.addManually(phone, null, sectionId)
                                }
                            }
                        },
                        chatId = current.chatId,
                        // Нажатие на аватар с именем в шапке — личная страница собеседника.
                        // Только у личной переписки: за шапкой группы не один человек.
                        onPerson = listState.chats.firstOrNull { it.chatId == current.chatId }
                            ?.takeIf { it.kind == ChatKind.Personal }
                            ?.peerId
                            ?.let { peerId -> { where = Where.Person(peerId) } },
                        // Имя — тем же механизмом, что в списке (Д14): собеседник по «Виду»,
                        // иначе название переписки, иначе то, с чем её открыли. Иначе один
                        // человек звался бы в списке одним, а в шапке другим.
                        name = listState.chats.firstOrNull { it.chatId == current.chatId }
                            ?.let { chat ->
                                personOfChat(chat)?.line(bookStateForChats.view.look(), PERSON_FIRST_LINE)
                                    ?: chat.title
                            }
                            ?: current.name,
                        peerFace = listState.chats.firstOrNull { it.chatId == current.chatId }
                            ?.let { faceOfChat(it) },
                        // Позвонить можно только человеку и только там, где есть чем:
                        // у группы собеседника нет, на ПК нет движка. Кнопки тогда нет.
                        // Видеозвонок — «три точки», а не шапка (решение заказчика
                        // 2026-09-20). Условие то же, что у голосового: личная переписка
                        // и есть чем звонить.
                        // Групповой звонок (ГЗ7): в личной — с этим человеком (решение 9), в
                        // группе — в ней самой (решение 1); полоса «Идёт звонок» и приглашения.
                        onGroupCall = if (!callHost.possible) null else listState.chats.firstOrNull { it.chatId == current.chatId }?.let { chat ->
                            if (chat.kind == ChatKind.Personal) {
                                chat.peerId?.let { peer -> { groupDesk.fromPerson(peer) } }
                            } else {
                                { groupDesk.fromGroup(current.chatId, groupTitleOf(current.chatId)) }
                            }
                        },
                        groupCall = groupDesk.live[current.chatId]?.call,
                        onWatchGroupCall = { groupDesk.watchGroup(current.chatId) },
                        onJoinGroupCall = { callId ->
                            groupDesk.join(callId, current.chatId, groupTitleOf(current.chatId))
                            showCall()
                        },
                        inCallHere = callHost.active && callHost.group?.groupId == current.chatId,
                        // Автору чата звонка — «Повторить звонок», пока звонок не идёт
                        // (заказчик 2026-10-08).
                        onRepeatGroupCall = if (callHost.possible && callOwnerOf(current.chatId) == session.userId) {
                            {
                                groupDesk.again(current.chatId, groupTitleOf(current.chatId), video = true, mine = true) {}
                            }
                        } else {
                            null
                        },
                        ttlUntilMs = callGroupsTtl[current.chatId],
                        callCreator = callCreatorName(current.chatId),
                        callCreatorFace = callCreatorFace(current.chatId),
                        inviteOf = { line ->
                            io.tima.feature.chat.CallInviteLink.groupOf(line.text)?.let { g -> groupDesk.inviteOf(g, groupTitleOf(g)) }
                        },
                        onInvite = { line ->
                            io.tima.feature.chat.CallInviteLink.groupOf(line.text)?.let { g ->
                                groupDesk.joinInvite(g, groupTitleOf(g))
                                showCall()
                            }
                        },
                        onVideoCall = listState.chats.firstOrNull { it.chatId == current.chatId }
                            ?.takeIf { it.kind == ChatKind.Personal && callHost.possible }
                            ?.peerId
                            ?.let { peerId ->
                                {
                                    val name = personOfChat(
                                        listState.chats.first { it.chatId == current.chatId },
                                    )?.line(bookStateForChats.view.look(), PERSON_FIRST_LINE).orEmpty()
                                    callPerson(peerId, name, true)
                                }
                            },
                        onCall = listState.chats.firstOrNull { it.chatId == current.chatId }
                            ?.takeIf { it.kind == ChatKind.Personal && callHost.possible }
                            ?.peerId
                            ?.let { peerId ->
                                {
                                    val name = personOfChat(
                                        listState.chats.first { it.chatId == current.chatId },
                                    )?.line(bookStateForChats.view.look(), PERSON_FIRST_LINE).orEmpty()
                                    callPerson(peerId, name, false)
                                }
                            },
                        scope = scope,
                        onBack = { where = Where.Nothing },
                        onMembers = { where = Where.Members(current.chatId, current.name) },
                        // «Сообщить о проблеме» из подокна неотправленного: отчёт с кодом
                        // причины уже в тексте — человеку не надо ничего пересказывать.
                        onReportFailed = { reason, waiting ->
                            val what = if (waiting) "Сообщение висит в очереди" else "Сообщение не отправилось"
                            problemDraft = "$what. Причина: ${reason ?: "не сохранена"}. Переписка ${current.chatId.take(8)}."
                            cameFrom = Origin(window, (if (waiting) "ждёт: " else "не отправилось: ") + (reason ?: "без причины"))
                            where = Where.Settings(SettingsItem.PROBLEM)
                        },
                        onCarry = { messageId, was ->
                            // Из переписки уносится сообщение группы: вид контейнера
                            // назван прямо, а не подразумевается умолчанием.
                            page.carry(CarryToPage.CONTAINER_GROUP, current.chatId, messageId, was)
                        },
                    )
                }
            }

            is Where.Person -> {
                {
                    // Карточку спрашиваем при открытии: человек мог прийти сюда из шапки
                    // переписки, где мы знали только идентификатор.
                    LaunchedEffect(current.userId) {
                        people.want(listOf(current.userId))
                        people.wantFace(current.userId)
                    }
                    // Дружит ли ОН со мной. Спрашивается чтением его ленты: сервер кладёт
                    // ответ полем `friend` туда же (Д1б). Отдельной ручки «дружим ли» нет,
                    // и заводить её ради заглушки рано — сначала решение по §1 плана.
                    var friend by remember(current.userId) { mutableStateOf<Boolean?>(null) }
                    LaunchedEffect(current.userId) {
                        friend = (network.pages.page(current.userId) as? PageStep.Page)?.friend
                    }
                    val entry = bookStateForChats.all.firstOrNull {
                        it.userId == current.userId ||
                            (peopleCards[current.userId]?.phone != null &&
                                it.phone == peopleCards[current.userId]?.phone)
                    }
                    val who = (peopleCards[current.userId] ?: ChatPerson())
                        .withBookName(entry?.name, entry?.phone)
                    GuestPageScreen(
                        person = who,
                        face = peopleFaces[current.userId],
                        inContacts = entry != null,
                        // Номера не знаем — заводить контакт не из чего: книга держит
                        // человека по номеру, и пустая строка завела бы пустого.
                        onAddToContacts = who.phone?.let { phone ->
                            {
                                contacts.changedPhone(phone)
                                newContact = true
                            }
                        },
                        look = bookStateForChats.view.look(),
                        friend = friend,
                        onBack = { where = Where.Nothing },
                        // Наше имя человека — ✎ напротив «Имя» (заказчик 2026-09-26). Только у
                        // того, кто в книге: имя пишется в его строку, и синхронизируется
                        // вместе с ней.
                        ownName = entry?.nameOwn,
                        onRename = entry?.let { e ->
                            { name: String? -> scope.launch { environment.bookStorage.rename(e.id, name) } }
                        },
                        // Четыре действия (ЗВ7). Звонков нет на платформе без движка —
                        // тогда и кнопок нет, а не «есть, но отвергаются».
                        onCall = if (callHost.possible) {
                            {
                                callPerson(
                                    current.userId,
                                    who.line(bookStateForChats.view.look(), PERSON_FIRST_LINE).orEmpty(),
                                    false,
                                )
                            }
                        } else {
                            null
                        },
                        onVideoCall = if (callHost.possible) {
                            {
                                callPerson(
                                    current.userId,
                                    who.line(bookStateForChats.view.look(), PERSON_FIRST_LINE).orEmpty(),
                                    true,
                                )
                            }
                        } else {
                            null
                        },
                        // Групповой звонок с этим человеком — настройка, где он уже отмечен
                        // (ПЛАН-(ГЗ)-ГРУППОВЫХ-ЗВОНКОВ ГЗ7).
                        onGroupCall = if (callHost.possible) ({ groupDesk.fromPerson(current.userId) }) else null,
                        // «Написать» — переход в переписку с этим человеком. Тот же
                        // путь, что из книги: идентификатор переписки считается из пары.
                        onWrite = {
                            val chatId = PersonalChatIdsOverKodium.personalChatId(session.userId, current.userId)
                            where = Where.Chat(
                                chatId,
                                who.line(bookStateForChats.view.look(), PERSON_FIRST_LINE).orEmpty(),
                            )
                        },
                    )
                }
            }

            Where.NewGroup -> {
                {
                    NewGroup(
                        rotator = { groupId, reason ->
                            if (assembled.keyOrchestrator.rotate(groupId, reason)) RotateStep.Rotated else RotateStep.Refused("ротация не удалась")
                        },
                        environment = environment,
                        network = network,
                        social = network,
                        scope = scope,
                        onBack = { where = Where.Nothing },
                        onCreated = { groupId, title -> where = Where.Chat(groupId, title) },
                    )
                }
            }

            is Where.Members -> {
                {
                    // Кандидаты из книги — кто в TIMa; «уже в группе» отмечает сам экран.
                    LaunchedEffect(bookStateForChats.all) { people.want(bookStateForChats.all.mapNotNull { it.userId }) }
                    Members(
                        environment = environment,
                        network = network,
                        session = session,
                        groupId = current.groupId,
                        scope = scope,
                        onBack = { where = Where.Chat(current.groupId, current.name) },
                        onAccess = { where = Where.Access(current.groupId, current.name) },
                        people = people,
                        look = communityView.look(),
                        contacts = bookStateForChats.all.filter { it.inTima }.map { entry ->
                            InviteCandidate(entry.userId!!, (peopleCards[entry.userId!!] ?: ChatPerson()).withBookName(entry.name, entry.phone))
                        },
                    )
                }
            }

            is Where.Access -> {
                {
                    // Store живёт столько, сколько открыто подокно, и ключ ему — группа:
                    // доступ у каждой группы свой, и остатки чужого состояния тут опасны.
                    val store = remember(current.groupId) {
                        AccessStore(
                            port = network.access,
                            groupId = current.groupId,
                            scope = scope,
                            epochAfter = ::epochAfter,
                        )
                    }
                    val state by store.state.collectAsState()
                    LaunchedEffect(current.groupId) { store.refresh() }
                    AccessScreen(
                        state = state,
                        onAsk = store::ask,
                        onDecide = store::decide,
                        onCloseTrouble = store::troubleDismissed,
                        onBack = { where = Where.Members(current.groupId, current.name) },
                    )
                }
            }
        },
    )
    // Замок пин-кода (ПЛАН-(ПН)) — поверх всего, но не поверх звонка: входящий принимается без
    // пина (Р11), а пин спрашивается, когда звонок кончился.
    if (locked && pin != null && lockWho != null && !callHost.active) {
        PinLockLayer(pin, lockWho, onUnlocked, onLockCancel, lockOthers, onSwitchAccount)
    }
    }
    }
}

@Composable
private fun Chat(
    environment: Environment,
    network: ChatPorts,
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
    myUserId: String = "",
    /**
     * Разобрать недоступное заново — когда сервер сказал, что ключ сообщения у устройства уже
     * есть (2026-10-06). `null` — нечем: так собирают экран в проверках.
     */
    reopenUnreadable: (suspend () -> Int)? = null,
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
    peerFace: ImageBitmap? = null,
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
    callCreatorFace: ImageBitmap? = null,
    inviteOf: (ChatLine) -> io.tima.feature.chat.CallInvite? = { null },
    onInvite: (ChatLine) -> Unit = {},
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
    LaunchedEffect(chatId, publicGroup, groupTop) {
        if (!publicGroup || liveStates == null) return@LaunchedEffect
        val newest = runCatching { onPullGroupNew(chatId) }.getOrDefault(0L)
        if (newest > 0) liveStates.readEntity("group", chatId, newest)
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
    // Пока переписка на экране — уведомлений о ней нет (У10): человек читает её глазами,
    // и строка в шторке была бы уведомлением о том, что он уже видит. `DisposableEffect`,
    // а не `LaunchedEffect`: уход с экрана обязан снять отметку, иначе закрытая переписка
    // останется «открытой» навсегда и замолчит насовсем.
    DisposableEffect(chatId, notices) {
        notices?.watching(chatId)
        seenUpTo(environment, chatId)
        onDispose {
            notices?.watching(null)
            // И на выходе: пока переписка была открыта, в ней могло прийти новое — его тоже
            // видели.
            seenUpTo(environment, chatId)
        }
    }
    // Store живёт столько, сколько открыта переписка: ключ по chatId, чтобы при переходе в
    // другую он пересоздался, а не показал реплики предыдущей.
    val store = remember(chatId) {
        ChatStore(
            chatId = chatId,
            observe = environment.chat,
            send = environment.send,
            scope = scope,
            markRead = environment.reading,
            // Запрос недостающего ключа и имена авторов — только у группы: у личной
            // переписки просить не у кого, а собеседник назван в шапке.
            requestKeys = if (group) {
                // Просьба подписывается фразой (Р42): без подписывающего она уходила неподписанной и
                // получала 403 даже с введённой фразой (живая проверка 2026-10-06, п. 1.7).
                RequestGroupKeys(GroupKeyRecoveryOverHttp(network.keyRecovery) { gid, words ->
                    io.tima.core.encryption.RecoverySignature.sign(words, gid, myDeviceId)
                })
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
            // Сами — один раз на переписку в сутки, в личной и в группе: каждое открытие с
            // недоступным сообщением будило бы чужие устройства заново.
            autoAskAllowed = {
                val key = CHAT_KEYS_ASKED + chatId
                val last = runCatching { environment.settings.all().first()[key]?.toLongOrNull() }.getOrNull() ?: 0L
                val now = msNow()
                (now - last >= CHAT_KEYS_AGAIN_MS).also { if (it) runCatching { environment.settings.put(key, now.toString()) } }
            },
        )
    }
    val state by store.state.collectAsState()
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
    // Выбран круг, которого у группы этого вида нет (остался с прежней сборки или от
    // другой группы) — сбросить на первый допустимый, иначе отправка получит 400.
    LaunchedEffect(kind, state.level) {
        val allowed = kind?.circles ?: return@LaunchedEffect
        if (allowed.none { it.level == state.level }) store.circleChosen(allowed.first().level)
    }
    var myColor by remember { mutableStateOf(false) }
    var myColorTrouble by remember { mutableStateOf<String?>(null) }
    // Отправители, чья личность отменена владельцем (ДУ6, Р30) — из настроек аккаунта.
    val settingsNow by environment.settings.all().collectAsState(initial = emptyMap())
    val cancelledSenders = remember(settingsNow) {
        settingsNow.keys.filter { it.startsWith(IdentityChain.CANCELLED_PREFIX) }.map { it.removePrefix(IdentityChain.CANCELLED_PREFIX) }.toSet()
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
    val peerSeen = peerUser?.let { presenceNow[it] }
    val mutedNow by (liveStates?.muted ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptySet<String>()) }).collectAsState()
    val peerLine = if (live == null) null else io.tima.feature.chat.peerStatus(
        Tima.words.chat, Tima.words.callLog,
        typing = (typingNow[chatId] ?: 0) > tickMs,
        online = peerSeen?.onlineAt(tickMs) == true,
        lastSeenMs = peerSeen?.lastSeenMs ?: 0,
    )
    ChatScreen(
        cancelledSenders = cancelledSenders,
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
        onAskChatKeys = store::askChatKeys,
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
private fun ChatLine.asComment(root: Long) = CommentEntry(
    postId = serverId,
    authorId = senderId.orEmpty(),
    text = text ?: "Сообщение не читается",
    atMs = atMs,
    parentPostId = root,
)


/**
 * Подтверждение привязки: экран и его Store.
 *
 * Store живёт столько, сколько открыт экран, и ключом ему служит сам код: другой код —
 * другое устройство, и остатки прежнего состояния тут были бы опасны.
 */
@Composable
private fun LinkConfirmation(
    network: DevicePorts,
    deviceSecret: ByteArray,
    /** Ключ подписи устройств этого телефона (ДУ2); `null` — телефон не подтверждён фразой. */
    signingKey: ByteArray?,
    code: String,
    scope: kotlinx.coroutines.CoroutineScope,
    onClose: () -> Unit,
    onTrusted: suspend (String, ByteArray) -> Unit = { _, _ -> },
) {
    val store = remember(code) {
        LinkStore(
            confirm = network.linkConfirmation(deviceIdentityFrom(deviceSecret), signingKey),
            scope = scope,
            code = code,
            onTrusted = onTrusted,
        )
    }
    val state by store.state.collectAsState()
    LinkScreen(state = state, onTrust = store::trust, onCancel = onClose)
}

/**
 * Настройки: подокно с тремя вкладками.
 *
 * Собирается здесь, а не в оболочке: вкладки живут в разных модулях — устройства в
 * `feature-auth`, обновление в `feature-shell`, — и свести их вправе только приложение.
 * Оболочка получает готовое содержимое слотом и по-прежнему не знает ни про один feature.
 */
@Composable
private fun Settings(
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
    /** Микрофон, колонки, камера — есть только у ПК; `null` — пункта нет. */
    callDevices: io.tima.core.call.CallDevices? = null,
    /** Запуск вместе с системой — есть только у ПК; `null` — раздела в «Разрешениях» нет. */
    loginStart: LoginStart? = null,
    /** Пин-код этого аккаунта (ПЛАН-(ПН)) — значок «Пин» у текущего в «Аккаунтах» (2026-10-07). */
    pin: PinHost? = null,
) {
    // Название темы считается в составе, а не в лямбде списка: лямбда не composable.
    val themeName = Tima.words.appearance.theme(appearance.choice)
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
        // Выбирать устройства есть смысл только там, где их выбирает человек, — на ПК.
        // Аппаратное кодирование — только на телефоне: на ПК кодеры программные.
        hidden = if (callDevices == null) setOf(SettingsItem.MEDIA) else setOf(SettingsItem.CALLS),
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
                )
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
                draft = problemDraft, photos = problemPhotos, kind = problemKind,
            )

            // Предложение — та же отправка с очередью, но без журнала (заказчик 2026-10-07).
            SettingsItem.SUGGEST -> Suggest(problemFacts, reporting, scope, platform)

            SettingsItem.STORAGE -> Storage(diaryPolicy, callsLog, callsState)

            // Испытательный режим звонков. Пункт временный и уйдёт вместе со стендом —
            // держать его «на всякий случай» после испытаний незачем (С-В1).
            SettingsItem.CALLBENCH -> CallBenchSwitch(
                on = benchState.on,
                preset = benchState.preset.name,
                onSwitch = bench::flag,
            )

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
private fun Devices(
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
private fun PinFlow(
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
private fun accountRows(
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
private fun RevokedDevice(onAgain: () -> Unit, reason: String = "", deleteAt: Long? = null) {
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
private fun Update(store: UpdateStore, state: UpdateState) {
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
private fun Storage(
    policyStore: AppearanceStore,
    callsLog: CallsStore,
    callsState: CallsState,
) {
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
    )
}

/**
 * Дни обратно в «месяцы» или «недели» — то же правило, что у [limitsOf].
 *
 * Единица не хранится отдельно и восстанавливается из числа: второе представление одного
 * числа пришлось бы держать в согласии, а оно однажды разъедется.
 */
private fun keepForDays(days: Int): KeepFor = when {
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
private fun limitsOf(policy: DiaryPolicy): DiaryLimits {
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
private fun Problem(
    facts: ProblemFacts,
    origin: Origin?,
    reporting: Reporting,
    scope: kotlinx.coroutines.CoroutineScope,
    platform: Platform,
    snapshot: () -> Snapshot,
    draft: String = "",
    photos: List<io.tima.feature.shell.ProblemPhoto> = emptyList(),
    kind: io.tima.feature.shell.ProblemKind = io.tima.feature.shell.ProblemKind.Other,
) {
    val store = remember {
        ProblemStore(
            // Сброс перед выгрузкой: отчёт составляют ровно тогда, когда приложение
            // ведёт себя плохо, и следующего повода записать на диск может не быть —
            // человек закроет его силой, а система добьёт процесс.
            log = { days ->
                Journal.diary.flush()
                Journal.diary.dump(days.toLong() * Diary.DAY)
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
private fun Suggest(
    facts: ProblemFacts,
    reporting: Reporting,
    scope: kotlinx.coroutines.CoroutineScope,
    platform: Platform,
) {
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
                        else SendOutcome.Refused(io.tima.core.words.CurrentWords.value.suggest.refused(result.status))
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

/** Вид предложения на сервере — `problemKindSuggestion` в `server/internal/api/problems.go`. */
private const val SUGGESTION_KIND = "suggestion"

/** Сколько висит короткое слово во вкладке «Звонки» («Чат удалён»). */
private const val CALLS_NOTE_MS = 2_500L

/** Высота снимка в форме отчёта: видно, что уходит, и не заслоняет форму. */
private val PHOTO_PREVIEW = 160.dp

/**
 * Тело отчёта: снимок сверху, хронология под ним.
 *
 * Снимок отвечает «что сейчас», журнал — «как дошли»; читающий начинает с первого и
 * спускается ко второму, только если первого не хватило. Отдельным полем снимок не
 * заводится: это тот же текст, и отдельное поле пришлось бы вести в таблице, в ручке и в
 * разборе ради того, что и так читается сверху вниз.
 */
private fun reportBody(began: Began, snapshot: Snapshot, log: String): String = buildString {
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
private fun howLongSince(startedAt: Long): String {
    val minutes = (nowMillis() - startedAt) / 60_000
    return when {
        minutes < 1 -> "идёт меньше минуты"
        minutes < 60 -> "идёт $minutes мин"
        else -> "идёт " + (minutes / 60) + " ч " + (minutes % 60) + " мин"
    }
}

/**
 * Код открытого подокна для журнала — латиницей, один на любом языке (заказчик
 * 2026-09-27). Что значит каждый — `doc_mig/ЖУРНАЛ-И-ОТЛАДКА/РЕЕСТР.md`, «Экраны».
 *
 * До 2026-09-27 здесь стояли русские названия: «настройки: Разрешения», «переписка».
 * Код не меняется от языка и от правки надписи, и отчёт находится поиском всегда.
 *
 * **Без идентификаторов.** `chat`, а не `chat:7f3a…`: читающему нужно знать, что человек
 * открыл переписку, а не какую, — идентификатор чужого разговора в хранилище отчётов не
 * нужен никому. Так же у страницы, группы, комментариев.
 */
private fun whereCode(where: Where): String = when (where) {
    Where.Nothing -> "list"
    Where.New -> "chat.new"
    Where.Profile -> "profile"
    Where.SelfPage -> "page.self"
    Where.NewGroup -> "group.new"
    Where.NewVirtual -> "virtual.new"
    is Where.Members -> "group.members"
    is Where.Chat -> "chat"
    is Where.Person -> "page.person"
    is Where.Access -> "group.access"
    is Where.Comments -> "comments"
    is Where.Community -> "community"
    is Where.Link -> "link.confirm"
    is Where.Certify -> "device.certify"
    is Where.Transfer -> if (where.virtualUserId == null) "account.take" else "account.give"
    // Пункт настроек — именем перечня: оно и есть ключ навигации, и латиницей.
    is Where.Settings -> "settings" + (where.item?.let { "." + it.name.lowercase() } ?: "")
}

/**
 * Что предлагает сервер этой платформе.
 *
 * Платформу называем в самом вопросе: пакеты у ПК и телефона разные, а сервер без этого
 * слова отвечает про Android — так он отвечал до 2026-09-06, и установленные клиенты на
 * это рассчитывают (О1).
 *
 * Ошибки связи здесь становятся исключением, а не пустым ответом: `null` означает «сервер
 * обновления не раздаёт», и подменять им «мы не дозвонились» значило бы сказать человеку,
 * что обновлений нет, когда мы этого не знаем.
 */
private suspend fun versionOffer(network: DevicePorts, platform: Platform): UpdateOffer? =
    when (val answer = network.appVersion.latest(platform.packageKind)) {
        is AppVersionResult.Version -> UpdateOffer(
            versionCode = answer.versionCode,
            versionName = answer.versionName,
            url = answer.url,
            notes = answer.notes,
            stream = answer.stream,
            sha256 = answer.sha256,
            size = answer.size,
            minClient = answer.minClient,
            important = answer.important,
        )
        AppVersionResult.NotConfigured -> null
        is AppVersionResult.NoConnection -> error("нет связи")
        is AppVersionResult.Refused -> error("отказ ${answer.status}")
    }



/**
 * Новая группа: подокно создания.
 *
 * Собирается здесь, потому что случай использования требует троих сразу — групп на
 * сервере, справочника и книги переписок. Домен их объявляет, а сводит приложение.
 */
@Composable
private fun NewGroup(
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
private fun Members(
    environment: Environment,
    network: GroupPorts,
    session: Session,
    groupId: String,
    scope: kotlinx.coroutines.CoroutineScope,
    onBack: () -> Unit,
    /** Открыть подокно «Доступ»: просьбы и выдача третьего круга. */
    onAccess: () -> Unit = {},
    people: ChatPeople? = null,
    look: PersonLook = PersonLook.DEFAULT,
    contacts: List<InviteCandidate>? = null,
) {
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
private fun seenUpTo(environment: Environment, chatId: String) {
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
        runCatching {
            val upto = environment.readState.lastIncomingTs(chatId)
            if (upto > 0) environment.readState.markLocal(chatId, upto)
        }
    }
}

private fun windowCounters(counts: io.tima.domain.chat.NoticeCounts): Map<Window, Int> {
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
 * Текст приглашения — один на все три способа.
 *
 * СМС и «поделиться» несут одну и ту же строку: разница только в том, чем её понесут.
 * Ссылки-приглашения с меткой пригласившего здесь нет — она отдельная работа, и без неё
 * нельзя узнать, кто кого привёл (развилка в ПЛАН-(Д)-КОНТАКТОВ.md).
 */
private const val INVITE_TEXT =
    "Пишу из TIMa — это мессенджер, где переписка видна только собеседникам. " +
        "Ставится по номеру телефона."

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
private fun PhoneWindow(
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
    faceOfChat: (ChatSummary) -> ImageBitmap? = { null },
    /** Группа звонка: создатель и его аватар. */
    callGroupOf: (ChatSummary) -> io.tima.feature.chat.CallGroupLook? = { null },
    faceOf: (BookEntry) -> ImageBitmap? = { null },
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
    faceOfCall: (CallRecord) -> ImageBitmap? = { null },
    /** Перезвонить из журнала — **тем же видом**, каким звонили тогда (Ж6). */
    onCallAgain: ((CallRecord) -> Unit)? = null,
    /** Открыли вкладку «Звонки»: сходить за свежим журналом и погасить счётчик. */
    onOpenedCalls: () -> Unit = {},
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
        when (tab) {
            WindowTab.Chats -> ChatsScreen(
                // Р4: переписки сужаются выбранным разделом — тем же, что у контактов;
                // поиск сужает дальше, по имени в шапке и первой строке последнего.
                state = list.copy(
                    chats = list.chats.filter { chat ->
                        (
                            chatSection.isEmpty() ||
                                sectionOfChat(chat) == (if (chatSection == COMMON_SECTION) "" else chatSection)
                            ) && chat.matches(request)
                    },
                ),
                onOpen = onOpen,
                onNew = onNew,
                onSettings = onSettings,
                // Тот же человек и тот же «Вид», что во вкладке «Контакты».
                personOf = personOfChat,
                faceOf = faceOfChat,
                onFace = { chat -> chat.peerId?.let(onFaceUser) },
                look = book.view.look(),
                countOf = { chat -> noticeCounts.chat(chat.chatId, chat.peerId) },
                tagOf = tagOf,
                callGroupOf = callGroupOf,
                receiptOf = receiptOfChat,
                typingOf = typingOfChat,
                mutedOf = { chat -> "chat:${chat.chatId}" in mutedKeys },
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
                LaunchedEffect(Unit) { onOpenedCalls() }
                CallsScreen(
                    // ── ФИЛЬТРЫ ВТОРОГО РЯДА ────────────────────────────────
                    //
                    // «Из книги» и «Незнакомые» отбираются **по книге, а не по
                    // серверу**: знает ли телефон этого человека — вопрос к телефону.
                    // «Незнакомые» у нас поэтому означает «звонил тот, кого нет в
                    // книге», а не «звонок с чужого номера»: чужих номеров в нашем
                    // журнале не бывает вовсе — звонить может только аккаунт.
                    // Групповой звонок — не про одного человека: он в «Всех» и «Пропущенных».
                    records = callsState.records.filter { record ->
                        val known = book.all.any { it.userId != null && it.userId == record.other(myUserId) }
                        when (calls) {
                            WindowTab.FromBook -> known && !record.group
                            WindowTab.Unknown -> !known && !record.group
                            WindowTab.Missed -> record.outcome(myUserId) == CallOutcome.Missed
                            else -> true
                        }
                    },
                    me = myUserId,
                    personOf = personOfCall,
                    faceOf = faceOfCall,
                    look = book.view.look(),
                    onCallAgain = onCallAgain,
                    onOpen = { record ->
                        if (record.group) onOpenGroupCall(record) else onOpenUser?.invoke(record.other(myUserId))
                    },
                    // Аватар группового — страница создателя, как в «Чатах».
                    onFace = { record -> onFaceUser(if (record.group) record.initiatorId else record.other(myUserId)) },
                    offline = callsState.offline,
                    groupOf = groupCallOf,
                    note = callsNote,
                    flagged = { it.callId in flaggedCalls },
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
 * Повтор и удаление отказанного — поверх очереди. Журнал получает пару к `QUEUE-REFUSED`:
 * что сделал человек, с какой прежней причиной и каким новым кругом.
 */
private fun deadMessages(environment: Environment): DeadMessages = object : DeadMessages {
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
private fun SearchRow(
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
        ControlRow { IconButton(glyph = "✕", onClick = onClose) }
    }
}

// ── Полоса «звонки не дойдут» (ВЗ0г) ────────────────────────────────────────

/** Где лежит «Позже» беды: время, до которого полоса молчит, мс. Пусто — не откладывали. */
private fun bgLaterKey(trouble: BackgroundTrouble): String = "bg.later." + trouble.name.lowercase()

/** Код журнала беды: уведомления и канал — `BG-NOTICES`, батарея — `BG-POWER`. */
/**
 * Где системный вопрос про уведомления (ВЗ0б) — для очереди событий: пока он не решён,
 * событие про уведомления — «не знаю» (заказчик 2026-09-27).
 */
private enum class NoticesAsk {
    /** Ещё не прочитали, спрашивали ли в прошлые запуски. */
    Deciding,

    /** Системное окно на экране. */
    Asking,

    /** Отказали только что — событие ждёт следующего запуска. */
    DeniedNow,

    /** Спрашивать нечего или ответ «разрешить»: событие по обычным правилам. */
    Settled,
}

/** «Позже» — неделя (решение заказчика 2026-09-26). */
private const val BG_LATER_MS = 7L * 24 * 60 * 60 * 1000

/** Не чаще раза в полминуты — повторная аттестация по требованию сервера (ЗБ1). */
private const val ATTEST_AGAIN_MS = 30_000L

/** Разрешение на уведомления спрошено само — один раз на установку (ВЗ0б). */
private const val NOTICES_AUTO_ASKED = "notices.autoAsked"

/** Рейка с подписями или значками и ширина колонки в точках — что выставлено мышью. */
private const val STAGE_RAIL = "stage.rail"
private const val STAGE_COLUMN = "stage.column"
private const val RAIL_CAPTIONS = "captions"
private const val RAIL_ICONS = "icons"

/**
 * Строка выбора звука — ВЗ4: общая мелодия или звук сообщения, а в журнале контактов —
 * своя мелодия человека (ВЗ8). Выбор ложится строкой в настройки устройства.
 *
 * @param fileName имя своего файла у себя без расширения: у общей мелодии «ring», у
 *   мелодии контакта — своё, чтобы выбор одного не затёр другой.
 */
@Composable
internal fun soundRow(
    settings: io.tima.domain.chat.Settings,
    key: String,
    use: SoundUse,
    fileName: String,
): SoundRow {
    val scope = rememberCoroutineScope()
    var all by remember(settings) { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(settings) { settings.all().collect { all = it } }
    var trouble by remember(key) { mutableStateOf<String?>(null) }
    val words = Tima.words.settings2
    val choice = soundChoiceOf(all[key])
    val save: (SoundChoice) -> Unit = { picked ->
        trouble = null
        scope.launch { settings.put(key, picked.wire()) }
    }
    val onPick: (SoundPick?) -> Unit = { pick ->
        when (pick) {
            null -> Unit
            SoundPick.Default -> save(SoundChoice.Default)
            is SoundPick.System -> save(SoundChoice.System(pick.uri, pick.title))
            is SoundPick.File -> save(SoundChoice.File(pick.path, pick.title))
            SoundPick.TooBig -> trouble = words.soundTooBig
            SoundPick.BadType -> trouble = words.soundBadType
        }
    }
    val system = rememberSystemSoundPicker(use, onPick)
    val file = rememberSoundFilePicker(fileName, onPick)
    return SoundRow(
        // Справа — каким путём выбрано, под названием настройки — что звучит (заказчик
        // 2026-10-01: «какая мелодия выбрана — название»). «Как в системе» тоже называется:
        // мелодия телефона.
        current = when (choice) {
            SoundChoice.Default -> words.soundDefault
            SoundChoice.Silent -> words.soundSilent
            is SoundChoice.System -> words.soundFromSystem
            is SoundChoice.File -> words.soundFromFile
        },
        sound = when (choice) {
            SoundChoice.Default -> systemDefaultSoundTitle(use)
            SoundChoice.Silent -> null
            is SoundChoice.System -> choice.title.ifBlank { null }
            is SoundChoice.File -> choice.title.ifBlank { null }
        },
        onSystem = if (systemSoundsAvailable) system else null,
        onFile = file,
        onSilent = { save(SoundChoice.Silent) },
        onDefault = { save(SoundChoice.Default) },
        trouble = trouble,
        picked = when (choice) {
            SoundChoice.Default -> io.tima.feature.shell.SoundPicked.Default
            SoundChoice.Silent -> io.tima.feature.shell.SoundPicked.Silent
            is SoundChoice.System -> io.tima.feature.shell.SoundPicked.System
            is SoundChoice.File -> io.tima.feature.shell.SoundPicked.File
        },
    )
}

/** Как назвать выбранный звук в строке: имя мелодии или файла, «Без звука». */
private fun soundTitle(choice: SoundChoice, silent: String): String? = when (choice) {
    SoundChoice.Default -> null
    SoundChoice.Silent -> silent
    is SoundChoice.System -> choice.title
    is SoundChoice.File -> choice.title
}

/**
 * Мелодия звонка для выделенных в журнале контактов — ВЗ8.
 *
 * Своя мелодия есть только у тех, кто в TIMa: остальные не звонят через приложение, и
 * назначать им нечего. Выбор ложится каждому своим ключом; «Как в настройках» снимает
 * свою — звучит общая.
 */
@Composable
private fun ContactSoundSheet(
    settings: io.tima.domain.chat.Settings,
    targets: List<BookEntry>,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val words = Tima.words
    val users = targets.mapNotNull { it.userId }
    var trouble by remember { mutableStateOf<String?>(null) }
    val put: (String) -> Unit = { value ->
        scope.launch { users.forEach { settings.put(SoundKeys.ringOf(it), value) } }
        onClose()
    }
    val onPick: (SoundPick?) -> Unit = { pick ->
        when (pick) {
            null -> Unit
            SoundPick.Default -> put(SoundChoice.Default.wire())
            is SoundPick.System -> put(SoundChoice.System(pick.uri, pick.title).wire())
            is SoundPick.File -> put(SoundChoice.File(pick.path, pick.title).wire())
            SoundPick.TooBig -> trouble = words.settings2.soundTooBig
            SoundPick.BadType -> trouble = words.settings2.soundBadType
        }
    }
    val system = rememberSystemSoundPicker(SoundUse.Ring, onPick)
    // Имя файла у себя — своё на каждый выбор: общий файл нескольких людей не затирается
    // выбором одного из них.
    val file = rememberSoundFilePicker("ring_c" + msNow(), onPick)
    Box(
        Modifier.fillMaxSize().background(Tima.colors.text.copy(alpha = 0.45f)).clickable(onClick = onClose),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.fillMaxWidth().background(Tima.colors.surface).clickable(enabled = false) {}
                .padding(TimaSpacing.about4),
            verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
        ) {
            Name(words.book.ledgerSoundTitle + " · " + users.size)
            if (users.size < targets.size) Tertiary(words.book.ledgerSoundOnlyTima)
            if (users.isNotEmpty()) {
                if (systemSoundsAvailable) Button(label = words.settings2.soundFromSystem, onClick = system, kind = ButtonKind.Quiet)
                Button(label = words.settings2.soundFromFile, onClick = file, kind = ButtonKind.Quiet)
                Button(label = words.settings2.soundSilent, onClick = { put(SoundChoice.Silent.wire()) }, kind = ButtonKind.Quiet)
                // «Как в настройках» — снять свою: звучит общая мелодия.
                Button(label = words.book.ledgerSoundAsSettings, onClick = { put("") }, kind = ButtonKind.Quiet)
            }
            trouble?.let { Secondary(it) }
        }
    }
}

/** Кому уже уходило приглашение в группу звонка — ключ настроек устройства + номер группы. */
private const val INVITED_PREFIX = "call.group.invited."

/** Выбранный вид группового звонка — ключ настроек устройства. */
private const val GROUP_VIEW_KEY = "call.group.view"

/** Текст извещения о перерегистрации (ДУ9) — для этой стороны, тексты §2б. */
private fun reregNoticeText(
    e: io.tima.core.network.EventStreamProtocol.Decision.Rereg?,
    me: String,
    w: io.tima.core.words.AuthWords,
): String {
    if (e == null) return ""
    val isNew = e.newUserId == me
    val from = reregDate(e.windowFrom)
    val to = reregDate(e.windowTo)
    return when (e.kind) {
        // Смена номера (ДУ9): всем устройствам — «номер меняется», в окне — «пора подтвердить».
        "phone_started" -> w.phoneChangeOthers(e.phone)
        "phone_window" -> w.phoneChangeWindow(to)
        "phone_done" -> when (e.outcome) {
            "changed" -> w.phoneChangeDone(e.phone)
            "cancelled" -> w.phoneChangeCancelled
            else -> w.phoneChangeExpired
        }
        "started" -> w.reregOldAbout
        "disputed" -> if (isNew) w.reregDisputedNewAbout(from, to) else w.reregClaimedAbout(from, to)
        "window" -> if (isNew) w.reregWindowNew(to) else w.reregWindowOld(to)
        else -> when (e.outcome) {
            "extended" -> w.reregExtended(from, to)
            "new" -> if (isNew) w.reregNewWon else w.reregOldLost
            else -> if (isNew) w.reregNewLost("") else w.reregOldWon
        }
    }
}

/** Отметка «ключи переписки уже просили сами» — в настройках аккаунта, латиницей. */
private const val CHAT_KEYS_ASKED = "chat.keys.asked."

/**
 * Номера сообщений переписки, к которым ключа не осталось ни у кого (ответ сервера на просьбу,
 * 2026-10-06): их не просят снова. Имя латиницей — то, что приложение кладёт на диск.
 */
private const val CHAT_KEYS_LOST = "chat.keys.lost."

/** Сами просим ключи переписки не чаще раза в сутки. */
private const val CHAT_KEYS_AGAIN_MS = 24 * 60 * 60_000L
