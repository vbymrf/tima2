package io.tima.core.secrets

import io.tima.domain.account.PinCheck
import io.tima.domain.account.PinRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Пин-код (ПЛАН-(ПН)): хеш вместо пина, ошибки, паузы, только фраза, переживает перезапуск. */
class PinLockTest {

    private class Memory : SecretVault {
        val map = HashMap<String, ByteArray>()
        override fun put(alias: SecretAlias, secret: ByteArray) { map[alias.value] = secret }
        override fun get(alias: SecretAlias): ByteArray? = map[alias.value]
        override fun remove(alias: SecretAlias): Boolean = map.remove(alias.value) != null
    }

    private val vault = Memory()
    private var clock = 1_000_000L

    // Хеш в тесте — простой и детерминированный: проверяется логика, а не PBKDF2.
    private fun lock() = PinLock(vault, { pin, salt -> salt + pin.reversedArray() }, { byteArrayOf(7, 7) }, { clock })

    @Test
    fun пин_не_хранится_в_открытую() {
        lock().set("1234")
        assertFalse(vault.map.values.single().decodeToString().contains("1234"), "пин лежит в хранилище как есть")
        assertTrue(lock().isOn())
    }

    @Test
    fun верный_пин_подходит_неверный_считается() {
        val l = lock()
        l.set("1234")
        assertEquals(PinCheck.Ok, l.check("1234"))
        assertEquals(PinCheck.Wrong(4), l.check("0000"))
        assertEquals(PinCheck.Wrong(3), l.check("0000"))
        assertEquals(PinCheck.Ok, l.check("1234"))
        assertEquals(PinCheck.Wrong(4), l.check("0000"), "верный ввод обнуляет счёт")
    }

    @Test
    fun после_пяти_ошибок_пауза_и_она_растёт() {
        val l = lock()
        l.set("1234")
        repeat(4) { l.check("0000") }
        val first = assertIs<PinCheck.Paused>(l.check("0000"))
        assertEquals(clock + PinRules.FIRST_PAUSE_MS, first.untilMs)
        assertIs<PinCheck.Paused>(l.check("1234"), "во время паузы не проверяется даже верный")
        clock = first.untilMs
        val second = assertIs<PinCheck.Paused>(l.check("0000"))
        assertEquals(clock + 2 * PinRules.FIRST_PAUSE_MS, second.untilMs)
    }

    @Test
    fun после_десяти_только_фраза_и_это_переживает_перезапуск() {
        val l = lock()
        l.set("1234")
        repeat(PinRules.PHRASE_AFTER) {
            clock += 10 * 60 * 60_000L
            l.check("0000")
        }
        assertEquals(PinCheck.PhraseOnly, lock().state(), "новый экземпляр — как после перезапуска")
        assertEquals(PinCheck.PhraseOnly, lock().check("1234"))
        lock().phraseAccepted()
        assertEquals(PinCheck.Ok, lock().check("1234"))
    }

    @Test
    fun убранный_пин_не_спрашивается() {
        val l = lock()
        l.set("1234")
        l.remove()
        assertFalse(l.isOn())
        assertEquals(PinCheck.Ok, l.state())
    }

    @Test
    fun забытый_аккаунт_уносит_пин() {
        val accounts = Accounts(vault)
        accounts.pin("u-1", { p, s -> s + p }, { byteArrayOf(1) }, { clock }).set("1234")
        accounts.forget("u-1")
        assertFalse(accounts.pin("u-1", { p, s -> s + p }, { byteArrayOf(1) }, { clock }).isOn())
    }
}
