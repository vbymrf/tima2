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
 * Keychain в симуляторе.
 *
 * **Прогон здесь упёрся в измеренное ограничение, и это записано, а не обойдено.**
 * Тесты Kotlin/Native — отдельный исполняемый файл, а не приложение: права
 * `application-identifier` у него нет, и Keychain отвечает `errSecMissingEntitlement`
 * (-34018). Измерено следующее: **удаление отказывает точно** — на первом прогоне
 * упала даже проверка пустого секрета, а она к Keychain обращается только через
 * уборку. Про чтение утверждать нечего: оно могло и вернуть «записи нет».
 *
 * Для вывода этого достаточно: пользоваться хранилищем можно только если можно писать.
 * Настоящая проверка Keychain требует прогона **внутри приложения** (`app-ios`, К5) —
 * ровно тот же случай, что у Android, где AndroidKeyStore проверяется только на
 * устройстве.
 *
 * Поэтому проверка разветвлена, а не пропущена: где Keychain доступен — проверяется
 * круг, где недоступен — проверяется, что отказ **внятный и отдельный**. Пропущенный
 * тест выглядел бы зелёным и не проверял бы ни одного из двух.
 */
class KeychainVaultTest {

    private val служба = "io.tima.secrets.test-${Random.nextLong()}"
    private val vault = KeychainVault(служба)
    private val имя = SecretAlias("device-secret.v1")
    private val секрет = ByteArray(32) { (it * 5 + 1).toByte() }

    /**
     * Доступен ли Keychain этому процессу. Выясняется **записью**, а не удалением.
     *
     * Разница выяснилась прогоном: без права `application-identifier` **чтение**
     * ведёт себя как «записи нет» и отказа не даёт, а запись и удаление отказывают.
     * Проба удалением поэтому давала неверный ответ на вопрос «можно ли этим
     * хранилищем пользоваться»: пользоваться им можно, только если можно ПИСАТЬ.
     */
    private val доступен: Boolean = try {
        // Имя латиницей: собственная проверка имён кириллицу запрещает — на разных
        // файловых системах она ведёт себя по-разному. Я на это и наступил.
        val проба = SecretAlias("probe.v1")
        KeychainVault(служба).put(проба, byteArrayOf(1))
        KeychainVault(служба).remove(проба)
        true
    } catch (e: KeychainUnavailable) {
        false
    }

    @AfterTest
    fun убрать() {
        // runCatching: где Keychain недоступен, уборка тоже отказывает, и падать на ней
        // значило бы объявлять красным то, что тест уже проверил как ожидаемый отказ.
        runCatching { vault.remove(имя) }
    }

    /**
     * Ветка «Keychain недоступен»: отказ обязан быть отдельным и объясняющим себя.
     *
     * Свалить его в общий [SecretVaultFailure] значило бы отправить искать причину в
     * данных, тогда как лечится она окружением.
     */
    private fun проверитьВнятныйОтказ() {
        val беда = assertFailsWith<KeychainUnavailable> { vault.put(имя, секрет) }
        assertTrue(
            беда.message.orEmpty().contains("34018"),
            "отказ обязан называть OSStatus: ${беда.message}",
        )
        assertTrue(
            беда.message.orEmpty().contains("application-identifier"),
            "и объяснять, чего процессу не хватает: ${беда.message}",
        )
        // Про чтение здесь не утверждается ничего, и это не небрежность: без права
        // чтение отвечает «записи нет», то есть отказа не даёт. Проверять то, что
        // зависит от окружения тоньше, чем сам факт недоступности, значило бы гадать.
    }

    @Test
    fun секрет_кладётся_и_читается_обратно() {
        if (!доступен) return проверитьВнятныйОтказ()
        vault.put(имя, секрет)
        assertContentEquals(секрет, vault.get(имя), "прочитано не то, что положили")
    }

    @Test
    fun отсутствующий_секрет_это_null_а_не_беда() {
        // Первый запуск устройства выглядит именно так, и это обычный путь.
        if (!доступен) return проверитьВнятныйОтказ()
        assertNull(vault.get(SecretAlias("never-written.v1")))
    }

    @Test
    fun повторная_запись_заменяет() {
        // SecItemAdd сам по себе не перезаписывает: без удаления перед записью смена
        // секрета устройства молча не срабатывала бы.
        if (!доступен) return проверитьВнятныйОтказ()
        vault.put(имя, секрет)
        val другой = ByteArray(32) { 0x5A }
        vault.put(имя, другой)
        assertContentEquals(другой, vault.get(имя))
    }

    @Test
    fun удаление_убирает_и_повторное_не_ошибка() {
        if (!доступен) return проверитьВнятныйОтказ()
        vault.put(имя, секрет)
        assertTrue(vault.remove(имя))
        assertNull(vault.get(имя))
        assertFalse(vault.remove(имя), "повторное удаление — не ошибка, но и не «удалил»")
    }

    @Test
    fun чужая_служба_своих_секретов_не_видит() {
        // Разделение по службе — не украшение: иначе тесты тёрли бы секрет приложения.
        if (!доступен) return проверитьВнятныйОтказ()
        vault.put(имя, секрет)
        assertNull(KeychainVault("$служба.чужая").get(имя))
    }

    @Test
    fun пустой_секрет_не_принимается() {
        // Проверка размера идёт до всякого обращения к хранилищу, поэтому здесь ветка не
        // нужна: это единственное, что верно в любом окружении.
        assertFailsWith<IllegalArgumentException> { vault.put(имя, ByteArray(0)) }
    }
}
