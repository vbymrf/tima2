package io.tima.shared

import io.tima.core.database.SqlChatJournal
import io.tima.core.database.SqlChatFeed
import io.tima.core.database.SqlChatFacts
import io.tima.core.database.SqlBook
import io.tima.core.database.SqlContacts
import io.tima.core.database.SqlSettings
import io.tima.domain.chat.ChatFacts
import io.tima.domain.chat.Book
import io.tima.domain.chat.ContactDiscovery
import io.tima.domain.chat.Friends
import io.tima.domain.chat.ObserveBook
import io.tima.domain.chat.ObserveContacts
import io.tima.domain.chat.Settings
import io.tima.core.database.SqlChatsFeed
import io.tima.core.database.SqlInboxStore
import io.tima.core.database.SqlGroupKeys
import io.tima.core.database.SqlOutboxStore
import io.tima.core.database.SqlReadMarks
import io.tima.core.database.TimaDatabase
import io.tima.core.encryption.DeviceIdentity
import io.tima.core.encryption.DeviceKeyFactoryOverKodium
import io.tima.core.encryption.LinkSignerOverKodium
import io.tima.core.encryption.LocalStoreFieldCipher
import io.tima.core.encryption.TextBodyCodec
import io.tima.core.network.AccountApiOverHttp
import io.tima.core.network.AuthApi
import io.tima.core.network.DeviceLinkConfirmOverHttp
import io.tima.core.network.DeviceLinkStartOverHttp
import io.tima.core.network.AppVersionApi
import io.tima.core.network.DeviceBookOverHttp
import io.tima.core.network.DevicesApi
import io.tima.core.network.EscrowApi
import io.tima.core.network.EventStream
import io.tima.core.network.GroupKeyRecoveryApi
import io.tima.core.network.GroupKeysApi
import io.tima.core.network.GroupMessagesApi
import io.tima.core.network.LevelAccessOverHttp
import io.tima.core.network.MessageLevelsOverHttp
import io.tima.core.network.UserPagesOverHttp
import io.tima.core.network.GroupsApi
import io.tima.core.network.HttpMessageTransport
import io.tima.core.network.KeysApi
import io.tima.core.network.ContactsOverHttp
import io.tima.core.network.FriendsOverHttp
import io.tima.core.network.UsersApi
import io.tima.core.network.LinkConfirmApi
import io.tima.core.network.LinkStartApi
import io.tima.core.network.RouteConfig
import io.tima.core.network.ServerRoute
import io.tima.core.network.ServerLink
import io.tima.core.network.httpEngine
import io.tima.core.network.timaDefaults
import io.tima.core.outbox.FieldCipher
import io.tima.core.outbox.Inbox
import io.tima.core.outbox.Outbox
import io.tima.core.outbox.UuidDedupKeys
import io.tima.core.secrets.SecretVault
import io.tima.core.secrets.VaultSecretStore
import io.tima.core.secrets.platformVault
import io.tima.domain.account.ConfirmDeviceLink
import io.tima.domain.account.LinkNewDevice
import io.tima.domain.account.MyDevices
import io.tima.domain.account.RegisterDevice
import io.tima.domain.account.Session
import io.tima.domain.chat.ChatJournal
import io.tima.domain.chat.MarkRead
import io.tima.domain.chat.ObserveChat
import io.tima.domain.chat.ObserveChats
import io.tima.domain.chat.SendMessage

/**
 * Вход: то, что работает **до** появления устройства.
 *
 * Отдельно от [Окружение], и это не аккуратность, а найденная поломка. Сначала окружение
 * само порождало секрет устройства, если его не было. Потом регистрация порождала свой —
 * через [DeviceKeyFactoryOverKodium], потому что серверу нужны ключи, выведенные ровно из
 * него. Второй секрет затирал первый, и база, записанная под первым ключом покоя,
 * **становилась нечитаемой**: строки на месте, содержимое не открыть.
 *
 * Поэтому секрет устройства порождается **в одном месте** — в регистрации, — а локальная
 * база открывается только когда он есть. Пока устройства нет, открывать нечего: своей
 * переписки у неоформленного устройства не бывает.
 */
