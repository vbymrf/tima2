package io.tima.core.outbox

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Ключ идемпотентности. Проверяется то, от чего зависит отсутствие дублей у
 * собеседника: ключи не повторяются и не зависят от порядка запусков.
 */
class UuidDedupKeysTest {

    @Test
    fun ключи_не_повторяются() {
        val ключи = List(1_000) { UuidDedupKeys.newKey() }
        assertEquals(1_000, ключи.toSet().size, "повтор ключа означает потерянное сообщение")
    }

    @Test
    fun ключ_годится_для_заголовка_и_для_базы() {
        // Уезжает в X-Client-Msg-Id и лежит уникальным столбцом в messages: значит ни
        // пробелов, ни переводов строки, ни пустоты.
        val ключ = UuidDedupKeys.newKey()
        assertEquals(36, ключ.length, "ожидался UUID: $ключ")
        assertTrue(ключ.all { it.isDigit() || it in 'a'..'f' || it == '-' }, "лишние знаки: $ключ")
    }
}
