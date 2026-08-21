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
    private val секрет = ByteArray(32) { (it * 7 + 3).toByte() }
    private val имя = SecretAlias("device-secret.v1")

    private fun хранилище(): DpapiVault =
        DpapiVault(Files.createTempDirectory("tima-vault-test"))

    @Test
    fun секрет_кладётся_и_читается_обратно() {
        if (!windows) return проверитьГромкийОтказ()
        val vault = хранилище()

        vault.put(имя, секрет)

        assertContentEquals(секрет, vault.get(имя), "прочитано не то, что положили")
    }

    @Test
    fun на_диске_нет_открытых_байт_секрета() {
        // Тот же вопрос, который поймал ложное обещание secure_delete: проверять надо
        // файл, а не свою уверенность. Здесь он главный — ровно этим дефектом v1
        // шифрование покоя и было декоративным.
        if (!windows) return проверитьГромкийОтказ()
        val каталог = Files.createTempDirectory("tima-vault-test")
        DpapiVault(каталог).put(имя, секрет)

        val файлы = каталог.toFile().listFiles().orEmpty()
        assertEquals(1, файлы.size, "ожидался один файл секрета: ${файлы.map { it.name }}")
        val байты = файлы[0].readBytes()

        assertFalse(байты.содержит(секрет), "секрет лежит на диске открытым — это дефект v1")
        assertTrue(байты.size > секрет.size, "блоб DPAPI обязан быть длиннее секрета")
    }

    @Test
    fun без_нашей_энтропии_блоб_не_читается() {
        // Энтропия не защищает от кода, запущенного под тем же пользователем — этого на
        // ПК не закрывает ничто. Она делает другое: не даёт прочитать блоб обычным
        // инструментом мимо приложения. Это и проверяется.
        if (!windows) return проверитьГромкийОтказ()
        val каталог = Files.createTempDirectory("tima-vault-test")
        DpapiVault(каталог).put(имя, секрет)
        val блоб = каталог.toFile().listFiles()!![0].readBytes()

        assertFailsWith<Throwable> {
            Crypt32Util.cryptUnprotectData(блоб, "чужая энтропия".toByteArray(), 0, null)
        }
        // А с нашей — читается: значит отказ выше именно из-за энтропии, а не потому,
        // что блоб вообще не читается.
        assertContentEquals(
            секрет,
            Crypt32Util.cryptUnprotectData(блоб, "tima/secret-vault/v1".toByteArray(), 0, null),
        )
    }

    @Test
    fun отсутствующий_секрет_это_null_а_испорченный_это_беда() {
        // Разница дорогая: «нет секрета» означает первый запуск и рождение нового ключа.
        // Принять за первый запуск испорченный секрет значило бы молча выбросить всю
        // локальную переписку.
        if (!windows) return проверитьГромкийОтказ()
        val каталог = Files.createTempDirectory("tima-vault-test")
        val vault = DpapiVault(каталог)

        assertNull(vault.get(имя), "на первом запуске секрета нет — и это не ошибка")

        vault.put(имя, секрет)
        val файл = каталог.toFile().listFiles()!![0]
        файл.writeBytes(файл.readBytes().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() })

        assertFailsWith<SecretVaultFailure> { vault.get(имя) }
    }

    @Test
    fun повторная_запись_заменяет_а_удаление_убирает() {
        if (!windows) return проверитьГромкийОтказ()
        val vault = хранилище()
        vault.put(имя, секрет)
        val другой = ByteArray(32) { 0x5A }

        vault.put(имя, другой)
        assertContentEquals(другой, vault.get(имя), "перезапись — обычный путь, а не отказ")

        assertTrue(vault.remove(имя))
        assertNull(vault.get(имя))
        assertFalse(vault.remove(имя), "повторное удаление — не ошибка, но и не «удалил»")
    }

    @Test
    fun пустой_секрет_не_принимается() {
        if (!windows) return проверитьГромкийОтказ()
        assertFailsWith<IllegalArgumentException> { хранилище().put(имя, ByteArray(0)) }
    }

    /**
     * На JVM не под Windows хранилища пока нет, и его отсутствие обязано быть громким:
     * откат «положим открытым файлом» вернул бы дефект v1 на macOS и Linux, причём
     * молча.
     */
    private fun проверитьГромкийОтказ() {
        val vault = platformVault("test")
        val беда = assertFailsWith<SecretVaultFailure> { vault.get(имя) }
        assertTrue(
            беда.message.orEmpty().contains("ещё не сделано"),
            "ожидался внятный отказ, получено: ${беда.message}",
        )
        assertFailsWith<SecretVaultFailure> { vault.put(имя, секрет) }
    }

    private fun ByteArray.содержит(образец: ByteArray): Boolean {
        if (образец.isEmpty() || образец.size > size) return false
        outer@ for (i in 0..size - образец.size) {
            for (j in образец.indices) if (this[i + j] != образец[j]) continue@outer
            return true
        }
        return false
    }
}
