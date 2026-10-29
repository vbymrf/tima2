package io.tima.crypto

import io.kodium.MlKemVariant
import io.kodium.core.MLKEM
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Поставщик ML-KEM — Kodium 1.1.0 (ПЛАН-(КМ) Р1–Р3). Схему закрытия Kodium выбирает сам, по
 * открытому ключу; [Mlkem768.encapsulate] на ключ старой схемы обязан отказать (Р2), а свой
 * ключ — быть FIPS 203. На всех платформах, а не только на JVM: на iOS сверки с BouncyCastle нет.
 */
class Mlkem768Test {

    @Test
    fun `ключ старой схемы LEGACY — закрытие отказывает`() {
        val (legacyPublic, _) = MLKEM.keyPair(MlKemVariant.LEGACY)
        assertEquals(MlKemVariant.LEGACY, MLKEM.variantOf(legacyPublic))
        assertFailsWith<IllegalArgumentException> { Mlkem768.encapsulate(legacyPublic) }
    }

    @Test
    fun `свой ключ — FIPS 203 и открывает своё закрытие`() {
        repeat(20) {
            val (publicKey, secretKey) = Mlkem768.keyPair()
            assertEquals(MlKemVariant.FIPS_203, MLKEM.variantOf(publicKey))
            val (shared, ciphertext) = Mlkem768.encapsulate(publicKey)
            assertContentEquals(shared, Mlkem768.decapsulate(ciphertext, secretKey))
        }
    }
}
