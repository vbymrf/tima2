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
        val keys = List(1_000) { UuidDedupKeys.newKey() }
        assertEquals(1_000, keys.toSet().size, "повтор ключа означает потерянное сообщение")
    }

    @Test
    fun ключ_годится_для_заголовка_и_для_базы() {
        // Уезжает в X-Client-Msg-Id и лежит уникальным столбцом в messages: значит ни
        // пробелов, ни переводов строки, ни пустоты.
        val key = UuidDedupKeys.newKey()
        assertEquals(36, key.length, "ожидался UUID: $key")
        assertTrue(key.all { it.isDigit() || it in 'a'..'f' || it == '-' }, "лишние знаки: $key")
    }
}
