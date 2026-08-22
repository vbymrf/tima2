package io.tima.core.secrets

import androidx.test.platform.app.InstrumentationRegistry
import io.tima.domain.account.Session
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Хранилище Android — **на устройстве**, потому что AndroidKeyStore нельзя изобразить.
 *
 * Проверяется то же, что у DPAPI на Windows, и главное — тем же способом: **по файлу**.
 * Открытых байт секрета на диске быть не должно. Это ровно та проверка, которая днём
 * раньше поймала ложное обещание `secure_delete`: код выглядел правильным, а байты
 * лежали.
 */
class KeystoreVaultTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val область = "проверка"
    private val vault = KeystoreVault(context, область)

    private val имя = SecretAlias("device-secret.v1")
    private val секрет = ByteArray(32) { (it * 7 + 3).toByte() }

    private val каталог = java.io.File(java.io.File(context.filesDir, "tima-secrets"), область)

    @AfterTest
    fun убрать() {
        каталог.deleteRecursively()
    }

    @Test
    fun секрет_кладётся_и_читается_обратно() {
        vault.put(имя, секрет)
        assertContentEquals(секрет, vault.get(имя), "прочитано не то, что положили")
    }

    @Test
    fun на_диске_нет_открытых_байт_секрета() {
        vault.put(имя, секрет)

        val файлы = каталог.listFiles().orEmpty()
        assertEquals(1, файлы.size, "ожидался один файл: ${файлы.map { it.name }}")
        val байты = файлы[0].readBytes()

        assertFalse(содержит(байты, секрет), "секрет лежит на диске открытым — это дефект v1")
        assertTrue(байты.size > секрет.size, "шифртекст с вектором и тегом обязан быть длиннее")
    }

    @Test
    fun каждая_запись_идёт_со_своим_вектором() {
        // GCM с повторённым вектором на том же ключе раскрывает открытый текст. Ключ
        // создаётся с setRandomizedEncryptionRequired(true) — проверяем, что это так и
        // работает, а не только объявлено.
        vault.put(имя, секрет)
        val первый = каталог.listFiles()!![0].readBytes()
        vault.put(имя, секрет)
        val второй = каталог.listFiles()!![0].readBytes()

        assertFalse(
            первый.contentEquals(второй),
            "один и тот же секрет дал те же байты: вектор повторился",
        )
        assertContentEquals(секрет, vault.get(имя))
    }

    @Test
    fun отсутствующий_секрет_это_null_а_испорченный_это_беда() {
        // Разница дорогая: «нет секрета» означает первый запуск и рождение нового ключа.
        // Принять за первый запуск испорченный секрет значило бы молча выбросить всю
        // локальную переписку.
        assertNull(vault.get(имя), "на первом запуске секрета нет — и это не ошибка")

        vault.put(имя, секрет)
        val файл = каталог.listFiles()!![0]
        val байты = файл.readBytes()
        байты[байты.size - 1] = (байты[байты.size - 1] + 1).toByte()
        файл.writeBytes(байты)

        assertFailsWith<SecretVaultFailure> { vault.get(имя) }
    }

    @Test
    fun повторная_запись_заменяет_а_удаление_убирает() {
        vault.put(имя, секрет)
        val другой = ByteArray(32) { 0x5A }

        vault.put(имя, другой)
        assertContentEquals(другой, vault.get(имя), "перезапись — обычный путь, а не отказ")

        assertTrue(vault.remove(имя))
        assertNull(vault.get(имя))
        assertFalse(vault.remove(имя), "повторное удаление — не ошибка, но и не «удалил»")
    }

    @Test
    fun чужая_область_своих_секретов_не_видит() {
        vault.put(имя, секрет)
        val чужая = KeystoreVault(context, "чужая-область")
        try {
            assertNull(чужая.get(имя))
        } finally {
            java.io.File(java.io.File(context.filesDir, "tima-secrets"), "чужая-область")
                .deleteRecursively()
        }
    }

    @Test
    fun пустой_секрет_не_принимается() {
        assertFailsWith<IllegalArgumentException> { vault.put(имя, ByteArray(0)) }
    }

    @Test
    fun сессия_и_секрет_живут_через_переходник() {
        // Тот же договор, что проверен в общем коде на хранилище в памяти, — но на
        // настоящем Keystore.
        val store = VaultSecretStore(vault)
        val сессия = Session(
            userId = "0f8fad5b-d9cb-469f-a165-70867728950e",
            deviceId = "7c9e6679-7425-40de-944b-e07fc1f90ae7",
            accessToken = "eyJhbGciOiJFZERTQSJ9.полезная-нагрузка.подпись",
        )

        store.saveDeviceSecret(секрет)
        assertFalse(store.hasDevice(), "секрет без сессии — прерванная регистрация")

        store.saveSession(сессия)
        assertTrue(store.hasDevice())
        assertEquals(сессия, store.session())

        store.clear()
        assertNull(store.session())
        assertNull(store.deviceSecret())
    }

    private fun содержит(где: ByteArray, что: ByteArray): Boolean {
        if (что.isEmpty() || что.size > где.size) return false
        outer@ for (i in 0..где.size - что.size) {
            for (j in что.indices) if (где[i + j] != что[j]) continue@outer
            return true
        }
        return false
    }
}
