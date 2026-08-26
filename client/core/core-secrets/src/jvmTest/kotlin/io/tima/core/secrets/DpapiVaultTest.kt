package io.tima.core.secrets

import com.sun.jna.platform.win32.Crypt32Util
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Хранилище ПК. Проверка **не разветвлена пропуском**: на Windows проверяется, что
 * DPAPI работает и что открытых байт на диске не остаётся, на остальных JVM — что
 * хранилище отказывает громко. Пропущенный тест выглядел бы зелёным на обеих
 * платформах, ничего не проверив ни на одной — а именно так и был потерян дефект v1.
 */
class DpapiVaultTest {

    private val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")
    private val secret = ByteArray(32) { (it * 7 + 3).toByte() }
    private val name = SecretAlias("device-secret.v1")

    private fun store(): DpapiVault =
        DpapiVault(Files.createTempDirectory("tima-vault-test"))

    @Test
    fun секрет_кладётся_и_читается_обратно() {
        if (!windows) return checkLoudRefusal()
        val vault = store()

        vault.put(name, secret)

        assertContentEquals(secret, vault.get(name), "прочитано не то, что положили")
    }

    @Test
    fun на_диске_нет_открытых_байт_секрета() {
        // Тот же вопрос, который поймал ложное обещание secure_delete: проверять надо
        // файл, а не свою уверенность. Здесь он главный — ровно этим дефектом v1
        // шифрование покоя и было декоративным.
        if (!windows) return checkLoudRefusal()
        val catalog = Files.createTempDirectory("tima-vault-test")
        DpapiVault(catalog).put(name, secret)

        val files = catalog.toFile().listFiles().orEmpty()
        assertEquals(1, files.size, "ожидался один файл секрета: ${files.map { it.name }}")
        val bytes = files[0].readBytes()

        assertFalse(bytes.contains(secret), "секрет лежит на диске открытым — это дефект v1")
        assertTrue(bytes.size > secret.size, "блоб DPAPI обязан быть длиннее секрета")
    }

    @Test
    fun без_нашей_энтропии_блоб_не_читается() {
        // Энтропия не защищает от кода, запущенного под тем же пользователем — этого на
        // ПК не закрывает ничто. Она делает другое: не даёт прочитать блоб обычным
        // инструментом мимо приложения. Это и проверяется.
        if (!windows) return checkLoudRefusal()
        val catalog = Files.createTempDirectory("tima-vault-test")
        DpapiVault(catalog).put(name, secret)
        val blob = catalog.toFile().listFiles()!![0].readBytes()

        assertFailsWith<Throwable> {
            Crypt32Util.cryptUnprotectData(blob, "чужая энтропия".toByteArray(), 0, null)
        }
        // А с нашей — читается: значит отказ выше именно из-за энтропии, а не потому,
        // что блоб вообще не читается.
        assertContentEquals(
            secret,
            Crypt32Util.cryptUnprotectData(blob, "tima/secret-vault/v1".toByteArray(), 0, null),
        )
    }

    @Test
    fun отсутствующий_секрет_это_null_а_испорченный_это_беда() {
        // Разница дорогая: «нет секрета» означает первый запуск и рождение нового ключа.
        // Принять за первый запуск испорченный секрет значило бы молча выбросить всю
        // локальную переписку.
        if (!windows) return checkLoudRefusal()
        val catalog = Files.createTempDirectory("tima-vault-test")
        val vault = DpapiVault(catalog)

        assertNull(vault.get(name), "на первом запуске секрета нет — и это не ошибка")

        vault.put(name, secret)
        val file = catalog.toFile().listFiles()!![0]
        file.writeBytes(file.readBytes().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() })

        assertFailsWith<SecretVaultFailure> { vault.get(name) }
    }

    @Test
    fun повторная_запись_заменяет_а_удаление_убирает() {
        if (!windows) return checkLoudRefusal()
        val vault = store()
        vault.put(name, secret)
        val other = ByteArray(32) { 0x5A }

        vault.put(name, other)
        assertContentEquals(other, vault.get(name), "перезапись — обычный путь, а не отказ")

        assertTrue(vault.remove(name))
        assertNull(vault.get(name))
        assertFalse(vault.remove(name), "повторное удаление — не ошибка, но и не «удалил»")
    }

    @Test
    fun пустой_секрет_не_принимается() {
        if (!windows) return checkLoudRefusal()
        assertFailsWith<IllegalArgumentException> { store().put(name, ByteArray(0)) }
    }

    /**
     * На JVM не под Windows хранилища пока нет, и его отсутствие обязано быть громким:
     * откат «положим открытым файлом» вернул бы дефект v1 на macOS и Linux, причём
     * молча.
     */
    private fun checkLoudRefusal() {
        val vault = platformVault("test")
        val trouble = assertFailsWith<SecretVaultFailure> { vault.get(name) }
        assertTrue(
            trouble.message.orEmpty().contains("ещё не сделано"),
            "ожидался внятный отказ, получено: ${trouble.message}",
        )
        assertFailsWith<SecretVaultFailure> { vault.put(name, secret) }
    }

    private fun ByteArray.contains(sample: ByteArray): Boolean {
        if (sample.isEmpty() || sample.size > size) return false
        outer@ for (i in 0..size - sample.size) {
            for (j in sample.indices) if (this[i + j] != sample[j]) continue@outer
            return true
        }
        return false
    }
}
