package io.tima.app

import io.tima.core.database.SqlChatFeed
import io.tima.core.database.SqlChatsFeed
import io.tima.core.database.SqlOutboxStore
import io.tima.core.database.TimaDatabase
import io.tima.core.database.desktopDatabase
import io.tima.core.encryption.DeviceKeyFactoryOverKodium
import io.tima.core.encryption.LocalStoreFieldCipher
import io.tima.core.encryption.TextBodyCodec
import io.tima.core.outbox.FieldCipher
import io.tima.core.outbox.Outbox
import io.tima.core.outbox.UuidDedupKeys
import io.tima.core.secrets.SecretVault
import io.tima.core.secrets.VaultSecretStore
import io.tima.core.secrets.platformVault
import io.tima.domain.chat.ObserveChat
import io.tima.domain.chat.ObserveChats
import io.tima.domain.chat.SendMessage
import java.io.File

/**
 * Сборка приложения для ПК — **единственное место, где всё соединяется**.
 *
 * Ни один модуль ниже не знает, кто его собирает: очередь получает хранилище портом,
 * хранилище — шифр, экран — случаи использования. Здесь это перестаёт быть абстракцией и
 * становится путями к файлам.
 *
 * **Сети здесь нет, и это честная граница текущего этапа.** Регистрация устройства и
 * вход — К5.1; пока их нет, сообщение ложится в очередь и там остаётся. Ровно это и
 * проверяется первым запуском: переписка на диске, письмо в очереди, и оба переживают
 * закрытие приложения.
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
         * Открыть окружение: секрет из хранилища платформы, база с диска.
         *
         * **Секрет — первое, а не последнее.** Из него выводится ключ покоя базы, и без
         * него база бесполезна: открыть её будет нечем. В v1 на ПК секрет лежал открытым
         * файлом рядом с базой, и шифрование покоя было декоративным; здесь он в DPAPI, а
         * на системах, где хранилища ещё нет, `platformVault` **отказывает громко** — это
         * лучше, чем положить секрет открытым файлом.
         */
        fun открыть(
            каталог: File = каталогДанных(),
            /**
             * Хранилище секретов. Подменяется только проверками — и не для того, чтобы
             * обойти DPAPI, а чтобы не писать в хранилище живой машины: сам DPAPI
             * проверен там, где ему место (`DpapiVaultTest`, по файлу).
             */
            хранилищеСекретов: SecretVault = platformVault(scope = "desktop"),
        ): Окружение {
            val хранилище = VaultSecretStore(хранилищеСекретов)
            val секрет = хранилище.deviceSecret() ?: завестиСекрет(хранилище)
            val db = desktopDatabase(File(каталог, ИМЯ_БАЗЫ))
            return Окружение(db, LocalStoreFieldCipher(секрет))
        }

        /**
         * Первый запуск: секрет устройства порождается один раз и живёт в хранилище
         * платформы.
         *
         * Порождать его на каждом запуске значило бы каждый раз заводить новое устройство
         * и терять всю местную переписку — ключ покоя выводится ровно из него.
         */
        private fun завестиСекрет(хранилище: VaultSecretStore): ByteArray {
            val материал = DeviceKeyFactoryOverKodium.newDeviceKeys()
            хранилище.saveDeviceSecret(материал.secret)
            return материал.secret
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