class Entry private constructor(
    val registration: RegisterDevice,
    /**
     * Привязка к аккаунту, который уже есть на другом устройстве.
     *
     * Живёт здесь, а не в [Сеть], потому что нужна **до** входа: у нового устройства ещё
     * нет ни аккаунта, ни токена, и весь смысл привязки — получить их без SMS.
     */
    val link: LinkNewDevice,
    /** Адрес сервера: он же понадобится сети после входа. */
    val host: String,
    /** На чём мы работаем. Приложение объявляет это серверу при каждом запуске. */
    val platform: Platform,
    private val secrets: VaultSecretStore,
) {

    /**
     * Заведённое устройство: секрет **и** сессия.
     *
     * Различие не формальное. Секрет пишется ДО вызова сервера (иначе умри процесс между
     * «сервер завёл устройство» и «мы сохранили ключ» — и устройство осталось бы на
     * сервере навсегда без ключа). Значит секрет без сессии означает незаконченный вход, и
     * пускать по нему в приложение нельзя: сервер про такое устройство не знает.
     *
     * @return `null` — надо входить.
     */
    fun created(): Device? {
        val session = secrets.session() ?: return null
        val secret = secrets.deviceSecret() ?: return null
        return Device(secret, session)
    }

    /** Всё, что нужно приложению после входа: ключ покоя базы и кто мы для сервера. */
    class Device(val secret: ByteArray, val session: Session)

    companion object {

        /**
         * @param host адрес сервера. По умолчанию стенд: другого сервера пока не
         *   существует.
         *
         * Боевой способ — подписанный конфиг маршрутов (К3.3), и он написан; ключ выпуска
         * ещё не выдан, поэтому обновление маршрутов **отвергается целиком**. Подменять
         * адрес снаружи умеет только платформа: на ПК это переменная окружения, и читает её
         * приложение для ПК. `System.getenv` в общем коде нет — его нет на iOS, и попытка
         * прочитать окружение здесь была первой же ошибкой при выносе сборки в общий модуль.
         */
        /**
         * @param платформа объявляется **вызывающим**, и умолчания у неё нет. Умолчание
         *   здесь уже было — `"desktop"`, — и телефон объявлял себя ПК: см. [Платформа].
         *
         * `scope` хранилища остаётся строкой `"desktop"` на всех платформах намеренно.
         * Это имя раздела внутри хранилища самой платформы (у Android — своё, у iOS —
         * своё), то есть значение опаковое; переименовать его сейчас значило бы оставить
         * секреты каждой уже существующей установки в разделе, куда никто больше не
         * заглянет — то есть потребовать перерегистрации без всякой пользы.
         */
        fun create(
            platform: Platform,
            host: String = STAND,
            secretStore: SecretVault = platformVault(scope = "desktop"),
        ): Entry {
            val link = ServerLink.open(host)
            val route = link.route
            val client = link.client
            val secrets = VaultSecretStore(secretStore)
            val deviceSecrets = secrets
            return Entry(
                host = host,
                platform = platform,
                link = LinkNewDevice(
                    api = DeviceLinkStartOverHttp(LinkStartApi(route, client)),
                    keys = DeviceKeyFactoryOverKodium,
                    secrets = deviceSecrets,
                ),
                registration = RegisterDevice(
                    api = AccountApiOverHttp(AuthApi(route, client)),
                    keys = DeviceKeyFactoryOverKodium,
                    secrets = secrets,
                    platform = platform.server,
                ),
                secrets = secrets,
            )
        }

        /** Стенд: единственный существующий сервер. Punycode — тот же, что в харнессе. */
        const val STAND: String = "xn--80aa4ar0b.xn--p1ai"
    }
}


/**
 * Сеть заведённого устройства: то, что требует токена.
 *
 * Отдельно от [Вход], потому что здесь нужна сессия, а у входа её ещё нет. Токен берётся
 * **на каждый вызов**, а не один раз: он живёт меньше приложения.
 */
