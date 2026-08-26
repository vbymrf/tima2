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
import io.tima.domain.chat.ChatKind
import io.tima.domain.chat.ChatSummary
import io.tima.domain.chat.Contact
import io.tima.domain.chat.ChatNames
import io.tima.domain.chat.CreateGroupChat
import io.tima.domain.chat.ManageGroupMembers
import io.tima.domain.chat.RequestGroupKeys
import io.tima.feature.group.NewGroupStore
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
import io.tima.feature.auth.DevicesStore
import io.tima.feature.auth.DeviceScreen
import io.tima.feature.auth.EntryScreen
import io.tima.feature.chat.ChatStore
import io.tima.feature.chat.ChatsState
import io.tima.feature.chat.ChatsStore
import io.tima.feature.chat.BookState
import io.tima.feature.chat.BookStore
import io.tima.feature.chat.BookScreen
import io.tima.feature.shell.Window
import io.tima.feature.shell.MediaWindow
import io.tima.feature.shell.ActivityWindow
import io.tima.feature.shell.SocialWindow
import io.tima.feature.shell.PageWindow
import io.tima.feature.shell.InSide
import io.tima.feature.shell.WindowFrame
import io.tima.feature.shell.Rail
import io.tima.feature.shell.TabStub
import io.tima.feature.shell.WindowSwitchingScreen
import io.tima.feature.chat.NewChatStore
import io.tima.feature.chat.NewChatScreen
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
 */
@Composable
fun Root(
    entry: Entry,
    deviceDatabase: () -> TimaDatabase,
    linkCode: String? = null,
    /** Номер сборки от платформы: общий код его знать не может и не должен. */
    buildVersion: String = "",
) {
    var device by remember { mutableStateOf(entry.created()) }

    val current = device
    if (current == null) {
        // Код, пришедший на устройство без аккаунта, ничего не значит: подтверждать
        // привязку нечем — своего ключа у него нет. Показываем обычный вход, а не
        // сообщение о беде: человек, скорее всего, просто отсканировал код не тем
        // приложением.
        Occurrence(entry, buildVersion) { device = entry.created() }
        return
    }

    // Сборка живёт в Assembly.kt: здесь навигация, а не «кто из чего состоит».
    val assembled = assemble(entry, current, deviceDatabase)

    App(assembled, entry.platform, current.secret, linkCode, buildVersion)
}

@Composable
private fun Occurrence(entry: Entry, buildVersion: String, onEntered: () -> Unit) {
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
        buildVersion = buildVersion,
    )
}

/** Что открыто в главной области. Вся навигация верхнего уровня — эти три случая. */
private sealed interface Where {
    /** Ничего: на телефоне это список, на широком формате — пустая главная область. */
    data object Nothing : Where

    /** Подокно «новая переписка». */
    data object New : Where

    /** Подокно «новая группа». */
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

    /**
     * Подтверждение привязки нового устройства.
     *
     * Приходит **снаружи**: человек навёл штатную камеру на код, та увидела `tima://link/…`
     * и открыла нас. Своего сканера у нас поэтому нет вовсе — и не нужно: чужой уже стоит
     * на каждом телефоне, а свой потребовал бы доступа к камере и объяснений, зачем он.
     */
    data class Link(val code: String) : Where

    /**
     * Свои устройства.
     *
     * Открывается кнопкой настроек в шапке, и это **не подмена**: настроек как экрана
     * (`doc_UI/25`) пока нет, а из всех их разделов существует ровно один — устройства.
     * Экран назван своим именем, поэтому человек видит, куда попал; когда разделов станет
     * больше, между ними встанет список разделов.
     */
    data object Devices : Where
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
    buildVersion: String,
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

