package io.tima.shared

import io.tima.core.database.SqlChatFeed
import io.tima.core.database.SqlChatsFeed
import io.tima.core.database.SqlInboxStore
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
import io.tima.core.network.DeviceBookOverHttp
import io.tima.core.network.DevicesApi
import io.tima.core.network.EscrowApi
import io.tima.core.network.EventStream
import io.tima.core.network.GroupKeyRecoveryApi
import io.tima.core.network.GroupKeysApi
import io.tima.core.network.GroupsApi
import io.tima.core.network.HttpMessageTransport
import io.tima.core.network.KeysApi
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
class Вход private constructor(
    val регистрация: RegisterDevice,
    /**
     * Привязка к аккаунту, который уже есть на другом устройстве.
     *
     * Живёт здесь, а не в [Сеть], потому что нужна **до** входа: у нового устройства ещё
     * нет ни аккаунта, ни токена, и весь смысл привязки — получить их без SMS.
     */
    val привязка: LinkNewDevice,
    /** Адрес сервера: он же понадобится сети после входа. */
    val host: String,
    /** На чём мы работаем. Приложение объявляет это серверу при каждом запуске. */
    val платформа: Платформа,
    private val секреты: VaultSecretStore,
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
    fun заведённое(): Устройство? {
        val сессия = секреты.session() ?: return null
        val секрет = секреты.deviceSecret() ?: return null
        return Устройство(секрет, сессия)
    }

    /** Всё, что нужно приложению после входа: ключ покоя базы и кто мы для сервера. */
    class Устройство(val секрет: ByteArray, val сессия: Session)

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
        fun создать(
            платформа: Платформа,
            host: String = СТЕНД,
            хранилищеСекретов: SecretVault = platformVault(scope = "desktop"),
        ): Вход {
            val связь = ServerLink.открыть(host)
            val route = связь.route
            val client = связь.client
            val секреты = VaultSecretStore(хранилищеСекретов)
            val секретыУстройства = секреты
            return Вход(
                host = host,
                платформа = платформа,
                привязка = LinkNewDevice(
                    api = DeviceLinkStartOverHttp(LinkStartApi(route, client)),
                    keys = DeviceKeyFactoryOverKodium,
                    secrets = секретыУстройства,
                ),
                регистрация = RegisterDevice(
                    api = AccountApiOverHttp(AuthApi(route, client)),
                    keys = DeviceKeyFactoryOverKodium,
                    secrets = секреты,
                    platform = платформа.серверу,
                ),
                секреты = секреты,
            )
        }

        /** Стенд: единственный существующий сервер. Punycode — тот же, что в харнессе. */
        const val СТЕНД: String = "xn--80aa4ar0b.xn--p1ai"
    }
}


/**
 * Сеть заведённого устройства: то, что требует токена.
 *
 * Отдельно от [Вход], потому что здесь нужна сессия, а у входа её ещё нет. Токен берётся
 * **на каждый вызов**, а не один раз: он живёт меньше приложения.
 */