class Network(
    private val link: ServerLink,
    private val session: Session,
) : ChatPorts, GroupPorts, DevicePorts {
    override val keys: KeysApi = KeysApi(link.route, link.client, token = { session.accessToken })
    override val escrow: EscrowApi = EscrowApi(link.route, link.client, token = { session.accessToken })
    val transport: HttpMessageTransport = HttpMessageTransport(link.route, link.client, token = { session.accessToken })

    /** Справочник: кто скрывается за номером телефона. Нужен, чтобы начать переписку. */
    override val directory: UsersApi = UsersApi(link.route, link.client, token = { session.accessToken })

    /** Сверка книги: `POST /users/discover`, куда уходит номер, а хранится слепой индекс. */
    override val discovery: ContactDiscovery =
        ContactsOverHttp(link.route, link.client, token = { session.accessToken })

    /** Друзья: свой список, правит только владелец. */
    override val friends: Friends =
        FriendsOverHttp(link.route, link.client, token = { session.accessToken })

    /** Устройства аккаунта: объявить платформу, показать список, отключить. */
    override val devices: DevicesApi = DevicesApi(link.route, link.client, token = { session.accessToken })

    /** Группы: создание, состав, роли. */
    override val groups: GroupsApi = GroupsApi(link.route, link.client, token = { session.accessToken })

    /** Групповые ключи: ротация и выдача обёрток этому устройству. */
    override val groupKeys: GroupKeysApi = GroupKeysApi(link.route, link.client, token = { session.accessToken })

    /**
     * Отправка сообщений группы.
     *
     * Отдельный порт от личного транспорта: у группового сообщения нет ни конверта, ни
     * обёрток на устройства — есть версия ключа либо открытый текст.
     */
    val groupMessages: GroupMessagesApi =
        GroupMessagesApi(link.route, link.client, token = { session.accessToken })

    /**
     * Сужение круга у уже отправленного сообщения (ADR-0019 §6).
     *
     * Отдельной ручкой, а не полем отправки: сужают не тем же действием, которым пишут, и
     * права на это разные — своё сообщение сужает автор, чужое админ.
     */
    /**
     * Страница человека: своя лента, чужая лента и перенос к себе.
     *
     * Кодек здесь тот же, что у сообщений: у принесённой записи сервер отдаёт байты
     * оригинала, и разбирать их вторым способом значило бы завести второе представление
     * текста.
     */
    override val pages: UserPagesOverHttp =
        UserPagesOverHttp(link.route, link.client, token = { session.accessToken }, codec = TextBodyCodec)

    override val access: LevelAccessOverHttp =
        LevelAccessOverHttp(link.route, link.client, token = { session.accessToken })

    override val messageLevels: MessageLevelsOverHttp =
        MessageLevelsOverHttp(link.route, link.client, token = { session.accessToken })

    /**
     * Недостающие версии ключа: попросить и отдать.
     *
     * Отдельно от [ключиГрупп], потому что это другая работа: там выпуск новой версии,
     * здесь передача уже существующей тому, кому её не выдавали.
     */
    override val keyRecovery: GroupKeyRecoveryApi =
        GroupKeyRecoveryApi(link.route, link.client, token = { session.accessToken })

    /**
     * То же самое под именем порта групп.
     *
     * Два имени у одной ручки — не дублирование: порт переписок называет её «чем
     * чинится нечитаемое сообщение», порт групп — «чем раздаются старые версии».
     * Это разные вопросы к одному и тому же маршруту.
     */
    override val groupKeyRecovery: GroupKeyRecoveryApi get() = keyRecovery

    /**
     * Свои устройства как случай использования.
     *
     * Появилось вместе с привязкой: подключить устройство стало делом одного скана, значит
     * отключать человек должен уметь сам.
     */
    override val myFleet: MyDevices = MyDevices(DeviceBookOverHttp(devices))

    /** Версия на сервере. Без токена: её спрашивают и до входа. */
    override val appVersion: AppVersionApi = AppVersionApi(link.route, link.client)

    /**
     * Подтверждение привязки нового устройства.
     *
     * Требует ключа этого устройства: подпись над данными из кода делается им. Ключ даёт
     * приложение, потому что он живёт в хранилище платформы, а не в сети.
     */
    override fun linkConfirmation(identity: DeviceIdentity): ConfirmDeviceLink = ConfirmDeviceLink(
        api = DeviceLinkConfirmOverHttp(LinkConfirmApi(link.route, link.client, token = { session.accessToken })),
        signer = LinkSignerOverKodium(identity),
    )

    /**
     * Живой канал событий.
     *
     * Собирается здесь, а не у приёмника: адрес и клиент стали приватными, и это
     * было целью — Ktor не поднимается выше core-network. Приёмник получает готовый
     * поток и про транспорт не знает.
     */
    fun eventChannel(): EventStream = EventStream(link.route, link.client, token = { session.accessToken })

    companion object {
        /** Тот же адрес и тот же клиент, что у входа: сервер один. */
        fun create(
            session: Session,
            host: String = Entry.STAND,
        ): Network = Network(
            // Соединение собирает core-network: Ktor выше него не поднимается. Живой
            // канал ставится на тот же клиент — второй означал бы второй набор
            // настроек, и они разошлись бы.
            link = ServerLink.open(host, liveChannel = true),
            session = session,
        )
    }
}

