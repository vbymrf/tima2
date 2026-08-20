package io.tima.spike

import asia.hombre.kyber.KyberCipherText
import asia.hombre.kyber.KyberDecapsulationKey
import asia.hombre.kyber.KyberEncapsulationKey
import asia.hombre.kyber.api.MLKEM_768
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * ML-KEM-768 проверяется ТОЛЬКО на JVM, и это не выбор, а констатация:
 * `asia.hombre:kyber` (как и его зависимость `keccak`) **не публикует артефакты
 * Apple**. Проверено листингом Maven Central 2026-08-20; сборка под iOS падала на
 * «Unresolved platforms: [iosArm64, iosSimulatorArm64]».
 *
 * Из этого следует, что **escrow на iOS сейчас невозможен**: каждое исходящее
 * сообщение оборачивает ключ на ключ эпохи escrow через ML-KEM. То есть это не
 * «нехватка удобства», а отсутствие обязательного слоя.
 *
 * Варианты решения и их цена — в отчёте doc_mig/отчёты/2026-08-20-К0-К1.md.
 * Решение за заказчиком: оно затрагивает crypto-invariants («один провайдер на
 * примитив»), а значит не принимается по ходу вёрстки.
 */
class MlkemJvmOnlyTest {

    @Test
    fun общий_секрет_совпадает_и_размеры_канонические() {
        // Размеры FIPS 203 — те же константы, что в Mlkem768.kt. Другой провайдер,
        // отдающий другие размеры, — это не «другая реализация», это несовместимость.
        val pair = MLKEM_768().generate()
        val publicKey = pair.encapsulationKey.fullBytes
        val secretKey = pair.decapsulationKey.fullBytes
        assertEquals(1184, publicKey.size, "ML-KEM-768 public key")
        assertEquals(2400, secretKey.size, "ML-KEM-768 secret key")

        val encapsulated = KyberEncapsulationKey.fromBytes(publicKey).encapsulate()
        assertEquals(1088, encapsulated.cipherText.fullBytes.size, "ML-KEM-768 ciphertext")
        assertEquals(32, encapsulated.sharedSecretKey.size, "общий секрет")

        val decapsulated = KyberCipherText.fromBytes(encapsulated.cipherText.fullBytes)
            .decapsulate(KyberDecapsulationKey.fromBytes(secretKey))

        assertContentEquals(encapsulated.sharedSecretKey, decapsulated, "общий секрет не совпал")
    }
}
