package io.tima.app

import io.tima.app.platform.SecretVault
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Защита секретов на десктопе. До этой правки `protect` возвращал вход без
 * изменений, и seed устройства лежал в `~/.tima/session.json` открытым.
 *
 * Тест намеренно не привязан к DPAPI или запасному пути: оба обязаны давать
 * одно и то же наблюдаемое поведение, а какой из них включится — решает ОС,
 * на которой идёт прогон.
 */
class SecretVaultTest {

    private val secret = ByteArray(32) { (it * 7 + 1).toByte() }

    @Test
    fun `развёрнутый секрет совпадает с исходным`() {
        val protected = SecretVault.protect(secret)
        assertContentEquals(secret, SecretVault.reveal(protected))
    }

    @Test
    fun `защищённый блоб не содержит исходный секрет открытым`() {
        val protected = SecretVault.protect(secret)
        assertFalse(
            protected.asList().windowed(secret.size).any { it == secret.asList() },
            "секрет виден в защищённом блобе — защита не сработала",
        )
    }

    @Test
    fun `наследие без метки читается как есть - обновление не выкидывает из аккаунта`() {
        // Ровно то, что лежало на диске у версии без шифрования: сырой секрет.
        assertContentEquals(secret, SecretVault.reveal(secret))
    }

    @Test
    fun `повторная защита даёт разные блобы - нет детерминированного шифротекста`() {
        val a = SecretVault.protect(secret)
        val b = SecretVault.protect(secret)
        assertFalse(a.contentEquals(b), "два вызова protect дали одинаковый блоб")
        assertContentEquals(secret, SecretVault.reveal(a))
        assertContentEquals(secret, SecretVault.reveal(b))
    }

    @Test
    fun `секреты разной длины переживают круг`() {
        for (size in listOf(1, 16, 32, 64, 200)) {
            val value = ByteArray(size) { (it % 251).toByte() }
            assertContentEquals(value, SecretVault.reveal(SecretVault.protect(value)), "длина $size")
        }
    }

    @Test
    fun `защищённый блоб длиннее исходного - метка и криптографические накладные на месте`() {
        assertTrue(SecretVault.protect(secret).size > secret.size)
    }
}
