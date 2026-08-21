package io.tima.core.secrets

import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Keychain. Прогон идёт в симуляторе (гейт К2 на macOS) — на Windows этот код только
 * компилируется, и без такого теста Keychain остался бы непроверенным нигде.
 *
 * Служба у каждого прогона своя: Keychain симулятора живёт дольше теста, и общая
 * служба означала бы, что прогоны видят чужие секреты.
 */
class KeychainVaultTest {

    private val служба = "io.tima.secrets.test-${Random.nextLong()}"
    private val vault = KeychainVault(служба)
    private val имя = SecretAlias("device-secret.v1")
    private val секрет = ByteArray(32) { (it * 5 + 1).toByte() }

    @AfterTest
    fun убрать() {
        vault.remove(имя)
        vault.remove(SecretAlias("другой.v1"))
    }

    @Test
    fun секрет_кладётся_и_читается_обратно() {
        vault.put(имя, секрет)
        assertContentEquals(секрет, vault.get(имя), "прочитано не то, что положили")
    }

    @Test
    fun отсутствующий_секрет_это_null_а_не_беда() {
        // Первый запуск устройства выглядит именно так, и это обычный путь.
        assertNull(vault.get(SecretAlias("никогда-не-писали.v1")))
    }

    @Test
    fun повторная_запись_заменяет() {
        // SecItemAdd сам по себе не перезаписывает: без удаления перед записью смена
        // секрета устройства молча не срабатывала бы.
        vault.put(имя, секрет)
        val другой = ByteArray(32) { 0x5A }
        vault.put(имя, другой)
        assertContentEquals(другой, vault.get(имя))
    }

    @Test
    fun удаление_убирает_и_повторное_не_ошибка() {
        vault.put(имя, секрет)
        assertTrue(vault.remove(имя))
        assertNull(vault.get(имя))
        assertFalse(vault.remove(имя), "повторное удаление — не ошибка, но и не «удалил»")
    }

    @Test
    fun чужая_служба_своих_секретов_не_видит() {
        // Разделение по службе — не украшение: иначе тесты тёрли бы секрет приложения.
        vault.put(имя, секрет)
        assertNull(KeychainVault("$служба.чужая").get(имя))
    }

    @Test
    fun пустой_секрет_не_принимается() {
        assertFailsWith<IllegalArgumentException> { vault.put(имя, ByteArray(0)) }
    }
}
