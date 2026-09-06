package io.tima.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Срок жизни токена, прочитанный из него самого (находка 2026-09-06).
 *
 * Токен живёт сутки, и до этой правки обновлять его было нечем: через сутки после входа
 * каждая ручка под токеном отвечала `401`, а человек видел просто неработающее
 * приложение. Чтобы обновляться заранее, надо знать срок — и берётся он из самого токена.
 */
class DeviceTokensTest {

    /**
     * Настоящий JWT: заголовок, полезная часть с `exp`, подпись.
     *
     * Подпись здесь заведомо не сходится — и это правильно: **клиент её не проверяет**.
     * Проверка подписи — дело сервера; клиенту нужно одно число, чтобы решить, не пора ли
     * обновиться. Проверяй он подпись, ему понадобился бы ключ сервера, то есть вторая
     * система доверия на пустом месте.
     */
    private fun token(exp: Long): String {
        val header = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"
        val payload = base64Url("""{"sub":"u-1","device_id":"d-1","scope":"access","exp":$exp}""")
        return "$header.$payload.подпись-не-важна"
    }

    private fun base64Url(text: String): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val bytes = text.encodeToByteArray()
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        for (byte in bytes) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
            bits += 8
            while (bits >= 6) {
                bits -= 6
                out.append(alphabet[(buffer shr bits) and 0x3F])
            }
        }
        if (bits > 0) out.append(alphabet[(buffer shl (6 - bits)) and 0x3F])
        return out.toString()
    }

    @Test
    fun срок_читается_из_токена() {
        val expires = 1_800_000_000L
        assertEquals(expires * 1000, expiresAt(token(expires)))
    }

    @Test
    fun мусор_не_притворяется_сроком() {
        // Не разобралось — значит срок неизвестен, и обновиться надо на всякий случай.
        // Выдумать здесь «наверное, ещё живой» значило бы однажды работать мёртвым
        // токеном до первого отказа.
        assertNull(expiresAt(""))
        assertNull(expiresAt("не-токен"))
        assertNull(expiresAt("a.b.c"))
        assertNull(expiresAt("header..signature"))
    }

    @Test
    fun токен_без_поля_exp_считается_неизвестным() {
        val payload = base64Url("""{"sub":"u-1","scope":"access"}""")
        assertNull(expiresAt("header.$payload.signature"))
    }
}
