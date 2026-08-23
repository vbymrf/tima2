package io.tima.app

import io.ktor.client.HttpClient
import io.tima.core.database.SqlChatFeed
import io.tima.core.database.SqlChatsFeed
import io.tima.core.database.SqlOutboxStore
import io.tima.core.database.TimaDatabase
import io.tima.core.database.desktopDatabase
import io.tima.core.encryption.DeviceKeyFactoryOverKodium
import io.tima.core.encryption.LocalStoreFieldCipher
import io.tima.core.encryption.TextBodyCodec
import io.tima.core.network.AccountApiOverHttp
import io.tima.core.network.AuthApi
import io.tima.core.network.EscrowApi
import io.tima.core.network.HttpMessageTransport
import io.tima.core.network.KeysApi
import io.tima.core.network.UsersApi
import io.tima.core.network.RouteConfig
import io.tima.core.network.ServerRoute
import io.tima.core.network.httpEngine
import io.tima.core.network.timaDefaults
import io.tima.core.outbox.FieldCipher
import io.tima.core.outbox.Outbox
import io.tima.core.outbox.UuidDedupKeys
import io.tima.core.secrets.SecretVault
import io.tima.core.secrets.VaultSecretStore
import io.tima.core.secrets.platformVault
import io.tima.domain.account.RegisterDevice
import io.tima.domain.account.Session
import io.tima.domain.chat.ObserveChat
import io.tima.domain.chat.ObserveChats
import io.tima.domain.chat.SendMessage
import java.io.File

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
         * @param host адрес сервера.
         *
         * Берётся из окружения, а по умолчанию — стенд: другого сервера пока не
         * существует. Боевой способ — подписанный конфиг маршрутов (К3.3), и он написан;
         * ключ выпуска ещё не выдан, поэтому обновление маршрутов **отвергается целиком**,
         * и подставить адрес снаружи нельзя. Здесь честный временный путь: переменная
         * окружения, названная вслух.
         */
        fun создать(
            host: String = System.getenv("TIMA_STAND_HOST")?.takeIf { it.isNotBlank() }
                ?: СТЕНД,
            хранилищеСекретов: SecretVault = platformVault(scope = "desktop"),
        ): Вход {
            val route = ServerRoute.from(RouteConfig(host = host))
            val client = HttpClient(httpEngine()) { timaDefaults() }
            val секреты = VaultSecretStore(хранилищеСекретов)
            return Вход(
                регистрация = RegisterDevice(
                    api = AccountApiOverHttp(AuthApi(route, client)),
                    keys = DeviceKeyFactoryOverKodium,
                    secrets = секреты,
                    platform = "desktop",
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
    route: ServerRoute,
    client: HttpClient,
    сессия: Session,
) {
    val ключи: KeysApi = KeysApi(route, client, token = { сессия.accessToken })
    val escrow: EscrowApi = EscrowApi(route, client, token = { сессия.accessToken })
    val транспорт: HttpMessageTransport = HttpMessageTransport(route, client, token = { сессия.accessToken })

    /** Справочник: кто скрывается за номером телефона. Нужен, чтобы начать переписку. */
    val справочник: UsersApi = UsersApi(route, client, token = { сессия.accessToken })

    companion object {
        /** Тот же адрес и тот же клиент, что у входа: сервер один. */
        fun создать(
            сессия: Session,
            host: String = System.getenv("TIMA_STAND_HOST")?.takeIf { it.isNotBlank() } ?: Вход.СТЕНД,
        ): Сеть = Сеть(
            route = ServerRoute.from(RouteConfig(host = host)),
            client = HttpClient(httpEngine()) { timaDefaults() },
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
) {

    /** Очередь исходящих: одна на приложение, потому что одна на базу. */
    val очередь: Outbox = Outbox(SqlOutboxStore(db, шифр), nowMs = { System.currentTimeMillis() })

    val переписки: ObserveChats = ObserveChats(SqlChatsFeed(db, TextBodyCodec, шифр))

    val переписка: ObserveChat = ObserveChat(SqlChatFeed(db, TextBodyCodec, шифр))

    val отправка: SendMessage = SendMessage(
        queue = очередь,
        codec = TextBodyCodec,
        keys = UuidDedupKeys,
    )

    companion object {

        /**
         * Открыть базу устройства.
         *
         * @param секретУстройства те самые 32 байта, из которых выведены ключи устройства.
         *   Ключ покоя базы выводится из них же, поэтому **чужой секрет означает
         *   нечитаемую базу**, а не ошибку: строки на месте, содержимое не открыть.
         *   Порождает секрет только регистрация — см. [Вход].
         */
        fun открыть(секретУстройства: ByteArray, каталог: File = каталогДанных()): Окружение {
            val db = desktopDatabase(File(каталог, ИМЯ_БАЗЫ))
            return Окружение(db, LocalStoreFieldCipher(секретУстройства))
        }

        /** `%LOCALAPPDATA%\TIMA` — рядом с секретами, но не вместе с ними. */
        fun каталогДанных(): File {
            val base = System.getenv("LOCALAPPDATA")
                ?: System.getProperty("user.home")
                ?: error("непонятно, где держать данные: ни LOCALAPPDATA, ни user.home")
            return File(base, "TIMA")
        }

        private const val ИМЯ_БАЗЫ = "tima.db"
    }
}
