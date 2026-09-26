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
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
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
import io.tima.feature.shell.SoundRow
import io.tima.core.media.systemSoundsAvailable
import io.tima.core.media.rememberSoundFilePicker
import io.tima.core.media.rememberSystemSoundPicker
import io.tima.core.media.SoundUse
import io.tima.core.media.SoundPick
import io.tima.core.notify.wire
import io.tima.core.notify.soundChoiceOf
import io.tima.core.notify.SoundKeys
import io.tima.core.notify.SoundChoice
import kotlinx.coroutines.flow.first
import io.tima.feature.shell.LocalBackgroundWarning
import io.tima.feature.shell.BackgroundWarning
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
import io.tima.feature.call.CallScreen
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
import io.tima.feature.shell.NoticeScreen
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
     * Где платформа хранит выбранный язык (ПЛАН-ЯЗЫКА Я1).
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
     * Кто ставит скачанное обновление (ПЛАН-ОБНОВЛЕНИЯ.md, О3).
     *
     * `null` — платформа этого не умеет: на iOS обновление приходит из App Store, а на
     * сборке без установщика кнопки просто не будет. Кнопка, которая ничего не делает,
     * хуже её отсутствия — по ней судят, что приложение сломано.
     */
    installer: UpdateInstaller? = null,
    /** Установщик запущен — платформе пора закрыть приложение. */
    onLeaving: () -> Unit = {},
    /**
     * Что платформа знает о себе для отчёта о проблеме (ПЛАН-ОТЛАДКИ.md, Б3).
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
    // видит (ПЛАН-ЯЗЫКА, Я2-беды). Пишется отсюда и только отсюда — из того же значения,
    // что уходит в тему, поэтому разойтись им негде.
    SideEffect { CurrentWords.value = words }

    // Шрифт — отсюда же, что цвета и словарь (ПЛАН-ШРИФТОВ Ш1): одна подмена на всё.
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
            onLeaving = onLeaving,
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
    onLeaving: () -> Unit,
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
        Occurrence(entry, build) { device = entry.created() }
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

    App(
        assembled = assembled,
        platform = entry.platform,
        callEngine = callEngine,
        deviceSecret = current.secret,
        linkCode = linkCode,
        transferCode = transferCode,
        installer = installer,
        onLeaving = onLeaving,
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
            entry.switchAccount(userId)
            device = entry.created()
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
private fun Occurrence(entry: Entry, build: Build, onEntered: () -> Unit) {
    val scope = rememberCoroutineScope()
    val store = remember {
        AuthStore(
            register = entry.registration,
            identities = AccountIdentitiesOverKodium,
            scope = scope,
            link = entry.link,
            deviceName = entry.platform.deviceName,
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
     * Страница сообщества: состав, описание, подписка (ПЛАН-СООБЩЕСТВ С6).
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
     * Заведение виртуального аккаунта (ПЛАН-КОНТАКТОВ.md, Д11).
     *
     * Открывается из переключения окон — оттуда же, где виден список аккаунтов. Это не
     * настройка: человек заводит второго себя, и место этому там, где он этих себя
     * выбирает.
     */
    data object NewVirtual : Where

    /**
     * Передача виртуального аккаунта (ПЛАН-КОНТАКТОВ.md, Д12).
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
    linkCode: String?,
    /** Код передачи, принесённый камерой (Д12). См. пояснение у [Root]. */
    transferCode: String? = null,
    /** Кто ставит обновление; `null` — платформа не умеет. См. пояснение у [Root]. */
    installer: UpdateInstaller? = null,
    /** Установщик запущен — пора закрыть приложение. */
    onLeaving: () -> Unit = {},
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
    onSwitchAccount: (String) -> Unit = {},
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
    LaunchedEffect(where) { Journal.note(LogCode.SCREEN_OPEN, whereWords(where)) }

    // Какое окно открыто. Приложение начинается с окна 1: личная связь — то, ради
    // чего его открывают чаще всего, а остальные окна пока пусты по существу.
    var window by remember { mutableStateOf(Window.Phone) }

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
    LaunchedEffect(window) { Journal.note(LogCode.WINDOW_OPEN, window.name) }
    var windowSwitcher by remember { mutableStateOf(false) }
    // Куда уходим, если очередь непуста. null — вопрос не задан: отдельного флага
    // «спрашиваем» не заводим, чтобы «спрашиваем, но некуда» не стало возможным.
    var leavingTo by remember { mutableStateOf<String?>(null) }
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
    var socialTab by remember { mutableStateOf(WindowTab.Common) }
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
    /** Экран управления разделами — из меню «Вид» (ПЛАН-РАЗДЕЛОВ Р2). */
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
    // за отчётом не пойдёт (ПЛАН-ОТЛАДКИ.md, Б7).
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
        // с сессией. Числится в ПЛАН-КОНТАКТОВ.md, Д8.
        ProfileStore(profile = network.profile, phone = "", scope = scope, media = network.media)
    }
    val profileState by profile.state.collectAsState()
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

    // Окно 2 «Социум»: свои группы и карточки, которые открыли контакты. Списки живут
    // здесь, а не в оболочке: рама знает раму, работа с сервером — дело feature-group.
    val social = remember { SocialStore(GroupsOverHttp(network.groups), scope, network.communities) }
    // Окно 5 «Страница»: своя лента — своё и принесённое. Один Store на приложение: одна
    // страница у человека, и второй показывал бы то же самое со своим отставанием.
    val page = remember { PageStore(network.pages, scope, switches = network.commentSwitches) }

    // Звонок: окно 0 живёт, пока идёт разговор (макет 21-call.md, решение заказчика
    // 2026-09-19). Движок приходит от платформы — общий код Context не видит; `null`
    // означает «эта платформа звонить не умеет», и тогда окна 0 нет вовсе.
    // Стенд звонков — испытательный режим за флагом (ПЛАН-СТЕНДА-ЗВОНКОВ §4).
    //
    // **Заводится всегда, даже когда флаг выключен**, и это не расточительство: он
    // хранит выбранный набор публикации, а набор работает и без стенда — выключение
    // флага уносит обвязку, но не выбор. Замеров при выключенном флаге он не делает.
    val bench = remember(callEngine) { BenchStore(environment.settings, callEngine, scope) }
    val benchState by bench.state.collectAsState()

    // Набор — ЛЯМБДОЙ, а не значением: `CallHost` живёт от запуска до запуска, а набор
    // меняют на экране стенда посреди его жизни. Снятый значением, он застыл бы на том,
    // что было выбрано при старте.
    val callHost = remember(callEngine) {
        // Прогон — звонок при нажатой «Начать прогон» (`armed`), и кодек пресета в нём не
        // заменяется (заказчик 2026-09-25). Без неё звонок обычный, даже при включённом
        // испытательном режиме, — кодек по умению телефона.
        CallHost(
            network.calls,
            callEngine,
            scope,
            preset = { bench.state.value.let { it.preset.copy(exact = it.armed) } },
        )
    }

    // Входящий звонок приходит каналом событий. Имя собеседника берём тем же механизмом,
    // что везде (Д14): карточка справочника поверх книги — иначе человек увидит
    // идентификатор вместо имени ровно в тот момент, когда решает, брать ли трубку.
    val callPing by assembled.callPings.collectAsState()
    LaunchedEffect(callPing) {
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
    }

    // ── ПОРУЧЕНИЕ ИЗ СТРОКИ ЗВОНКА (ВЗ1, ВЗ2) ──────────────────────────────
    //
    // «Принять» на замке или нажатие на полноэкранный вызов поднимает окно; звонок к этому
    // времени мог ещё не дойти до `CallHost` (окно новое, лента применится через миг).
    // Поэтому ждём, пока звонок станет нашим, и только тогда исполняем.
    val callOrder by CallRequests.order.collectAsState()
    LaunchedEffect(callOrder, callHost.active, callPing) {
        val order = callOrder ?: return@LaunchedEffect
        if (!callHost.active || !callHost.callIs(order.callId)) return@LaunchedEffect
        CallRequests.done(order.callId)
        showCall()
        if (order.accept && callHost.incoming && callHost.state.stage != CallStage.Connected) {
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

    // Откуда ушли в настройки (ПЛАН-ОТЛАДКИ.md, Б2). Запоминается ЗДЕСЬ, в момент
    // перехода: к моменту отправки отчёта «текущее окно» будет «Настройки», то есть
    // бесполезным. Вкладка есть только у окна «Телефон» — у остальных её пока нет, и
    // выдумывать нечего.
    var cameFrom by remember { mutableStateOf<Origin?>(null) }
    /** Черновик отчёта о проблеме — подставляется из подокна неотправленного сообщения. */
    var problemDraft by remember { mutableStateOf("") }

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
            // (ПЛАН-ЯЗЫКА, «что не переводится»).
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
        linkCode?.let { where = Where.Link(it) }
    }

    // Код передачи приходит тем же путём и ведёт на приём: человек навёл камеру на чужой
    // QR, и единственное, чего он ждёт, — поле для фразы. Сторона задаётся здесь, а не
    // переключателем на экране: принёсший код — принимающий, других вариантов нет.
    LaunchedEffect(transferCode) {
        transferCode?.let { where = Where.Transfer(virtualUserId = null, brought = it) }
    }

    val socialState by social.state.collectAsState()
    val listState by list.state.collectAsState()

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

    val personOfChat: (ChatSummary) -> ChatPerson? = { chat ->
        chat.peerId?.takeIf { chat.kind == ChatKind.Personal }?.let { id ->
            people.want(listOf(id))
            val entry = entryOfChat(chat)
            (peopleCards[id] ?: ChatPerson()).withBookName(entry?.name, entry?.phone)
        }
    }
    val faceOfChat: (ChatSummary) -> ImageBitmap? = { chat ->
        chat.peerId?.takeIf { chat.kind == ChatKind.Personal }?.let { id ->
            people.wantFace(id)
            peopleFaces[id]
        }
    }
    // Янтарная цифра раздела — сколько людей раздела написали новое. Считается по личным
    // перепискам: у переписки есть непрочитанное, у собеседника — раздел в книге.
    val newInSection: (String) -> Int = { key ->
        val id = if (key == COMMON_SECTION) "" else key
        val everyone = key.isEmpty() || key == ALL_SECTION
        listState.personal.count { chat -> chat.unread > 0 && (everyone || sectionOfChat(chat) == id) }
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

    // Фоновые циклы — в своём файле: это политика времени, а не навигация.
    BackgroundLoops(assembled, platform, changeSign = listState, onPending = onPending)

    // Копия книги между устройствами (Р2а): забрать при запуске, отдавать после правок.
    // Один раз на сборку — `remember`, иначе каждая перерисовка заводила бы новый цикл.
    remember(assembled) {
        BookCopySync(
            environment = environment,
            store = network.accountStore,
            keys = assembled.keyOrchestrator,
            deviceId = session.deviceId,
            scope = scope,
        ).also { it.start() }
    }

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

    // Подокно при запуске: чем кончилась прошлая установка и не пора ли обновиться
    // (решение заказчика 2026-09-06). После порога, а не до: там работать нельзя вовсе,
    // и новость об успешной установке поверх этого была бы издевательством.
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
    if (news != null) {
        NoticeScreen(
            notice = news.notice(Tima.words.update),
            // Действие уводит туда, где обновление и живёт, — на вкладку настроек
            // (решение заказчика 2026-09-06): «единая область, одна логика». Установки
            // внутри события нет вовсе, и второй копии экрана обновления больше нет.
            actions = when (news) {
                // Установилось — решать нечего, и вести некуда.
                is UpdateNews.Installed -> listOf(
                    NoticeAction("Понятно", onPick = update::dismissNews),
                )

                else -> listOf(
                    NoticeAction("Перейти к обновлению") {
                        update.dismissNews()
                        where = Where.Settings(SettingsItem.UPDATE)
                    },
                    NoticeAction("Позже", ButtonKind.Quiet, update::dismissNews),
                )
            },
            onClose = update::dismissNews,
        )
        return
    }

    if (windowSwitcher) {
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
            counters = windowCounters(listState),
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
            accounts = accounts.map { it.userId to (it.nickname.ifBlank { it.userId.take(8) }) },
            currentAccount = session.userId,
            unsent = unsent,
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

    // ── ЗВОНКИ НЕ ДОЙДУТ — ПОЛОСА (ВЗ0г) ──────────────────────────────────
    //
    // Три беды по важности: уведомления запрещены → канал «Звонки» выключен → экономия
    // батареи душит. Показывается одна, самая важная. Сверка идёт событийно: запуск
    // процесса, возврат окна, входящий (`BackgroundWatch`), — и каждая сверка обновляет
    // полосу. «Позже» прячет беду на неделю; исправили — отметка снимается, и беда,
    // вернувшаяся потом, показывается сразу.
    var bgFacts by remember { mutableStateOf(backgroundFacts()) }
    DisposableEffect(Unit) {
        BackgroundWatch.onCheck = { bgFacts = it }
        onDispose { BackgroundWatch.onCheck = null }
    }
    var bgLater by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(Unit) { environment.settings.all().collect { bgLater = it } }
    val bgTrouble = when {
        bgFacts.notices == false -> BackgroundTrouble.Notices
        bgFacts.calls == false -> BackgroundTrouble.Calls
        bgFacts.awake == false -> BackgroundTrouble.Battery
        else -> null
    }
    // Исправленная беда забывает своё «Позже»: иначе, вернувшись, она молчала бы неделю.
    LaunchedEffect(bgFacts) {
        val fixed = buildList {
            if (bgFacts.notices == true) add(BackgroundTrouble.Notices)
            if (bgFacts.calls == true) add(BackgroundTrouble.Calls)
            if (bgFacts.awake == true) add(BackgroundTrouble.Battery)
        }
        for (t in fixed) {
            if (!bgLater[bgLaterKey(t)].isNullOrEmpty()) environment.settings.put(bgLaterKey(t), "")
        }
    }
    val bgShown = bgTrouble?.takeIf { t ->
        (bgLater[bgLaterKey(t)]?.toLongOrNull() ?: 0L) <= msNow()
    }
    LaunchedEffect(bgShown) {
        bgShown?.let { Journal.note(bgCode(it), "показали полосу «звонки не дойдут»", "беда" to it.name) }
    }
    // ВЗ0б: разрешение на уведомления спрашивается само — один раз на установку. Дальше
    // только полосой и кнопкой в «Настройки → Уведомления»: спрашивать на каждом запуске
    // значило бы надоесть, а после второго отказа система и не покажет вопроса.
    LaunchedEffect(Unit) {
        val asked = environment.settings.all().first()[NOTICES_AUTO_ASKED]
        if (asked.isNullOrEmpty() && notifyAccessWay() == NotifyAccessWay.Ask) {
            environment.settings.put(NOTICES_AUTO_ASKED, "1")
            Journal.note(LogCode.BG_NOTICES, "спрашиваем разрешение на уведомления — первый запуск")
            askNotifyAccess { BackgroundWatch.check("ответ на разрешение уведомлений") }
        }
    }

    // Плашка идущего звонка — во всех окнах, кроме самого звонка: предлагать «перейти в
    // звонок» тому, кто в нём стоит, незачем. Окно 0 временное, и без плашки оно
    // теряется: ушёл свайпом в «Чаты» — и не знаешь, разговор идёт или уже кончился.
    CompositionLocalProvider(
        LocalActiveCall provides ActiveCall(
            seconds = callHost.seconds,
            onOpen = { showCall() },
        ).takeIf { callHost.active && window != Window.Call },
        LocalBackgroundWarning provides bgShown?.let { trouble ->
            BackgroundWarning(
                trouble = trouble,
                onFix = {
                    Journal.note(bgCode(trouble), "нажали «Включить» на полосе", "беда" to trouble.name)
                    where = Where.Settings(SettingsItem.NOTIFICATIONS)
                },
                onLater = {
                    Journal.note(bgCode(trouble), "«Позже» — полоса спрятана на неделю", "беда" to trouble.name)
                    scope.launch {
                        environment.settings.put(bgLaterKey(trouble), (msNow() + BG_LATER_MS).toString())
                    }
                },
            )
        },
    ) {
    Stage(
        modifier = Modifier.fillMaxSize(),
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
                counters = windowCounters(listState),
                onSettings = toSettings,
                inCall = callHost.active,
                bench = benchState.on,
            )
        },
        column = {
            when (window) {
                // Окно 0 — звонок. Временное: пока идёт разговор. Экран чистый, всю
                // работу держит CallHost.
                Window.Call -> CallScreen(
                    state = callHost.state,
                    peer = callHost.peer,
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
                        )
                    } else {
                        null
                    },
                    remoteVideo = callHost.remoteVideo.collectAsState().value,
                    localVideo = callHost.localVideo.collectAsState().value,
                    onRemoteVideo = callHost::remoteVideo,
                    onEventAction = callHost::act,
                    // Жест берётся тот же, что у остальных окон, и вешается ЗДЕСЬ:
                    // у пяти настоящих окон он живёт в их оправе, а окно 0 рисуется
                    // голым — оправы с шапкой и вкладками у звонка нет. Без этой
                    // строки окно 0 было окном, из которого нельзя выйти пальцем
                    // (живой прогон 2026-09-20, ЗВ2).
                    modifier = Modifier.windowSwipe(
                        onLeft = { switchWindow(InSide.Next) },
                        onRight = { switchWindow(InSide.Previous) },
                    ),
                    onAccept = callHost::accept,
                    onDecline = callHost::hangUp,
                    onHangUp = callHost::hangUp,
                    onMicrophone = callHost::microphone,
                    onCamera = callHost::camera,
                    onCallAgain = callHost::again,
                    redialVideo = callHost.video,
                    onRedialKind = callHost::redialAs,
                    onClose = {
                        callHost.close()
                        window = Window.Phone
                    },
                )

                Window.Phone -> PhoneWindow(
                    tab = phoneTab,
                    onTab = { phoneTab = it },
                    myUserId = session.userId,
                    callsState = callsState,
                    // Имя и лицо в журнале — тем же механизмом, что везде (Д14): сервер
                    // знает только `user_id`, а «Аня Борисова» живёт в книге.
                    personOfCall = { record ->
                        val id = record.other(session.userId)
                        bookState.all.firstOrNull { it.userId == id }
                            ?.let { personOfBook(it) }
                            ?: ChatPerson()
                    },
                    faceOfCall = { record ->
                        val id = record.other(session.userId)
                        people.wantFace(id)
                        peopleFaces[id]
                    },
                    // Повтор звонит ТЕМ ЖЕ видом, каким звонили тогда (Ж6). Кнопка,
                    // молча звонящая голосом вместо видео, выглядит поломкой ровно один
                    // раз — а потом ей перестают верить.
                    onCallAgain = if (callHost.possible) {
                        { record ->
                            val id = record.other(session.userId)
                            val name = bookState.all.firstOrNull { it.userId == id }
                                ?.let { personOfBook(it).line(bookState.view.look(), PERSON_FIRST_LINE) }
                                .orEmpty()
                            callPerson(id, name, record.video)
                        }
                    } else {
                        null
                    },
                    onOpenedCalls = callsLog::opened,
                    // Имя пользователя и ник у контактов — со справочника, пачкой по разу.
                    personOf = personOfBook,
                    // Картинка аватара — по карточке справочника, приезжает потоком.
                    faceOf = { entry -> entry.userId?.let { people.wantFace(it); peopleFaces[it] } },
                    // ТОЛЬКО личные. Группы ушли на свою вкладку окна 5 — решение
                    // заказчика 2026-09-17; до него они стояли здесь вперемешку с
                    // личными, и это было временным размещением, записанным в
                    // `ИНТЕРФЕЙС/04-социум/ФУНКЦИОНАЛ.md`.
                    list = listState.copy(chats = listState.personal),
                    // Ники — со справочника: в книге их нет, а искать по ним надо
                    // (Л19). У человека без номера ник — единственное, чем его найти.
                    book = bookState.copy(
                        nicks = peopleCards.mapNotNull { (id, card) -> card.nick?.let { id to it } }.toMap(),
                    ),
                    onSearchInBook = book::changedSearch,
                    personOfChat = personOfChat,
                    faceOfChat = faceOfChat,
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
                    // Списки обновляются при входе в окно: возвращаясь из группы, человек
                    // должен видеть её на месте, а не прежний снимок.
                    LaunchedEffect(Unit) { social.refresh() }
                    // Раздел группы — из местной базы (`chats.section_id`); сервер разделов
                    // не знает. Группы без строки в базе — общий раздел.
                    val sectionOfGroup: (String) -> String = { id ->
                        listState.groups.firstOrNull { it.chatId == id }?.sectionId.orEmpty()
                    }
                    val freshInCatalog: (String) -> Int = { key ->
                        val id = if (key == COMMON_SECTION) "" else key
                        val everyone = key.isEmpty() || key == ALL_SECTION
                        listState.groups.count { it.unread > 0 && (everyone || it.sectionId == id) }
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
                    // Жест тот же, что у окна 0, и по той же причине: у стенда нет оправы
                    // с шапкой, а окно, из которого нельзя выйти пальцем, — не окно.
                    modifier = Modifier.windowSwipe(
                        onLeft = { switchWindow(InSide.Next) },
                        onRight = { switchWindow(InSide.Previous) },
                    ),
                    )
                }

                Window.Page -> PageWindow(
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
                                        listState.groups.count { it.unread > 0 && (everyone || it.sectionId == id) }
                                    },
                                )
                            }
                            GroupsScreen(
                                state = listState,
                                onOpen = { where = Where.Chat(it.chatId, it.title) },
                                chosen = groupSection,
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
                        opened = current.item,
                        onOpen = { where = Where.Settings(it) },
                        network = network,
                        scope = scope,
                        platform = platform,
                        build = build,
                        appearance = appearance,
                        onAppearance = onAppearance,
                        language = language,
                        onLanguage = onLanguage,
                        locale = locale,
                        onBack = { where = Where.Nothing },
                        profile = profile,
                        profileState = profileState,
                        virtuals = virtuals,
                        virtualsState = virtualsState,
                        onNewVirtual = { where = Where.NewVirtual },
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
                        reporting = reporting,
                        diaryPolicy = diaryPolicy,
                        callsLog = callsLog,
                        callsState = callsState,
                        deviceSettings = environment.settings,
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
                            )
                        },
                    )
                }
            }

            is Where.Link -> {
                {
                    LinkConfirmation(
                        network = network,
                        deviceSecret = deviceSecret,
                        code = current.code,
                        scope = scope,
                        onClose = { where = Where.Nothing },
                    )
                }
            }

            is Where.Chat -> {
                {
                    Chat(
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
                        // Заглушка, и объяснение показывает сам экран: звать снаружи
                        // нечего, групповых звонков нет ни в одном плане (решение
                        // заказчика 2026-09-20).
                        groupCall = callHost.possible,
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
    }
}

@Composable
private fun Chat(
    environment: Environment,
    network: ChatPorts,
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
) {
    // Групповая ли переписка — решает столбец `kind`, а не догадка по идентификатору.
    var chatMenu by remember { mutableStateOf(false) }
    // От этого зависит трое: показывать ли автора у реплик, спрашивать ли имена и есть ли
    // вход в состав.
    val group = remember(chatId) {
        environment.chatFacts.kindOf(chatId) == ChatKind.Group
    }
    // Пока переписка на экране — уведомлений о ней нет (У10): человек читает её глазами,
    // и строка в шторке была бы уведомлением о том, что он уже видит. `DisposableEffect`,
    // а не `LaunchedEffect`: уход с экрана обязан снять отметку, иначе закрытая переписка
    // останется «открытой» навсегда и замолчит насовсем.
    DisposableEffect(chatId, notices) {
        notices?.watching(chatId)
        onDispose { notices?.watching(null) }
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
                RequestGroupKeys(GroupKeyRecoveryOverHttp(network.keyRecovery))
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
    var failed by remember { mutableStateOf<ChatLine?>(null) }
    // Выбран круг, которого у группы этого вида нет (остался с прежней сборки или от
    // другой группы) — сбросить на первый допустимый, иначе отправка получит 400.
    LaunchedEffect(kind, state.level) {
        val allowed = kind?.circles ?: return@LaunchedEffect
        if (allowed.none { it.level == state.level }) store.circleChosen(allowed.first().level)
    }
    var myColor by remember { mutableStateOf(false) }
    var myColorTrouble by remember { mutableStateOf<String?>(null) }
    ChatScreen(
        state = state,
        onPerson = onPerson,
        onCall = onCall,
        peerFace = peerFace,
        authorLook = authorLook,
        ownerId = ownerId,
        hues = hues,
        peer = name ?: "Без имени",
        onSet = store::draftChanged,
        onSend = { store.sendPressed() },
        onBack = onBack,
        onCloseMessage = store::noticeDismissed,
        onRequestKey = store::requestKey,
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
            group = false,
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
    code: String,
    scope: kotlinx.coroutines.CoroutineScope,
    onClose: () -> Unit,
) {
    val store = remember(code) {
        LinkStore(
            confirm = network.linkConfirmation(deviceIdentityFrom(deviceSecret)),
            scope = scope,
            code = code,
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
    network: DevicePorts,
    scope: kotlinx.coroutines.CoroutineScope,
    platform: Platform,
    build: Build,
    appearance: Appearance,
    onAppearance: (Appearance) -> Unit,
    language: Language,
    onLanguage: (Language) -> Unit,
    /** Страна и отбор выдачи (ПЛАН-ЯЗЫКА Я7): живут рядом с выбором языка. */
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
    /** Что уйдёт в отчёте о проблеме, кроме текста и журнала (ПЛАН-ОТЛАДКИ.md, Б3). */
    problemFacts: ProblemFacts,
    /** Черновик отчёта о проблеме — из подокна неотправленного сообщения. */
    problemDraft: String = "",
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
    /** Настройки устройства — выбор звуков (ВЗ4) живёт здесь и не синхронизируется. */
    deviceSettings: io.tima.domain.chat.Settings,
) {
    // Название темы считается в составе, а не в лямбде списка: лямбда не composable.
    val themeName = Tima.words.appearance.theme(appearance.choice)
    val fleet = remember { DevicesStore(network.myFleet, scope) }
    val devices by fleet.state.collectAsState()

    SettingsScreen(
        opened = opened,
        onOpen = { onOpen(it) },
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
                VirtualsScreen(
                    state = virtualsState,
                    onCreate = onNewVirtual,
                    onGive = { onTransfer(it) },
                    onTake = { onTransfer(null) },
                )
            }

            SettingsItem.DEVICES -> Devices(fleet, devices, build.name)

            // Уведомления (У1, У14). Пункт стоял в списке с самого начала и не
            // открывал ничего; теперь здесь два действия, без которых уведомления на
            // Android не работают: право показывать и «не усыплять».
            SettingsItem.NOTIFICATIONS -> {
                // Состояние читается при каждом заходе, а не запоминается: человек мог
                // сменить разрешение в системных настройках, пока нас не было.
                var awake by remember { mutableStateOf(awakeAllowed()) }
                var access by remember { mutableStateOf(notifyAccessWay()) }
                NotificationsScreen(
                    access = when (access) {
                        NotifyAccessWay.Given -> NotifyAccess.Given
                        NotifyAccessWay.Ask -> NotifyAccess.Ask
                        NotifyAccessWay.Settings -> NotifyAccess.Settings
                    },
                    onAsk = { askNotifyAccess { access = notifyAccessWay() } },
                    // Строки нет вовсе там, где усыплять некому (ПК): неактивная кнопка
                    // тоже зовёт нажать, а нажимать здесь не на что.
                    onBattery = if (platform == Platform.DESKTOP) {
                        null
                    } else {
                        { askAwake(); awake = awakeAllowed() }
                    },
                    batteryFree = awake,
                    // Канал «Звонки» (ВЗ0г): состояние читается при каждом заходе — его
                    // меняют в настройках телефона, пока нас нет.
                    callsChannelOn = if (platform == Platform.DESKTOP) null else backgroundFacts().calls,
                    onCallsChannel = if (platform == Platform.DESKTOP) null else ::openCallsChannelSettings,
                    fullScreenOn = if (platform == Platform.DESKTOP) null else backgroundFacts().fullScreen,
                    onFullScreen = if (platform == Platform.DESKTOP) null else ::openFullScreenSettings,
                    // Звуки (ВЗ4): общий выбор — в настройках устройства, не синхронизируется.
                    ring = soundRow(deviceSettings, SoundKeys.RING, SoundUse.Ring, "ring"),
                    message = soundRow(deviceSettings, SoundKeys.MESSAGE, SoundUse.Message, "message"),
                )
            }

            SettingsItem.APPEARANCE -> AppearanceScreen(appearance, onAppearance)

            // Шрифты и размеры — второй пункт вида (ПЛАН-ШРИФТОВ Ш4).
            SettingsItem.TEXT -> TextLookScreen(appearance, onAppearance)

            // Выбор языка приложения (ПЛАН-ЯЗЫКА Я1). Сообщения не переводятся, и экран
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
            SettingsItem.PROBLEM -> Problem(problemFacts, origin, reporting, scope, platform, snapshot, draft = problemDraft)

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
) {
    DeviceScreen(
        state = state,
        onAsk = store::ask,
        onConfirm = store::revoke,
        onChangedMind = store::changedMind,
        buildVersion = buildVersion,
    )
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
 * «Память и трафик»: что занимает место и когда убирается (ПЛАН-ПАМЯТИ.md).
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
 * Отчёт о проблеме — ПЛАН-ОТЛАДКИ.md, Б3.
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
        )
    }
    val state by store.state.collectAsState()
    ProblemScreen(
        state = state,
        onText = store::changedText,
        onKind = store::chose,
        onBegan = store::chose,
        onShow = store::toggleShowing,
        onSend = store::send,
    )
}

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
    // По-русски всегда: тело отчёта читает чинящий (ПЛАН-ЯЗЫКА §4).
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
 * Как назвать открытый экран в журнале.
 *
 * **Имена, а не идентификаторы.** `Where.Chat(7f3a…)` в отчёте бесполезен: читающему
 * нужно знать, что человек открыл переписку, а не какую именно — и уж точно не нужен
 * идентификатор чужого разговора в нашем хранилище отчётов.
 */
private fun whereWords(where: Where): String = when (where) {
    Where.Nothing -> "список"
    Where.New -> "новая переписка"
    Where.Profile -> "профиль"
    Where.SelfPage -> "моя страница"
    Where.NewGroup -> "новая группа"
    Where.NewVirtual -> "новый виртуальный аккаунт"
    is Where.Members -> "состав группы"
    is Where.Chat -> "переписка"
    // Без идентификатора: журналу нужно знать, что человек открыл чужую страницу, а чью
    // именно — в отчёте не нужно никому.
    is Where.Person -> "личная страница"
    is Where.Access -> "доступ к закрытым записям"
    // Ни канала, ни номера записи: в журнале нужно знать, что человек читал разговор, а
    // не под какой записью — идентификатор чужого поста в отчёте не нужен никому.
    is Where.Comments -> "комментарии"
    is Where.Community -> "сообщество"
    is Where.Link -> "подтверждение привязки устройства"
    is Where.Transfer -> if (where.virtualUserId == null) "приём аккаунта" else "передача аккаунта"
    // Место в журнале — по-русски и всегда: журнал читает чинящий (ПЛАН-ЯЗЫКА §4).
    is Where.Settings ->
        "настройки" + (where.item?.let { ": " + RussianWords.settings2.item(it) } ?: "")
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
                    book = SqlGroupKeys(environment.db, environment.cipher),
                    msNow = ::msNow,
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
    )
}

/**
 * Сводные счётчики непрочитанного по окнам.
 *
 * Число сегодня одно и настоящее — непрочитанные сообщения окна 1. У остальных окон
 * его нет, и подставлять туда ноль было бы не честнее: ноль означает «прочитано всё»,
 * а правда в том, что считать нечего — социального слоя на сервере нет.
 */
private fun windowCounters(list: ChatsState): Map<Window, Int> {
    // Счётчик идёт ЗА перепиской, а не остаётся там, где она лежала. Группы уехали на
    // вкладку окна 5 — значит и непрочитанное в них считается окну 5. Иначе человек видит
    // янтарную точку на «Телефоне», открывает его и не находит там ничего: счётчик
    // указывает в пустоту, и это хуже отсутствующего счётчика.
    val phone = list.personal.sumOf { it.unread }
    val page = list.groups.sumOf { it.unread }
    return buildMap {
        if (phone > 0) put(Window.Phone, phone)
        if (page > 0) put(Window.Page, page)
    }
}

/**
 * Текст приглашения — один на все три способа.
 *
 * СМС и «поделиться» несут одну и ту же строку: разница только в том, чем её понесут.
 * Ссылки-приглашения с меткой пригласившего здесь нет — она отдельная работа, и без неё
 * нельзя узнать, кто кого привёл (развилка в ПЛАН-КОНТАКТОВ.md).
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
    list: ChatsState,
    book: BookState,
    /** Человек за строкой книги — имя из книги плюс карточка справочника. */
    personOf: (BookEntry) -> ChatPerson = { ChatPerson(name = it.name, phone = it.phone) },
    /** Собеседник личной переписки — для строки списка чатов; `null` у групп. */
    personOfChat: (ChatSummary) -> ChatPerson? = { null },
    /** Аватар собеседника личной переписки. */
    faceOfChat: (ChatSummary) -> ImageBitmap? = { null },
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
    faceOfCall: (CallRecord) -> ImageBitmap? = { null },
    /** Перезвонить из журнала — **тем же видом**, каким звонили тогда (Ж6). */
    onCallAgain: ((CallRecord) -> Unit)? = null,
    /** Открыли вкладку «Звонки»: сходить за свежим журналом и погасить счётчик. */
    onOpenedCalls: () -> Unit = {},
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
        countOf = { which -> if (which == WindowTab.Calls) callsState.missed else 0 },
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
                { { SectionsRow(book.tabs(bookWords), book.chosen, book.view.icons, onChooseSection, newIn = newInSection) } }
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
                look = book.view.look(),
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
                    newIn = newInSection,
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
                LaunchedEffect(Unit) { onOpenedCalls() }
                CallsScreen(
                    // ── ФИЛЬТРЫ ВТОРОГО РЯДА ────────────────────────────────
                    //
                    // «Из книги» и «Неизвестные» отбираются **по книге, а не по
                    // серверу**: знает ли телефон этого человека — вопрос к телефону.
                    // «Неизвестные» у нас поэтому означает «звонил тот, кого нет в
                    // книге», а не «звонок с чужого номера»: чужих номеров в нашем
                    // журнале не бывает вовсе — звонить может только аккаунт.
                    records = callsState.records.filter { record ->
                        val known = book.all.any { it.userId != null && it.userId == record.other(myUserId) }
                        when (calls) {
                            WindowTab.FromBook -> known
                            WindowTab.Unknown -> !known
                            WindowTab.Missed -> record.outcome(myUserId) == CallOutcome.Missed
                            else -> true
                        }
                    },
                    me = myUserId,
                    personOf = personOfCall,
                    faceOf = faceOfCall,
                    look = book.view.look(),
                    onCallAgain = onCallAgain,
                    offline = callsState.offline,
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
private fun bgCode(trouble: BackgroundTrouble): String =
    if (trouble == BackgroundTrouble.Battery) LogCode.BG_POWER else LogCode.BG_NOTICES

/** «Позже» — неделя (решение заказчика 2026-09-26). */
private const val BG_LATER_MS = 7L * 24 * 60 * 60 * 1000

/** Разрешение на уведомления спрошено само — один раз на установку (ВЗ0б). */
private const val NOTICES_AUTO_ASKED = "notices.autoAsked"

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
        current = when (choice) {
            SoundChoice.Default -> words.soundDefault
            SoundChoice.Silent -> words.soundSilent
            is SoundChoice.System -> choice.title.ifBlank { words.soundDefault }
            is SoundChoice.File -> choice.title
        },
        onSystem = if (systemSoundsAvailable) system else null,
        onFile = file,
        onSilent = { save(SoundChoice.Silent) },
        onDefault = { save(SoundChoice.Default) },
        trouble = trouble,
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
