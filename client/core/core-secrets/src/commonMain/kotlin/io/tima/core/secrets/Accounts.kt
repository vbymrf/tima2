package io.tima.core.secrets

import io.tima.domain.account.Session

/**
 * Несколько аккаунтов на одном устройстве — ПЛАН-КОНТАКТОВ.md, Д11.
 *
 * **Аккаунт здесь — не «профиль» и не «второе имя».** Это отдельный пользователь со
 * своей фразой, своими ключами и своей перепиской: основной и его виртуальные равны во
 * всём, кроме телефона.
 *
 * ── ПОЧЕМУ СЕКРЕТЫ РАЗДЕЛЬНЫЕ ───────────────────────────────────────────────
 *
 * Сессия и секрет устройства лежат **под своим именем на каждый аккаунт**. Один общий
 * набор означал бы, что переключение подменяет ключи под теми же именами, и первый же
 * сбой на середине оставил бы секрет одного аккаунта с сессией другого — то есть
 * устройство, отправляющее от чужого имени.
 *
 * ── ПОЧЕМУ СПИСОК ЛЕЖИТ ЗДЕСЬ, А НЕ В БАЗЕ ──────────────────────────────────
 *
 * База у каждого аккаунта своя и зашифрована своим ключом покоя. Список аккаунтов не
 * может лежать в базе одного из них: чтобы его прочитать, надо уже знать, в какой
 * заходить. Курица без яйца.
 */
class Accounts(private val vault: SecretVault) {

    /**
     * Известные аккаунты в порядке появления.
     *
     * Пустой список означает «ни одного входа не было», а не поломку: так выглядит
     * первый запуск.
     */
    fun all(): List<Account> {
        val raw = vault.get(LIST)?.decodeToString() ?: return emptyList()
        return raw.lineSequence().filter { it.isNotBlank() }.mapNotNull(::parse).toList()
    }

    /** Кто сейчас. `null` — входа не было либо запись испорчена. */
    fun current(): String? = vault.get(CURRENT)?.decodeToString()?.ifBlank { null }

    /**
     * Запомнить аккаунт и сделать его текущим.
     *
     * Повторный вызов обновляет запись, а не заводит вторую: аккаунт узнаётся по
     * `userId`, и два входа в один аккаунт — обычный путь (перерегистрация, смена
     * токена).
     */
    fun remember(account: Account, session: Session, deviceSecret: ByteArray) {
        val было = all().filterNot { it.userId == account.userId }
        write(было + account)
        store(account.userId).saveSession(session)
        store(account.userId).saveDeviceSecret(deviceSecret)
        switchTo(account.userId)
    }

    /** Переключиться. Ничего не проверяет: проверка — дело того, кто собирает окружение. */
    fun switchTo(userId: String) {
        vault.put(CURRENT, userId.encodeToByteArray())
    }

    /** Хранилище секретов конкретного аккаунта. */
    fun store(userId: String): VaultSecretStore = VaultSecretStore(Scoped(vault, userId))

    /**
     * Забыть аккаунт целиком: и запись в списке, и его секреты.
     *
     * Текущим становится первый из оставшихся — иначе приложение осталось бы с
     * указателем на несуществующий аккаунт, а это неотличимо от «входа не было».
     */
    fun forget(userId: String) {
        store(userId).clear()
        val осталось = all().filterNot { it.userId == userId }
        write(осталось)
        if (current() == userId) {
            осталось.firstOrNull()?.let { switchTo(it.userId) } ?: vault.remove(CURRENT)
        }
    }

    private fun write(list: List<Account>) {
        vault.put(LIST, list.joinToString("\n") { it.write() }.encodeToByteArray())
    }

    private fun parse(line: String): Account? {
        val parts = line.split(FIELD)
        if (parts.size != 3 || parts[0].isBlank()) return null
        return Account(userId = parts[0], nickname = parts[1], virtual = parts[2] == "1")
    }

    private fun Account.write(): String =
        listOf(userId, nickname, if (virtual) "1" else "0").joinToString(FIELD)

    private companion object {
        val LIST = SecretAlias("accounts.v1")
        val CURRENT = SecretAlias("accounts.current.v1")
        const val FIELD = "\t"
    }
}

/**
 * Строка списка аккаунтов.
 *
 * Имени человека здесь нет намеренно: оно живёт на сервере и меняется, а список нужен и
 * без сети. Ник же выбирается один раз и служит опознанием — у виртуального аккаунта он
 * вообще единственное, чем его называют.
 */
data class Account(
    val userId: String,
    val nickname: String = "",
    /** Виртуальный — тот, у кого нет телефона. */
    val virtual: Boolean = false,
)

/**
 * Хранилище платформы с именами, разведёнными по аккаунту.
 *
 * Приставка, а не отдельное хранилище: у Keychain и Keystore раздел один на приложение,
 * и заводить второй ради приставки значило бы менять способ хранения ради имени.
 */
private class Scoped(private val inner: SecretVault, private val userId: String) : SecretVault {
    override fun put(alias: SecretAlias, secret: ByteArray) = inner.put(scoped(alias), secret)
    override fun get(alias: SecretAlias): ByteArray? = inner.get(scoped(alias))
    override fun remove(alias: SecretAlias): Boolean = inner.remove(scoped(alias))

    // Точка, а не «собака»: имя секрета допускает только a-z, цифры, точку, дефис и
    // подчёркивание. UUID аккаунта в них укладывается целиком.
    private fun scoped(alias: SecretAlias) = SecretAlias("${alias.value}.$userId")
}