class Сеть(
    private val связь: ServerLink,
    private val сессия: Session,
) : ПортыПереписок, ПортыГрупп, ПортыУстройств {
    override val ключи: KeysApi = KeysApi(связь.route, связь.client, token = { сессия.accessToken })
    override val escrow: EscrowApi = EscrowApi(связь.route, связь.client, token = { сессия.accessToken })
    val транспорт: HttpMessageTransport = HttpMessageTransport(связь.route, связь.client, token = { сессия.accessToken })

    /** Справочник: кто скрывается за номером телефона. Нужен, чтобы начать переписку. */
    override val справочник: UsersApi = UsersApi(связь.route, связь.client, token = { сессия.accessToken })

    /** Устройства аккаунта: объявить платформу, показать список, отключить. */
    override val устройства: DevicesApi = DevicesApi(связь.route, связь.client, token = { сессия.accessToken })

    /** Группы: создание, состав, роли. */
    override val группы: GroupsApi = GroupsApi(связь.route, связь.client, token = { сессия.accessToken })

    /** Групповые ключи: ротация и выдача обёрток этому устройству. */
    override val ключиГрупп: GroupKeysApi = GroupKeysApi(связь.route, связь.client, token = { сессия.accessToken })

    /**
     * Недостающие версии ключа: попросить и отдать.
     *
     * Отдельно от [ключиГрупп], потому что это другая работа: там выпуск новой версии,
     * здесь передача уже существующей тому, кому её не выдавали.
     */
    override val восстановлениеКлючей: GroupKeyRecoveryApi =
        GroupKeyRecoveryApi(связь.route, связь.client, token = { сессия.accessToken })

    /**
     * Свои устройства как случай использования.
     *
     * Появилось вместе с привязкой: подключить устройство стало делом одного скана, значит
     * отключать человек должен уметь сам.
     */
    override val мойПарк: MyDevices = MyDevices(DeviceBookOverHttp(устройства))

    /**
     * Подтверждение привязки нового устройства.
     *
     * Требует ключа этого устройства: подпись над данными из кода делается им. Ключ даёт
     * приложение, потому что он живёт в хранилище платформы, а не в сети.
     */
    override fun подтверждениеПривязки(личность: DeviceIdentity): ConfirmDeviceLink = ConfirmDeviceLink(
        api = DeviceLinkConfirmOverHttp(LinkConfirmApi(связь.route, связь.client, token = { сессия.accessToken })),
        signer = LinkSignerOverKodium(личность),
    )

    /**
     * Живой канал событий.
     *
     * Собирается здесь, а не у приёмника: адрес и клиент стали приватными, и это
     * было целью — Ktor не поднимается выше core-network. Приёмник получает готовый
     * поток и про транспорт не знает.
     */
    fun каналСобытий(): EventStream = EventStream(связь.route, связь.client, token = { сессия.accessToken })

    companion object {
        /** Тот же адрес и тот же клиент, что у входа: сервер один. */
        fun создать(
            сессия: Session,
            host: String = Вход.СТЕНД,
        ): Сеть = Сеть(
            // Соединение собирает core-network: Ktor выше него не поднимается. Живой
            // канал ставится на тот же клиент — второй означал бы второй набор
            // настроек, и они разошлись бы.
            связь = ServerLink.открыть(host, живойКанал = true),
            сессия = сессия,
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
class Окружение private constructor(
    val db: TimaDatabase,
    val шифр: FieldCipher,
    /** Кто я: по этому отличается своё сообщение со второго устройства от чужого. */
    private val myUserId: String,
) {

    /** Очередь исходящих: одна на приложение, потому что одна на базу. */
    val очередь: Outbox = Outbox(SqlOutboxStore(db, шифр), nowMs = { сейчасМс() })

    /**
     * Машина входящих. Одна на приложение по той же причине: одна база.
     *
     * Конверт записывается ДО попытки разбора — иначе падение расшифровки теряет
     * сообщение навсегда, живой канал его больше не пришлёт.
     */
    val входящие: Inbox = Inbox(SqlInboxStore(db, шифр), nowMs = { сейчасМс() })

    val переписки: ObserveChats = ObserveChats(SqlChatsFeed(db, TextBodyCodec, шифр, myUserId))

    val переписка: ObserveChat = ObserveChat(SqlChatFeed(db, TextBodyCodec, шифр, myUserId))

    /** Открытая переписка прочитана: счётчик непрочитанного обязан гаснуть. */
    val прочтение: MarkRead = MarkRead(SqlReadMarks(входящие))

    val отправка: SendMessage = SendMessage(
        queue = очередь,
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
        fun открыть(db: TimaDatabase, секретУстройства: ByteArray, myUserId: String): Окружение =
            Окружение(db, LocalStoreFieldCipher(секретУстройства), myUserId)
    }
}
