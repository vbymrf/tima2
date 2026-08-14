package io.tima.crypto

import asia.hombre.kyber.KyberCipherText
import asia.hombre.kyber.KyberDecapsulationKey
import asia.hombre.kyber.KyberEncapsulationKey
import asia.hombre.kyber.api.MLKEM_768
import asia.hombre.kyber.interfaces.RandomProvider
import java.security.SecureRandom

/**
 * ML-KEM-768 (FIPS 203) для escrow-слоя — провайдер KyberKotlin
 * (`asia.hombre:kyber`, ADR-0005 Поправка-2).
 *
 * НЕ Kodium: его реализация ML-KEM не интероперабельна с FIPS 203 — `KyberMath`
 * берёт SHAKE через `Digest`-грань kotlincrypto вместо XOF, из-за чего матрица `Â`
 * заполняется энтропией на 3%, а шум `s`/`e` — наполовину (ADR-0005 Поправка-1,
 * стенд воспроизведения — `doc_add/kodium-mlkem/`).
 *
 * KyberKotlin — апстрим того самого кода, что вендорен в Kodium, и **в нём дефекта
 * нет**: там `xof` возвращает поток, а `prf` получает длину конструктором. Выбран
 * вместо BouncyCastle по двум причинам: 163 КБ против 8,68 МБ и поддержка Kotlin
 * Multiplatform (BouncyCastle только JVM и закрывал бы дорогу на iOS).
 *
 * BouncyCastle остался в `testImplementation` как независимый оракул: сверка двух
 * реализаций идёт на каждой сборке — см. `CrossImplementationTest`. Отсутствие
 * именно такой сверки и позволило дефекту Kodium дожить до релиза.
 *
 * API повторяет `io.kodium.core.MLKEM` (порядок Pair: shared ПЕРВЫЙ) — провайдер
 * меняется в одном месте.
 */
object Mlkem768 {
    const val PublicKeySize = 1184
    const val SecretKeySize = 2400
    const val CiphertextSize = 1088
    const val SharedSecretSize = 32

    /** Генерация пары (CSPRNG). @return Pair(public 1184 B, secret 2400 B) */
    fun keyPair(): Pair<ByteArray, ByteArray> = toPair(MLKEM_768().generate())

    /** Инкапсуляция на публичный ключ. @return Pair(sharedSecret 32 B, ciphertext 1088 B) */
    fun encapsulate(publicKey: ByteArray): Pair<ByteArray, ByteArray> {
        require(publicKey.size == PublicKeySize) { "ML-KEM-768 public key: ожидалось $PublicKeySize байт" }
        val result = KyberEncapsulationKey.fromBytes(publicKey).encapsulate()
        return Pair(result.sharedSecretKey, result.cipherText.fullBytes)
    }

    /** Декапсуляция. FIPS 203 implicit rejection: повреждённый ct даёт другой shared, не ошибку. */
    fun decapsulate(ciphertext: ByteArray, secretKey: ByteArray): ByteArray? {
        if (ciphertext.size != CiphertextSize || secretKey.size != SecretKeySize) return null
        return try {
            KyberCipherText.fromBytes(ciphertext).decapsulate(KyberDecapsulationKey.fromBytes(secretKey))
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Тест-хук KAT: детерминированный keygen из `d ‖ z` (64 байта, layout noble/FIPS 203).
     *
     * ВНИМАНИЕ на порядок половин. `KyberKeyGenerator` запрашивает у источника
     * случайности СНАЧАЛА `z` (уходит в ключ декапсуляции для implicit rejection),
     * ПОТОМ `d` (из него растёт K-PKE) — то есть обратный нашему layout. Поэтому
     * здесь половины меняются местами.
     *
     * Проверено сверкой с BouncyCastle на seed с `d != z`; вектор `mlkem768_escrow`
     * такую ошибку не поймал бы: в нём все 64 байта одинаковы.
     */
    internal fun keyPairFromSeed(seed: ByteArray): Pair<ByteArray, ByteArray> {
        require(seed.size == 64) { "seed = d(32) ‖ z(32)" }
        val zd = seed.copyOfRange(32, 64) + seed.copyOfRange(0, 32)
        return toPair(MLKEM_768(FixedRandom(zd)).generate())
    }

    private fun toPair(kp: asia.hombre.kyber.KyberKEMKeyPair): Pair<ByteArray, ByteArray> =
        Pair(kp.encapsulationKey.fullBytes, kp.decapsulationKey.fullBytes)

    /** Отдаёт байты из фиксированного буфера последовательно — независимо от разбивки запросов. */
    private class FixedRandom(buffer: ByteArray) : RandomProvider {
        private val remaining = ArrayDeque(buffer.toList())
        override fun fillWithRandom(byteArray: ByteArray) {
            for (i in byteArray.indices) {
                byteArray[i] = remaining.removeFirstOrNull()
                    ?: throw IllegalStateException("KeyGen запросил больше случайности, чем задано")
            }
        }
    }

    /** CSPRNG-провайдер на случай, если понадобится явный источник. */
    internal object SecureRandomProvider : RandomProvider {
        private val random = SecureRandom()
        override fun fillWithRandom(byteArray: ByteArray) = random.nextBytes(byteArray)
    }
}
