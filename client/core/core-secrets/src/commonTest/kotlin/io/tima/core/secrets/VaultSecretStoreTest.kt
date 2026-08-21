package io.tima.core.secrets

import io.tima.domain.account.Session
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Переходник к порту `domain-account`.
 *
 * Хранилище здесь — в памяти, **и только в тестах**: в боевом коде такой реализации
 * нет вовсе, это был бы дефект v1 в другой одежде (секрет рядом с базой открытым).
 */
class VaultSecretStoreTest {

    private class ХранилищеВПамяти : SecretVault {
        val значения = mutableMapOf<String, ByteArray>()
        override fun put(alias: SecretAlias, secret: ByteArray) { значения[alias.value] = secret }
        override fun get(alias: SecretAlias): ByteArray? = значения[alias.value]
        override fun remove(alias: SecretAlias): Boolean = значения.remove(alias.value) != null
    }

    private val vault = ХранилищеВПамяти()
    private val store = VaultSecretStore(vault)
    private val секрет = ByteArray(32) { it.toByte() }
    private val сессия = Session(
        userId = "0f8fad5b-d9cb-469f-a165-70867728950e",
        deviceId = "7c9e6679-7425-40de-944b-e07fc1f90ae7",
        accessToken = "eyJhbGciOiJFZERTQSJ9.eyJzdWIiOiJ1LTEifQ.cGjK-подпись_дальше",
    )

    @Test
    fun сессия_и_секрет_кладутся_и_читаются() {
        store.saveDeviceSecret(секрет)
        store.saveSession(сессия)

        assertEquals(сессия, store.session())
        assertContentEquals(секрет, store.deviceSecret())
        assertTrue(store.hasDevice())
    }

    @Test
    fun на_первом_запуске_ничего_нет_и_это_не_ошибка() {
        assertNull(store.session())
        assertNull(store.deviceSecret())
        assertFalse(store.hasDevice())
    }

    @Test
    fun устройство_считается_заведённым_по_сессии_а_не_по_секрету() {
        // Порядок записи в RegisterDevice именно такой: секрет раньше вызова, сессия
        // после успеха. Значит «есть секрет, нет сессии» — это прерванная регистрация, и
        // устройством она не считается.
        store.saveDeviceSecret(секрет)
        assertFalse(store.hasDevice(), "секрет без сессии — прерванная регистрация")
    }

    @Test
    fun испорченная_запись_сессии_это_беда_а_не_чистый_лист() {
        // Молча начать с нуля значило бы завести второе устройство при живом первом, а
        // первое осталось бы на сервере без ключа.
        vault.значения["session.v1"] = "только-одно-поле".encodeToByteArray()
        assertFailsWith<SecretVaultFailure> { store.session() }

        vault.значения["session.v1"] = "u\n\nтокен".encodeToByteArray()
        assertFailsWith<SecretVaultFailure> { store.session() }
    }

    @Test
    fun секрет_не_того_размера_не_принимается() {
        assertFailsWith<IllegalArgumentException> { store.saveDeviceSecret(ByteArray(31)) }
        assertFailsWith<IllegalArgumentException> { store.saveDeviceSecret(ByteArray(0)) }
    }

    @Test
    fun перевод_строки_внутри_значения_отвергается() {
        // Разбор держится на том, что перевода строки в значениях не бывает: UUID и
        // base64url его содержать не могут. Проверка на записи, а не надежда.
        assertFailsWith<IllegalArgumentException> {
            store.saveSession(сессия.copy(accessToken = "часть\nвторая"))
        }
    }

    @Test
    fun выход_из_аккаунта_убирает_и_сессию_и_секрет() {
        store.saveDeviceSecret(секрет)
        store.saveSession(сессия)

        store.clear()

        assertNull(store.session())
        assertNull(store.deviceSecret())
        assertTrue(vault.значения.isEmpty(), "в хранилище не должно остаться ничего нашего")
    }
}
