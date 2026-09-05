package io.tima.shared

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import io.tima.core.encryption.AccountIdentitiesOverKodium
import io.tima.core.encryption.PersonalChatIdsOverKodium
import io.tima.core.encryption.deviceIdentityFrom
import io.tima.core.database.SqlChatBook
import io.tima.core.database.SqlGroupKeys
import io.tima.core.network.GroupKeyRecoveryOverHttp
import io.tima.core.network.GroupsOverHttp
import io.tima.domain.chat.MessageCircle
import io.tima.domain.chat.NarrowMessageLevel
import io.tima.domain.chat.ChatKind
import io.tima.domain.chat.ChatSummary
import io.tima.domain.chat.Contact
import io.tima.domain.chat.ChatNames
import io.tima.domain.chat.CreateGroupChat
import io.tima.domain.chat.ManageGroupMembers
import io.tima.domain.chat.RequestGroupKeys
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
import io.tima.feature.chat.ChatsState
import io.tima.feature.chat.ChatsStore
import io.tima.feature.chat.BookState
import io.tima.core.contacts.platformInvite
import io.tima.core.secrets.Account
import io.tima.core.contacts.platformPhoneBook
import io.tima.core.ui.Tab
import io.tima.domain.chat.BookEntry
import io.tima.domain.chat.AddContact
import io.tima.domain.chat.SyncBook
import io.tima.feature.chat.BookStore
import io.tima.feature.chat.BookViewSheet
import io.tima.feature.chat.InviteScreen
import io.tima.feature.chat.NewContactScreen
import io.tima.feature.chat.NewContactStore
import io.tima.feature.chat.ProfileScreen
import io.tima.feature.chat.ProfileState
import io.tima.feature.chat.ProfileStore
import io.tima.feature.chat.BookScreen
import io.tima.feature.shell.CALL_FILTERS
import io.tima.feature.shell.Window
import io.tima.feature.shell.MediaWindow
import io.tima.feature.shell.ActivityWindow
import io.tima.feature.shell.SocialWindow
import io.tima.feature.shell.PageWindow
import io.tima.feature.shell.InSide
import io.tima.feature.shell.WindowFrame
import io.tima.feature.shell.Rail
import io.tima.feature.shell.TabStub
import io.tima.feature.shell.FilterRow
import io.tima.feature.shell.WindowSwitchingScreen
import io.tima.feature.shell.SettingsItem
import io.tima.core.ui.Appearance
import io.tima.core.ui.merged
import io.tima.core.ui.TimaTheme
import androidx.compose.foundation.isSystemInDarkTheme
import io.tima.feature.shell.AppearanceScreen
import io.tima.feature.shell.SettingsScreen
import io.tima.feature.shell.UpdateOffer
import io.tima.feature.shell.UpdateSection
import io.tima.feature.shell.UpdateStore
import io.tima.feature.chat.NewChatStore
import io.tima.feature.chat.PageStore
import io.tima.feature.chat.NewChatScreen
import io.tima.feature.chat.PageScreen
import io.tima.feature.chat.ChatScreen
import io.tima.feature.chat.ChatsScreen

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
    linkCode: String? = null,
    /** Номер сборки от платформы: общий код его знать не может и не должен. */
    build: Build = Build(),
) {
    // Системная тема спрашивается ровно один раз и только затем, чтобы решить, с чего
    // начать при первом запуске. Дальше решает человек.
    val systemDark = isSystemInDarkTheme()
    var appearance by remember {
        mutableStateOf(Appearance.read(appearanceStore.load(), systemDark))
    }

    TimaTheme(colors = appearance.colors) {
        Inside(
            entry = entry,
            deviceDatabase = deviceDatabase,
            linkCode = linkCode,
            build = build,
            appearance = appearance,
            onAppearance = {
                appearance = it
                appearanceStore.save(it.write())
            },
        )
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
    linkCode: String?,
    build: Build,
    appearance: Appearance,
    onAppearance: (Appearance) -> Unit,
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

    // Сборка живёт в Assembly.kt: здесь навигация, а не «кто из чего состоит».
    // Первый аккаунт сохраняет прежнее имя базы: у того, кто уже пользуется
    // приложением, переписка лежит в `tima.db`, и переименование потеряло бы её.
    val assembled = assemble(entry, current, deviceDatabase, entry.accountList().firstOrNull()?.userId)

    App(
        assembled = assembled,
        platform = entry.platform,
        deviceSecret = current.secret,
        linkCode = linkCode,
        build = build,
        appearance = appearance,
        onAppearance = onAppearance,
        accounts = entry.accountList(),
        // Переключение меняет указатель и перечитывает устройство. Всё остальное
        // пересобирается само: `assemble` помнит по устройству, а у другого аккаунта
        // другая сессия и другой ключ покоя.
        onSwitchAccount = { userId ->
            entry.switchAccount(userId)
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
    /** Номер сборки — показывается в «Устройствах», см. пояснение там. */
    build: Build,
    appearance: Appearance,
    onAppearance: (Appearance) -> Unit,
    /** Аккаунты этого устройства: основной и его виртуальные (Д11). */
    accounts: List<Account> = emptyList(),
    onSwitchAccount: (String) -> Unit = {},
) {
    val environment = assembled.environment
    val network = assembled.network
    val session = assembled.session
    val scope = rememberCoroutineScope()
    val list = remember { ChatsStore(environment.chats, scope) }
    var where by remember { mutableStateOf<Where>(Where.Nothing) }

    // Какое окно открыто. Приложение начинается с окна 1: личная связь — то, ради
    // чего его открывают чаще всего, а остальные окна пока пусты по существу.
    var window by remember { mutableStateOf(Window.Phone) }
    var windowSwitcher by remember { mutableStateOf(false) }
    // Подокно «Вид» вкладки «Контакты»: настроек три группы и они независимы, перебор
    // по кругу не дал бы угадать следующее состояние.
    var bookView by remember { mutableStateOf(false) }
    // Кого приглашаем. null — подокно закрыто: отдельного флага не заводим, чтобы
    // «открыто, но некого» не стало возможным состоянием.
    var inviting by remember { mutableStateOf<BookEntry?>(null) }
    var newContact by remember { mutableStateOf(false) }

    val contacts = remember {
        NewContactStore(
            add = AddContact(environment.bookStorage, network.friends, network.discovery),
            book = environment.bookStorage,
            discovery = network.discovery,
            scope = scope,
        )
    }
    val contactsState by contacts.state.collectAsState()
    val invite = remember { platformInvite() }
    val profile = remember {
        // Номер сюда не приходит: в сессии его нет (userId, deviceId, токен), а
        // сервер отдаёт телефон только собеседникам по переписке. Строка номера в
        // профиле поэтому пуста — до тех пор, пока номер не начнёт храниться рядом
        // с сессией. Числится в ПЛАН-КОНТАКТОВ.md, Д8.
        ProfileStore(profile = network.profile, phone = "", scope = scope)
    }
    val profileState by profile.state.collectAsState()

    // Контакты: своя книга — прочитанное с телефона плюс заведённое руками (Д2…Д5).
    // Поток из базы: прочитанное и итог сверки появляются сами, без опроса.
    val book = remember {
        BookStore(
            book = environment.book,
            settings = environment.settings,
            sync = SyncBook(platformPhoneBook(), environment.bookStorage, network.discovery),
            scope = scope,
        )
    }
    // Окно 2 «Социум»: свои группы и карточки, которые открыли контакты. Списки живут
    // здесь, а не в оболочке: рама знает раму, работа с сервером — дело feature-group.
    val social = remember { SocialStore(GroupsOverHttp(network.groups), scope) }
    // Окно 5 «Страница»: своя лента — своё и принесённое. Один Store на приложение: одна
    // страница у человека, и второй показывал бы то же самое со своим отставанием.
    val page = remember { PageStore(network.pages, scope) }
    var phoneTab by remember { mutableStateOf("Чаты") }

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

    val socialState by social.state.collectAsState()
    val listState by list.state.collectAsState()
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
    BackgroundLoops(assembled, platform, changeSign = listState)

    // Свайп по средней зоне ведёт к соседнему окну в порядке переключателя. Края
    // не заворачиваются: с первого окна влево уйти некуда, и это честнее кольца —
    // человек, дойдя до края, видит, что край есть.
    val switchWindow: (InSide) -> Unit = { where_ ->
        val order = Window.entries
        val next = order.indexOf(window) + if (where_ == InSide.Next) 1 else -1
        order.getOrNull(next)?.let {
            window = it
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
        NewContactScreen(
            state = contactsState,
            onPhone = contacts::changedPhone,
            onName = contacts::changedName,
            onSection = contacts::changedSection,
            onSave = { contacts.save { newContact = false } },
            onBack = { newContact = false },
        )
        return
    }

    if (bookView) {
        BookViewSheet(
            view = bookState.view,
            onChange = book::changedView,
            onClose = { bookView = false },
        )
        return
    }

    if (windowSwitcher) {
        WindowSwitchingScreen(
            current = window,
            name = session.userId,
            alias = "@" + session.userId.take(8),
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
            // «Изменить» в шапке: первый из двух входов в профиль.
            onProfile = {
                where = Where.Profile
                windowSwitcher = false
            },
            // Аккаунты — здесь же: это единственное место, где человек видит, от чьего
            // лица он в приложении, и менять это надо там же, где смотрят.
            accounts = accounts.map { it.userId to (it.nickname.ifBlank { it.userId.take(8) }) },
            currentAccount = session.userId,
            onAccount = { userId ->
                windowSwitcher = false
                onSwitchAccount(userId)
            },
            onClose = { windowSwitcher = false },
        )
        return
    }

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
                onSettings = { where = Where.Settings() },
            )
        },
        column = {
            when (window) {
                Window.Phone -> PhoneWindow(
                    tab = phoneTab,
                    onTab = { phoneTab = it },
                    list = listState,
                    book = bookState,
                    onSearchInBook = book::changedSearch,
                    onOpen = { where = Where.Chat(it.chatId, it.title) },
                    // Открыть можно только того, кто в TIMa: у остальных переписки нет
                    // и завести её не из чего — им «Пригласить».
                    onOpenPerson = { person ->
                        val id = person.userId
                        if (id != null) {
                            val chatId = PersonalChatIdsOverKodium.personalChatId(session.userId, id)
                            where = Where.Chat(chatId, person.name)
                        }
                    },
                    onNew = { where = Where.New },
                    onSettings = { where = Where.Settings() },
                    onSwitchWindows = { windowSwitcher = true },
                    onNeighbourWindow = switchWindow,
                    onView = { bookView = true },
                    onToggleSection = book::openedSection,
                    onAddContact = { newContact = true },
                    onInvite = { inviting = it },
                )

                Window.Social -> {
                    // Списки обновляются при входе в окно: возвращаясь из группы, человек
                    // должен видеть её на месте, а не прежний снимок.
                    LaunchedEffect(Unit) { social.refresh() }
                    SocialWindow(
                        onSwitchWindows = { windowSwitcher = true },
                        onSearch = {},
                        onSettings = { where = Where.Settings() },
                        onNeighbourWindow = switchWindow,
                        catalog = {
                            CatalogTab(
                                state = socialState,
                                onOpen = { where = Where.Chat(it.groupId, it.title) },
                                onNew = { where = Where.NewGroup },
                            )
                        },
                        friends = { FriendsTab(state = socialState, onAsk = social::ask) },
                    )
                }

                Window.Media -> MediaWindow(
                    onSwitchWindows = { windowSwitcher = true },
                    onSearch = {},
                    onSettings = { where = Where.Settings() },
                    onNeighbourWindow = switchWindow,
                )

                Window.Activity -> ActivityWindow(
                    onSwitchWindows = { windowSwitcher = true },
                    onSearch = {},
                    onSettings = { where = Where.Settings() },
                    onNeighbourWindow = switchWindow,
                )

                Window.Page -> PageWindow(
                    onSwitchWindows = { windowSwitcher = true },
                    onSearch = {},
                    onSettings = { where = Where.Settings() },
                    onNeighbourWindow = switchWindow,
                    // Своя страница: принесённое и своё вперемешку. Обновляется при
                    // открытии вкладки — список меняется от чужих действий (автор удалил,
                    // автор сузил), и держать его закэшированным значило бы показывать то,
                    // чего уже нет.
                    feed = {
                        val state by page.state.collectAsState()
                        LaunchedEffect(Unit) { page.refresh() }
                        PageScreen(
                            state = state,
                            onRemove = page::remove,
                            onCloseTrouble = page::troubleDismissed,
                        )
                    },
                )
            }
        },
        main = when (val current = where) {
            Where.Nothing -> null

            Where.Profile -> {
                {
                    ProfileScreen(
                        state = profileState,
                        onName = profile::changedName,
                        onNickname = profile::changedNickname,
                        onSave = profile::save,
                        onBack = { where = Where.Nothing },
                    )
                }
            }

            Where.New -> {
                {
                    NewChatScreen(
                        state = newState,
                        onNumber = new::changedNumber,
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
                        onBack = { where = Where.Nothing },
                        profile = profile,
                        profileState = profileState,
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
                        environment = environment,
                        network = network,
                        chatId = current.chatId,
                        // Имя из списка, если строка уже пришла потоком; иначе то, с чем
                        // переписку открыли. Пустое место читалось бы как поломка.
                        name = listState.chats.firstOrNull { it.chatId == current.chatId }?.title
                            ?: current.name,
                        scope = scope,
                        onBack = { where = Where.Nothing },
                        onMembers = { where = Where.Members(current.chatId, current.name) },
                        onCarry = { messageId, was -> page.carry(current.chatId, messageId, was) },
                    )
                }
            }

            Where.NewGroup -> {
                {
                    NewGroup(
                        environment = environment,
                        network = network,
                        scope = scope,
                        onBack = { where = Where.Nothing },
                        onCreated = { groupId, title -> where = Where.Chat(groupId, title) },
                    )
                }
            }

            is Where.Members -> {
                {
                    Members(
                        environment = environment,
                        network = network,
                        session = session,
                        groupId = current.groupId,
                        scope = scope,
                        onBack = { where = Where.Chat(current.groupId, current.name) },
                        onAccess = { where = Where.Access(current.groupId, current.name) },
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

@Composable
private fun Chat(
    environment: Environment,
    network: ChatPorts,
    chatId: String,
    name: String?,
    scope: kotlinx.coroutines.CoroutineScope,
    onBack: () -> Unit,
    onMembers: () -> Unit,
    /** Унести реплику к себе на страницу: `(messageId, круг записи)`. */
    onCarry: (Long, Int) -> Unit = { _, _ -> },
) {
    // Групповая ли переписка — решает столбец `kind`, а не догадка по идентификатору.
    // От этого зависит трое: показывать ли автора у реплик, спрашивать ли имена и есть ли
    // вход в состав.
    val group = remember(chatId) {
        environment.chatFacts.kindOf(chatId) == ChatKind.Group
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
            names = if (group) {
                ChatNames { userId -> network.directory.nameOrNumber(userId) ?: userId }
            } else {
                null
            },
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
    ChatScreen(
        state = state,
        peer = name ?: "Без имени",
        onSet = store::draftChanged,
        onSend = { store.sendPressed() },
        onBack = onBack,
        onCloseMessage = store::noticeDismissed,
        onRequestKey = store::requestKey,
        onPhrase = store::changedPhrase,
        onMembers = if (group) onMembers else null,
        // Круг предлагается только в группе: в личной переписке всё зашифровано и
        // адресовано одному человеку — выбирать нечего.
        circle = if (group) MessageCircle.of(state.level) else null,
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
    )
}


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
    onBack: () -> Unit,
    /** Профиль общий с переключением окон: один Store, два входа. */
    profile: ProfileStore,
    profileState: ProfileState,
) {
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
                SettingsItem.APPEARANCE -> appearance.choice.title.lowercase()
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
            )

            SettingsItem.DEVICES -> Devices(fleet, devices, build.name)

            SettingsItem.APPEARANCE -> AppearanceScreen(appearance, onAppearance)

            SettingsItem.UPDATE -> Update(network, scope, platform, build)

            else -> TabStub(
                willWhat = item.title,
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
private fun Update(
    network: DevicePorts,
    scope: kotlinx.coroutines.CoroutineScope,
    platform: Platform,
    build: Build,
) {
    val store = remember {
        UpdateStore(
            versions = {
                when (val answer = network.appVersion.latest()) {
                    is AppVersionResult.Version -> UpdateOffer(
                        versionCode = answer.versionCode,
                        versionName = answer.versionName,
                        url = answer.url,
                        notes = answer.notes,
                        stream = answer.stream,
                    )
                    AppVersionResult.NotConfigured -> null
                    is AppVersionResult.NoConnection -> error("нет связи")
                    is AppVersionResult.Refused -> error("отказ ${answer.status}")
                }
            },
            scope = scope,
            installed = build.name,
            installedCode = build.code,
            stream = build.stream,
        )
    }
    val state by store.state.collectAsState()
    UpdateSection(
        state = state,
        onCheck = store::check,
        // Скачивание — платформенное действие: на ПК это браузер, на телефоне установщик.
        // Пока не заведено, ссылка просто не нажимается, и это честнее кнопки, которая
        // делает вид. Платформа рядом, чтобы вопрос не потерялся при разводке.
        onInstall = { _ -> },
    )
    // Платформа участвует в сборке состояния и будет нужна установщику; ссылка на неё
    // держится явно, чтобы её не выкинули как неиспользуемую.
    check(platform.server.isNotBlank())
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
    scope: kotlinx.coroutines.CoroutineScope,
    onBack: () -> Unit,
    onCreated: (String, String) -> Unit,
) {
    val store = remember {
        NewGroupStore(
            creation = CreateGroupChat(
                groups = GroupsOverHttp(network.groups),
                directory = network.directory,
                chats = SqlChatBook(environment.db, environment.cipher),
            ),
            scope = scope,
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
        onAddNumber = store::addNumber,
        onRemoveNumber = store::removeNumber,
        onCreate = store::create,
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
) {
    val store = remember(groupId) {
        MembersStore(
            members = ManageGroupMembers(
                groups = GroupsOverHttp(network.groups),
                directory = network.directory,
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
        )
    }
    val state by store.state.collectAsState()

    // Состав спрашивается при открытии: он меняется чужими руками, и показывать
    // вчерашний список значит показывать неправду.
    LaunchedEffect(groupId) { store.refresh() }

    MemberScreen(
        state = state,
        onNumber = store::changedNumber,
        onInvite = store::invite,
        onRemove = store::remove,
        onBack = onBack,
        onAccess = onAccess,
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
    val unread = list.chats.sumOf { it.unread }
    return if (unread > 0) mapOf(Window.Phone to unread) else emptyMap()
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
    tab: String,
    onTab: (String) -> Unit,
    list: ChatsState,
    book: BookState,
    onSearchInBook: (String) -> Unit,
    onOpen: (ChatSummary) -> Unit,
    onOpenPerson: (BookEntry) -> Unit,
    onChooseSection: (String) -> Unit = {},
    onNew: () -> Unit,
    onSettings: () -> Unit,
    onSwitchWindows: () -> Unit,
    onNeighbourWindow: (InSide) -> Unit,
    /** «Вид» — последняя вкладка-кнопка: открывает подокно настроек списка. */
    onView: () -> Unit,
    onToggleSection: (String) -> Unit,
    onAddContact: () -> Unit,
    onInvite: (BookEntry) -> Unit,
) {
    var calls by remember { mutableStateOf(CALL_FILTERS.first()) }
    WindowFrame(
        window = Window.Phone,
        tabs = listOf("Чаты", "Контакты", "Звонки"),
        selected = tab,
        onTab = onTab,
        onSwitchWindows = onSwitchWindows,
        onSearch = {},
        onSettings = onSettings,
        onNeighbourWindow = onNeighbourWindow,
        // «Вид» стоит последней вкладкой и только у «Контактов»: у чатов и журнала
        // настраивать нечего, и кнопка там означала бы несуществующее.
        tabsTrailing = if (tab == "Контакты") {
            { Tab(label = "Вид", current = false, onClick = onView) }
        } else {
            null
        },
        // Второй ряд: у журнала фильтры, у «Контактов» в виде «меню» — разделы.
        secondRow = when {
            tab == "Звонки" -> { { FilterRow(CALL_FILTERS, calls, { calls = it }) } }
            tab == "Контакты" && !book.view.folders && book.tabs.size > 1 ->
                { { FilterRow(book.tabs, book.chosen.ifBlank { "Все" }, onChooseSection) } }
            else -> null
        },
    ) {
        when (tab) {
            "Чаты" -> ChatsScreen(
                state = list,
                onOpen = onOpen,
                onNew = onNew,
                onSettings = onSettings,
            )

            "Контакты" -> BookScreen(
                state = book,
                onSearch = onSearchInBook,
                onOpen = onOpenPerson,
                onToggleSection = onToggleSection,
                onAdd = onAddContact,
                onInvite = onInvite,
            )

            // Заглушка называет выбранный фильтр. Фильтр, от которого на экране ничего
            // не меняется, неотличим от сломанного — в него тыкают повторно.
            else -> TabStub(
                willWhat = when (calls) {
                    "Контактов" -> "Здесь будет журнал звонков от людей из книги"
                    "Неизвестные" -> "Здесь будет журнал звонков с чужих номеров"
                    "Пропущенные" -> "Здесь будет журнал пропущенных"
                    else -> "Здесь будет журнал звонков"
                },
                thanHolds = "Входящие, исходящие и пропущенные — направление стрелкой, " +
                    "длительность словами. Звонков нет: клиент LiveKit — задача К7.",
            )
        }
    }
}