    // Книга: люди, с которыми уже есть переписка. Поток из базы — новая переписка
    // появляется в книге сама.
    val book = remember { BookStore(environment.contacts, scope) }
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
                where = Where.Devices
                windowSwitcher = false
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
                onSettings = { where = Where.Devices },
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
                    onOpenPerson = { where = Where.Chat(it.chatId, it.name) },
                    onNew = { where = Where.New },
                    onNewGroup = { where = Where.NewGroup },
                    onSettings = { where = Where.Devices },
                    onSwitchWindows = { windowSwitcher = true },
                    onNeighbourWindow = switchWindow,
                )

                Window.Social -> SocialWindow(
                    onSwitchWindows = { windowSwitcher = true },
                    onSearch = {},
                    onSettings = { where = Where.Devices },
                    onNeighbourWindow = switchWindow,
                )

                Window.Media -> MediaWindow(
                    onSwitchWindows = { windowSwitcher = true },
                    onSearch = {},
                    onSettings = { where = Where.Devices },
                    onNeighbourWindow = switchWindow,
                )

                Window.Activity -> ActivityWindow(
                    onSwitchWindows = { windowSwitcher = true },
                    onSearch = {},
                    onSettings = { where = Where.Devices },
                    onNeighbourWindow = switchWindow,
                )

                Window.Page -> PageWindow(
                    onSwitchWindows = { windowSwitcher = true },
                    onSearch = {},
                    onSettings = { where = Where.Devices },
                    onNeighbourWindow = switchWindow,
                )
            }
        },
        main = when (val current = where) {
            Where.Nothing -> null

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

            Where.Devices -> {
                {
                    Devices(
                        network = network,
                        scope = scope,
                        buildVersion = buildVersion,
                        onBack = { where = Where.Nothing },
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

/** Свои устройства: список, отключение и вопрос перед ним. */
@Composable
private fun Devices(
    network: DevicePorts,
    scope: kotlinx.coroutines.CoroutineScope,
    buildVersion: String,
    onBack: () -> Unit,
) {
    val store = remember { DevicesStore(network.myFleet, scope) }
    val state by store.state.collectAsState()
    DeviceScreen(
        state = state,
        onBack = onBack,
        onAsk = store::ask,
        onConfirm = store::revoke,
        onChangedMind = store::changedMind,
        buildVersion = buildVersion,
    )
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
        onTitle = store::changedTitle,
        onNumber = store::changedNumber,
        onAddNumber = store::addNumber,
        onRemoveNumber = store::removeNumber,
        onCreate = store::create,
        onBack = {
            onBack()
            store.reset()
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
 * Окно «Телефон» — три вкладки макета.
 *
 * «Чаты» и «Книга» построены, «Звонки» ждут К7 и говорят об этом словами: кнопка,
 * которая ничего не делает, обещает больше, чем есть.
 *
 * Вкладка запоминается вызывающим, а не этим экраном: «единая сессия» из `§1` требует,
 * чтобы окно возвращалось туда, где его оставили, — в том числе после захода в подокно.
 */
@Composable
private fun PhoneWindow(
    tab: String,
    onTab: (String) -> Unit,
    list: ChatsState,
    book: BookState,
    onSearchInBook: (String) -> Unit,
    onOpen: (ChatSummary) -> Unit,
    onOpenPerson: (Contact) -> Unit,
    onNew: () -> Unit,
    onNewGroup: () -> Unit,
    onSettings: () -> Unit,
    onSwitchWindows: () -> Unit,
    onNeighbourWindow: (InSide) -> Unit,
) = WindowFrame(
    window = Window.Phone,
    tabs = listOf("Чаты", "Книга", "Звонки"),
    selected = tab,
    onTab = onTab,
    onSwitchWindows = onSwitchWindows,
    onSearch = {},
    onSettings = onSettings,
    onNeighbourWindow = onNeighbourWindow,
) {
    when (tab) {
        "Чаты" -> ChatsScreen(
            state = list,
            onOpen = onOpen,
            onNew = onNew,
            onNewGroup = onNewGroup,
            onSettings = onSettings,
        )

        "Книга" -> BookScreen(
            state = book,
            onSearch = onSearchInBook,
            onOpen = onOpenPerson,
        )

        else -> TabStub(
            willWhat = "Здесь будет журнал звонков",
            thanHolds = "Входящие, исходящие и пропущенные, с фильтрами. " +
                "Звонков нет: клиент LiveKit — задача К7.",
        )
    }
}
