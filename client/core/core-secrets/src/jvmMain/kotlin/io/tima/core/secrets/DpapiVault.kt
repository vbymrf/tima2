package io.tima.core.secrets

import com.sun.jna.platform.win32.Crypt32Util
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView

/**
 * Хранилище ПК: секрет лежит файлом, но **зашифрованным DPAPI** на текущего
 * пользователя Windows.
 *
 * **Что именно закрывает DPAPI, а что нет — стоит сказать прямо.** Он закрывает
 * ровно тот дефект v1, из-за которого шифрование покоя было декоративным: файл,
 * скопированный с диска — вынесенный на флешке, попавший в резервную копию, взятый из
 * образа украденного ноутбука — **не расшифровывается нигде, кроме этой учётной записи
 * на этой машине**. Ключ хранит система, и в наш файл он не попадает.
 *
 * Чего DPAPI не закрывает: код, уже запущенный **под тем же пользователем**, может
 * позвать `CryptUnprotectData` так же, как мы. Этого на ПК не закрывает ничто — и
 * поэтому обещать этого нельзя. Дополнительная энтропия (см. [ENTROPY]) не защищает от
 * такого кода: она лежит в нашем же двоичном файле. Она делает другое — не даёт
 * расшифровать блоб обычным инструментом мимо приложения и не даёт спутать наши блобы
 * с чужими.
 *
 * **Не-Windows JVM отказывает громко** ([UnavailableVault]). Уронить сюда откат
 * «положим открытым, раз DPAPI нет» значило бы вернуть дефект v1 на macOS и Linux —
 * причём молча.
 */
internal class DpapiVault(private val directory: Path) : SecretVault {

    init {
        Files.createDirectories(directory)
        restrictToCurrentUser(directory)
    }

    override fun put(alias: SecretAlias, secret: ByteArray) {
        require(secret.isNotEmpty()) { "секрет пустой" }
        val protected = try {
            Crypt32Util.cryptProtectData(secret, ENTROPY, 0, DESCRIPTION, null)
        } catch (e: Throwable) {
            throw SecretVaultFailure("DPAPI отказал при записи ${alias.value}", e)
        }
        // Пишем через временный файл и переименовываем: прерванная на середине запись
        // не должна оставить обрезанный блоб вместо рабочего секрета — это была бы
        // потеря локальной базы без всякого злоумышленника.
        val target = fileOf(alias)
        val temp = File("${target.path}.new")
        temp.writeBytes(protected)
        restrictToCurrentUser(temp.toPath())
        if (!temp.renameTo(target)) {
            // renameTo на Windows не перезаписывает существующий файл.
            if (!target.delete() || !temp.renameTo(target)) {
                temp.delete()
                throw SecretVaultFailure("не удалось заменить секрет ${alias.value}")
            }
        }
    }

    override fun get(alias: SecretAlias): ByteArray? {
        val file = fileOf(alias)
        if (!file.isFile) return null
        val protected = file.readBytes()
        return try {
            Crypt32Util.cryptUnprotectData(protected, ENTROPY, 0, null)
        } catch (e: Throwable) {
            // Не `null`: «нет секрета» и «секрет есть, но не читается» — разные беды.
            // Первое означает первый запуск и порождает новый ключ; принять за первое
            // второе значило бы молча выбросить локальную базу.
            throw SecretVaultFailure(
                "секрет ${alias.value} есть, но не расшифровывается: другой пользователь, " +
                    "другая машина или испорченный файл",
                e,
            )
        }
    }

    override fun remove(alias: SecretAlias): Boolean = fileOf(alias).delete()

    private fun fileOf(alias: SecretAlias) = File(directory.toFile(), "${alias.value}.dpapi")

    private companion object {

        /**
         * Дополнительная энтропия DPAPI. Привязывает блоб к нашему приложению: чужой
         * `CryptUnprotectData` без этих же байт получит отказ. Версия в строке — чтобы
         * смена схемы была явной, а не тихой поломкой чтения.
         */
        val ENTROPY: ByteArray = "tima/secret-vault/v1".toByteArray()

        /** Видно в диалогах Windows, если система когда-нибудь спросит. */
        const val DESCRIPTION = "TIMA device secret"

        /**
         * Снимает наследованные права и оставляет доступ только текущему пользователю.
         *
         * Сам по себе DPAPI-блоб чужому пользователю бесполезен, но каталог с секретами,
         * читаемый «всеми», — это ещё и сведения о том, чем человек пользуется.
         */
        fun restrictToCurrentUser(path: Path) {
            val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java)
                ?: return // не NTFS — прав ACL нет вовсе, и это не повод отказывать
            runCatching {
                val owner = Files.getOwner(path)
                val entry = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(owner)
                    .setPermissions(AclEntryPermission.entries.toSet())
                    .build()
                view.acl = listOf(entry)
            }
            // Молча: на сетевом диске или в контейнере смена ACL может быть запрещена, и
            // ронять из-за этого запись секрета — хуже, чем оставить права как есть.
        }
    }
}

/**
 * Заглушка отказа для JVM не под Windows.
 *
 * Отказывает **при каждом обращении**, а не при создании: приложение должно суметь
 * запуститься и сказать человеку, в чём дело, а не упасть на старте без объяснения.
 */
internal class UnavailableVault(private val why: String) : SecretVault {
    override fun put(alias: SecretAlias, secret: ByteArray): Unit = throw SecretVaultFailure(why)
    override fun get(alias: SecretAlias): ByteArray? = throw SecretVaultFailure(why)
    override fun remove(alias: SecretAlias): Boolean = throw SecretVaultFailure(why)
}

/**
 * ПК: DPAPI на Windows.
 *
 * Каталог — внутри `%LOCALAPPDATA%`, а **не рядом с файлом базы**: даже зашифрованный
 * секрет не должен уезжать вместе с базой одним копированием.
 */
actual fun platformVault(scope: String): SecretVault {
    val os = System.getProperty("os.name").orEmpty()
    if (!os.startsWith("Windows")) {
        // macOS Keychain и Linux Secret Service — отдельная работа, и до неё лучше
        // отказывать громко, чем положить секрет открытым файлом.
        return UnavailableVault(
            "хранилище секретов для $os ещё не сделано; открытым файлом секрет не пишется",
        )
    }
    val base = System.getenv("LOCALAPPDATA")
        ?: throw SecretVaultFailure("LOCALAPPDATA не задан: непонятно, где держать секреты")
    return DpapiVault(Path.of(base, "TIMA", "secrets", scope))
}
