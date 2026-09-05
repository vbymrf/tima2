package io.tima.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Разбор кода передачи (ПЛАН-КОНТАКТОВ.md, Д12).
 *
 * Разбор строже, чем кажется нужным, и это намеренно: дальше код уходит на подпись, а
 * подпись не сходится и по другой причине — фраза не та. Пропусти сюда мусор, и человеку
 * скажут «фраза не подходит» там, где он вставил не ту строку.
 */
class TransferQrTest {

    /** 43 знака — 32 байта в base64url без выравнивания, столько порождает сервер. */
    private val code = "A".repeat(43)

    @Test
    fun ссылка_и_голый_код_дают_одно() {
        assertEquals(code, TransferQr.parse(TransferQr.payload(code)))
        // Голый — потому что код диктуют, пересылают текстом и переписывают с бумаги.
        // Требовать вокруг него ссылку значило бы отказывать тому, кто принёс ровно то,
        // что ему дали.
        assertEquals(code, TransferQr.parse(code))
        assertEquals(code, TransferQr.parse("  $code  "))
    }

    @Test
    fun чужая_ссылка_и_мусор_не_проходят() {
        assertNull(TransferQr.parse("tima://link/v1?session_id=abc&secret=def"))
        assertNull(TransferQr.parse(""))
        assertNull(TransferQr.parse("не код вовсе"))
        // Обычный base64: «+» и «/» сервер не примет, и лучше сказать об этом здесь.
        assertNull(TransferQr.parse("A".repeat(42) + "+"))
        assertNull(TransferQr.parse("A".repeat(42)))
        assertNull(TransferQr.parse("A".repeat(44)))
    }

    @Test
    fun хвост_ссылки_отрезается() {
        // Системная камера иногда приводит с довеском — она сама дописывает параметры.
        assertEquals(code, TransferQr.parse(TransferQr.payload(code) + "&utm=camera"))
    }
}
