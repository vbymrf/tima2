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
    private val area = "проверка"
    private val vault = KeystoreVault(context, area)

    private val name = SecretAlias("device-secret.v1")
    private val secret = ByteArray(32) { (it * 7 + 3).toByte() }

    private val catalog = java.io.File(java.io.File(context.filesDir, "tima-secrets"), area)

    @AfterTest
    fun remove() {
        catalog.deleteRecursively()
    }

    @Test
    fun секрет_кладётся_и_читается_обратно() {
        vault.put(name, secret)
        assertContentEquals(secret, vault.get(name), "прочитано не то, что положили")
    }

    @Test
    fun на_диске_нет_открытых_байт_секрета() {
        vault.put(name, secret)

        val files = catalog.listFiles().orEmpty()
        assertEquals(1, files.size, "ожидался один файл: ${files.map { it.name }}")
        val bytes = files[0].readBytes()

        assertFalse(contains(bytes, secret), "секрет лежит на диске открытым — это дефект v1")
        assertTrue(bytes.size > secret.size, "шифртекст с вектором и тегом обязан быть длиннее")
    }

    @Test
    fun каждая_запись_идёт_со_своим_вектором() {
        // GCM с повторённым вектором на том же ключе раскрывает открытый текст. Ключ
        // создаётся с setRandomizedEncryptionRequired(true) — проверяем, что это так и
        // работает, а не только объявлено.
        vault.put(name, secret)
        val first = catalog.listFiles()!![0].readBytes()
        vault.put(name, secret)
        val second = catalog.listFiles()!![0].readBytes()

        assertFalse(
            first.contentEquals(second),
            "один и тот же секрет дал те же байты: вектор повторился",
        )
        assertContentEquals(secret, vault.get(name))
    }

    @Test
    fun отсутствующий_секрет_это_null_а_испорченный_это_беда() {
        // Разница дорогая: «нет секрета» означает первый запуск и рождение нового ключа.
        // Принять за первый запуск испорченный секрет значило бы молча выбросить всю
        // локальную переписку.
        assertNull(vault.get(name), "на первом запуске секрета нет — и это не ошибка")

        vault.put(name, secret)
        val file = catalog.listFiles()!![0]
        val bytes = file.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1] + 1).toByte()
        file.writeBytes(bytes)

        assertFailsWith<SecretVaultFailure> { vault.get(name) }
    }

    @Test
    fun повторная_запись_заменяет_а_удаление_убирает() {
        vault.put(name, secret)
        val other = ByteArray(32) { 0x5A }

        vault.put(name, other)
        assertContentEquals(other, vault.get(name), "перезапись — обычный путь, а не отказ")

        assertTrue(vault.remove(name))
        assertNull(vault.get(name))
        assertFalse(vault.remove(name), "повторное удаление — не ошибка, но и не «удалил»")
    }

    @Test
    fun чужая_область_своих_секретов_не_видит() {
        vault.put(name, secret)
        val foreign = KeystoreVault(context, "чужая-область")
        try {
            assertNull(foreign.get(name))
        } finally {
            java.io.File(java.io.File(context.filesDir, "tima-secrets"), "чужая-область")
                .deleteRecursively()
        }
    }

    @Test
    fun пустой_секрет_не_принимается() {
        assertFailsWith<IllegalArgumentException> { vault.put(name, ByteArray(0)) }
    }

    @Test
    fun сессия_и_секрет_живут_через_переходник() {
        // Тот же договор, что проверен в общем коде на хранилище в памяти, — но на
        // настоящем Keystore.
        val store = VaultSecretStore(vault)
        val session = Session(
            userId = "0f8fad5b-d9cb-469f-a165-70867728950e",
            deviceId = "7c9e6679-7425-40de-944b-e07fc1f90ae7",
            accessToken = "eyJhbGciOiJFZERTQSJ9.полезная-нагрузка.подпись",
        )

        store.saveDeviceSecret(secret)
        assertFalse(store.hasDevice(), "секрет без сессии — прерванная регистрация")

        store.saveSession(session)
        assertTrue(store.hasDevice())
        assertEquals(session, store.session())

        store.clear()
        assertNull(store.session())
        assertNull(store.deviceSecret())
    }

    private fun contains(where: ByteArray, what: ByteArray): Boolean {
        if (what.isEmpty() || what.size > where.size) return false
        outer@ for (i in 0..where.size - what.size) {
            for (j in what.indices) if (where[i + j] != what[j]) continue@outer
            return true
        }
        return false
    }
}
