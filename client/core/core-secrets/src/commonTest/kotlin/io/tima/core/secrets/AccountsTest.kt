package io.tima.core.secrets

import io.tima.domain.account.Session
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Список аккаунтов на устройстве — ПЛАН-КОНТАКТОВ.md, Д11.
 *
 * Хранилище здесь — в памяти, **и только в тестах**: в боевом коде такой реализации нет
 * вовсе, это был бы дефект v1 в другой одежде (секрет рядом с базой открытым).
 */
class AccountsTest {

    private class StoreInMemory : SecretVault {
        val values = mutableMapOf<String, ByteArray>()
        override fun put(alias: SecretAlias, secret: ByteArray) { values[alias.value] = secret }
        override fun get(alias: SecretAlias): ByteArray? = values[alias.value]
        override fun remove(alias: SecretAlias): Boolean = values.remove(alias.value) != null
    }

    private val vault = StoreInMemory()
    private val accounts = Accounts(vault)

    private fun session(userId: String) = Session(userId, "d-$userId", "jwt-$userId")

    @Test
    fun секреты_аккаунтов_не_смешиваются() {
        accounts.remember(Account("u-1"), session("u-1"), ByteArray(32) { 1 })
        accounts.remember(Account("u-2", nickname = "petr_smirnov", virtual = true), session("u-2"), ByteArray(32) { 2 })

        // Общий набор имён означал бы, что переключение подменяет ключи под теми же
        // именами: сбой на середине оставил бы секрет одного аккаунта с сессией другого —
        // то есть устройство, отправляющее от чужого имени.
        assertContentEquals(ByteArray(32) { 1 }, accounts.store("u-1").deviceSecret())
        assertContentEquals(ByteArray(32) { 2 }, accounts.store("u-2").deviceSecret())
        assertEquals(session("u-1"), accounts.store("u-1").session())
        assertEquals("u-2", accounts.current(), "последний запомненный обязан стать текущим")
    }

    @Test
    fun повторный_вход_в_тот_же_аккаунт_не_заводит_второй_строки() {
        accounts.remember(Account("u-1"), session("u-1"), ByteArray(32) { 1 })
        accounts.remember(Account("u-1", nickname = "petr_smirnov"), session("u-1"), ByteArray(32) { 9 })

        assertEquals(1, accounts.all().size)
        assertEquals("petr_smirnov", accounts.all().single().nickname)
    }

    @Test
    fun неотправленное_считается_по_аккаунтам_отдельно() {
        accounts.remember(Account("u-1"), session("u-1"), ByteArray(32) { 1 })
        accounts.remember(Account("u-2"), session("u-2"), ByteArray(32) { 2 })

        accounts.notePending("u-1", 3)
        assertEquals(3, accounts.pending("u-1"))
        // Чужое число не появляется само: у аккаунта, который ничего не оставлял, ноль.
        assertEquals(0, accounts.pending("u-2"))
    }

    @Test
    fun пустая_очередь_стирает_метку() {
        accounts.notePending("u-1", 2)
        accounts.notePending("u-1", 0)

        assertEquals(0, accounts.pending("u-1"))
        // Именно стирает, а не пишет ноль: метка «0 не отправлено» — это метка, которой
        // не должно быть видно, и хранить её значит хранить мусор на каждый аккаунт.
        assertTrue(vault.values.keys.none { it.startsWith("accounts.pending") })
    }

    @Test
    fun забытый_аккаунт_уносит_и_секреты_и_число() {
        accounts.remember(Account("u-1"), session("u-1"), ByteArray(32) { 1 })
        accounts.remember(Account("u-2"), session("u-2"), ByteArray(32) { 2 })
        accounts.notePending("u-2", 4)

        accounts.forget("u-2")

        assertEquals(listOf("u-1"), accounts.all().map { it.userId })
        assertNull(accounts.store("u-2").session())
        assertEquals(0, accounts.pending("u-2"))
        // Указатель не остаётся на несуществующем: это неотличимо от «входа не было».
        assertEquals("u-1", accounts.current())
    }
}
