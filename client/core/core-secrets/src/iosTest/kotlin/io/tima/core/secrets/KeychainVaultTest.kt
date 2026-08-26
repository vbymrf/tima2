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
 * Тесты Kotlin/Native — отдельный исполняемый файл, а не приложение, и своей связки
 * ключей у него не появляется: симулятор отвечает `errSecNotAvailable` (**-25291**).
 * Это измерено отчётом прогона, а не выведено из общих соображений — первое моё
 * объяснение (нет права `application-identifier`, -34018) оказалось неверным.
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

    private val service = "io.tima.secrets.test-${Random.nextLong()}"
    private val vault = KeychainVault(service)
    private val name = SecretAlias("device-secret.v1")
    private val secret = ByteArray(32) { (it * 5 + 1).toByte() }

    /**
     * Доступен ли Keychain этому процессу. Выясняется **записью**, а не удалением.
     *
     * Разница выяснилась прогоном: без права `application-identifier` **чтение**
     * ведёт себя как «записи нет» и отказа не даёт, а запись и удаление отказывают.
     * Проба удалением поэтому давала неверный ответ на вопрос «можно ли этим
     * хранилищем пользоваться»: пользоваться им можно, только если можно ПИСАТЬ.
     */
    private val available: Boolean = try {
        // Имя латиницей: собственная проверка имён кириллицу запрещает — на разных
        // файловых системах она ведёт себя по-разному. Я на это и наступил.
        val probe = SecretAlias("probe.v1")
        KeychainVault(service).put(probe, byteArrayOf(1))
        KeychainVault(service).remove(probe)
        true
    } catch (e: KeychainUnavailable) {
        false
    }

    @AfterTest
    fun remove() {
        // runCatching: где Keychain недоступен, уборка тоже отказывает, и падать на ней
        // значило бы объявлять красным то, что тест уже проверил как ожидаемый отказ.
        runCatching { vault.remove(name) }
    }

    /**
     * Ветка «Keychain недоступен»: отказ обязан быть отдельным и объясняющим себя.
     *
     * Свалить его в общий [SecretVaultFailure] значило бы отправить искать причину в
     * данных, тогда как лечится она окружением.
     */
    private fun checkClearRefusal() {
        val trouble = assertFailsWith<KeychainUnavailable> { vault.put(name, secret) }
        val text = trouble.message.orEmpty()
        assertTrue(text.contains("OSStatus"), "отказ обязан называть код: $text")
        assertTrue(
            text.contains("-25291") || text.contains("-34018"),
            "код обязан быть одним из измеренных кодов недоступности: $text",
        )
        assertTrue(text.contains("недоступен"), "и говорить, что дело в окружении: $text")
        // Про чтение здесь не утверждается ничего, и это не небрежность: без права
        // чтение отвечает «записи нет», то есть отказа не даёт. Проверять то, что
        // зависит от окружения тоньше, чем сам факт недоступности, значило бы гадать.
    }

    @Test
    fun секрет_кладётся_и_читается_обратно() {
        if (!available) return checkClearRefusal()
        vault.put(name, secret)
        assertContentEquals(secret, vault.get(name), "прочитано не то, что положили")
    }

    @Test
    fun отсутствующий_секрет_это_null_а_не_беда() {
        // Первый запуск устройства выглядит именно так, и это обычный путь.
        if (!available) return checkClearRefusal()
        assertNull(vault.get(SecretAlias("never-written.v1")))
    }

    @Test
    fun повторная_запись_заменяет() {
        // SecItemAdd сам по себе не перезаписывает: без удаления перед записью смена
        // секрета устройства молча не срабатывала бы.
        if (!available) return checkClearRefusal()
        vault.put(name, secret)
        val other = ByteArray(32) { 0x5A }
        vault.put(name, other)
        assertContentEquals(other, vault.get(name))
    }

    @Test
    fun удаление_убирает_и_повторное_не_ошибка() {
        if (!available) return checkClearRefusal()
        vault.put(name, secret)
        assertTrue(vault.remove(name))
        assertNull(vault.get(name))
        assertFalse(vault.remove(name), "повторное удаление — не ошибка, но и не «удалил»")
    }

    @Test
    fun чужая_служба_своих_секретов_не_видит() {
        // Разделение по службе — не украшение: иначе тесты тёрли бы секрет приложения.
        if (!available) return checkClearRefusal()
        vault.put(name, secret)
        assertNull(KeychainVault("$service.чужая").get(name))
    }

    @Test
    fun пустой_секрет_не_принимается() {
        // Проверка размера идёт до всякого обращения к хранилищу, поэтому здесь ветка не
        // нужна: это единственное, что верно в любом окружении.
        assertFailsWith<IllegalArgumentException> { vault.put(name, ByteArray(0)) }
    }
}