/**
 * Сборка приложения для ПК — **единственное место, где всё соединяется**.
 *
 * Ни один модуль ниже не знает, кто его собирает: очередь получает хранилище портом,
 * хранилище — шифр, экран — случаи использования. Здесь это перестаёт быть абстракцией и
 * становится путями к файлам.
 *
 * **Отправки в сеть здесь пока нет**: насос очереди подключается вместе с транспортом,
 * следующим срезом. Сообщение ложится в очередь и честно висит с отметкой «ждёт» — это
 * правда о его состоянии, а не недоделка.
 */
class Environment private constructor(
    val db: TimaDatabase,
    val cipher: FieldCipher,
    /** Кто я: по этому отличается своё сообщение со второго устройства от чужого. */
    private val myUserId: String,
) {

    /** Очередь исходящих: одна на приложение, потому что одна на базу. */
    val queue: Outbox = Outbox(SqlOutboxStore(db, cipher), nowMs = { msNow() })

    /**
     * Машина входящих. Одна на приложение по той же причине: одна база.
     *
     * Конверт записывается ДО попытки разбора — иначе падение расшифровки теряет
     * сообщение навсегда, живой канал его больше не пришлёт.
     */
    val incoming: Inbox = Inbox(SqlInboxStore(db, cipher), nowMs = { msNow() })

    /**
     * Факты о переписке: вид, собеседник, знаем ли о ней вообще.
     *
     * Порт вместо прямого запроса: схема базы перестала быть публичным API — до этого
     * миграция столбца была правкой экранов и приёмника.
     */
    /**
     * Книга групповых ключей.
     *
     * Здесь же, а не только внутри [GroupKeyOrchestrator]: ключи нужны и отправке
     * ([GroupSender]) — она обязана взять свежую версию перед шифрованием.
     */
    val groupKeyBook: SqlGroupKeys = SqlGroupKeys(db, cipher)

    val chatFacts: ChatFacts = SqlChatFacts(db)

    val chats: ObserveChats = ObserveChats(SqlChatsFeed(db, TextBodyCodec, cipher, myUserId))

    /**
     * Прежняя книга: люди, с которыми уже есть переписка.
     *
     * Оставлена до Д9: на ней держится выбор собеседника в «Новой переписке». Вкладка
     * «Контакты» с неё уже ушла — там своя книга ([book]), которая читается с телефона
     * и живёт своей жизнью.
     */
    val contacts: ObserveContacts = ObserveContacts(SqlContacts(db, cipher))

    /** Своя книга контактов: телефонная книга плюс заведённое руками (Д2). */
    val bookStorage: Book = SqlBook(db, cipher)

    val book: ObserveBook = ObserveBook(bookStorage)

    /** Настройки экранов: вид списка и что показывать (Д5). */
    val settings: Settings = SqlSettings(db)


    val chat: ObserveChat = ObserveChat(SqlChatFeed(db, TextBodyCodec, cipher, myUserId))

    /**
     * Местные записи в переписке: смена круга у сообщения и служебная строка о ней.
     *
     * Пишет их приёмник по событию сервера. Наружу они не уходят: подписи у такой строки
     * нет, и уйти ей некуда — каждый участник получает своё событие сам.
     */
    val journal: ChatJournal = SqlChatJournal(db, TextBodyCodec, cipher)

    /** Открытая переписка прочитана: счётчик непрочитанного обязан гаснуть. */
    val reading: MarkRead = MarkRead(SqlReadMarks(incoming))

    val send: SendMessage = SendMessage(
        queue = queue,
        codec = TextBodyCodec,
        keys = UuidDedupKeys,
    )

    companion object {

        /**
         * Открыть окружение над **уже открытой базой**.
         *
         * База приходит снаружи, и это единственное, что осталось платформенным: на ПК её
         * открывает `desktopDatabase(файл)`, на Android — `androidDatabase(context, имя)`.
         * Правила поведения от этого не зависят вовсе, поэтому и живут здесь.
         *
         * @param секретУстройства те самые 32 байта, из которых выведены ключи устройства.
         *   Ключ покоя базы выводится из них же, поэтому **чужой секрет означает
         *   нечитаемую базу**, а не ошибку: строки на месте, содержимое не открыть.
         *   Порождает секрет только регистрация — см. [Вход].
         * @param myUserId кто я. Нужен переписке: входящее от себя же — своё сообщение,
         *   написанное с другого своего устройства.
         */
        fun open(db: TimaDatabase, deviceSecret: ByteArray, myUserId: String): Environment =
            Environment(db, LocalStoreFieldCipher(deviceSecret), myUserId)
    }
}
