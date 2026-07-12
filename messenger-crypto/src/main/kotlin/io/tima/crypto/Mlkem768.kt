package io.tima.crypto

import org.bouncycastle.pqc.crypto.mlkem.MLKEMExtractor
import org.bouncycastle.pqc.crypto.mlkem.MLKEMGenerator
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyGenerationParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyPairGenerator
import org.bouncycastle.pqc.crypto.mlkem.MLKEMParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPrivateKeyParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPublicKeyParameters
import java.security.SecureRandom

/**
 * ML-KEM-768 (FIPS 203) для escrow-слоя — провайдер BouncyCastle.
 *
 * НЕ Kodium: его реализация ML-KEM самосогласована, но не интероперабельна с FIPS 203
 * (при общем seed ρ совпадает с эталоном, t̂ — нет; перекрёстные encapsulate/decapsulate
 * с эталонной реализацией дают разные shared). Подтверждено сверкой с @noble/post-quantum
 * (NIST ACVP-tested), см. поправку к ADR-0005. Escrow требует интеропа с HSM,
 * поэтому здесь стандартная реализация; весь NaCl-слой остаётся на Kodium.
 *
 * API повторяет `io.kodium.core.MLKEM` (порядок Pair: shared ПЕРВЫЙ) — при исправлении
 * Kodium upstream провайдер меняется в одном месте.
 */
object Mlkem768 {
    const val PublicKeySize = 1184
    const val SecretKeySize = 2400
    const val CiphertextSize = 1088
    const val SharedSecretSize = 32

    private val params = MLKEMParameters.ml_kem_768

    /** Генерация пары (CSPRNG). @return Pair(public 1184 B, secret 2400 B) */
    fun keyPair(): Pair<ByteArray, ByteArray> = keyPair(SecureRandom())

    /** Инкапсуляция на публичный ключ. @return Pair(sharedSecret 32 B, ciphertext 1088 B) */
    fun encapsulate(publicKey: ByteArray): Pair<ByteArray, ByteArray> {
        require(publicKey.size == PublicKeySize) { "ML-KEM-768 public key: ожидалось $PublicKeySize байт" }
        val encapsulated = MLKEMGenerator(SecureRandom())
            .generateEncapsulated(MLKEMPublicKeyParameters(params, publicKey))
        return Pair(encapsulated.secret, encapsulated.encapsulation)
    }

    /** Декапсуляция. FIPS 203 implicit rejection: повреждённый ct даёт другой shared, не ошибку. */
    fun decapsulate(ciphertext: ByteArray, secretKey: ByteArray): ByteArray? {
        if (ciphertext.size != CiphertextSize || secretKey.size != SecretKeySize) return null
        return try {
            MLKEMExtractor(MLKEMPrivateKeyParameters(params, secretKey)).extractSecret(ciphertext)
        } catch (e: Exception) {
            null
        }
    }

    /** Тест-хук KAT: детерминированный keygen из d‖z (64 байта, layout noble/FIPS 203). */
    internal fun keyPairFromSeed(seed: ByteArray): Pair<ByteArray, ByteArray> {
        require(seed.size == 64) { "seed = d(32) ‖ z(32)" }
        return keyPair(FixedRandom(seed))
    }

    private fun keyPair(random: SecureRandom): Pair<ByteArray, ByteArray> {
        val generator = MLKEMKeyPairGenerator()
        generator.init(MLKEMKeyGenerationParameters(random, params))
        val kp = generator.generateKeyPair()
        val pub = (kp.public as MLKEMPublicKeyParameters).encoded
        val sec = (kp.private as MLKEMPrivateKeyParameters).encoded
        return Pair(pub, sec)
    }

    /** Отдаёт байты из фиксированного буфера последовательно — независимо от разбивки запросов. */
    private class FixedRandom(buffer: ByteArray) : SecureRandom() {
        private val remaining = ArrayDeque(buffer.toList())
        override fun nextBytes(bytes: ByteArray) {
            for (i in bytes.indices) {
                bytes[i] = remaining.removeFirstOrNull()
                    ?: throw IllegalStateException("KeyGen запросил больше случайности, чем задано")
            }
        }
    }
}
