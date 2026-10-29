package io.tima.crypto

import io.kodium.MlKemVariant
import io.kodium.core.MLKEM
import io.kodium.core.fips203.KyberCipherText
import io.kodium.core.fips203.KyberDecapsulationKey
import io.kodium.core.fips203.KyberEncapsulationKey
import io.kodium.core.fips203.KyberKEMKeyPair
import io.kodium.core.fips203.MLKEM_768
import io.kodium.core.fips203.RandomProvider

/**
 * ML-KEM-768 (FIPS 203) для escrow-слоя — провайдер Kodium 1.1.0, нижний пакет
 * `io.kodium.core.fips203` (ADR-0005 Поправка-3, ПЛАН-(КМ) Р1, Р3).
 *
 * История. В Kodium 1.0.0 ML-KEM был не интероперабелен с FIPS 203: SHAKE брался через
 * `Digest`-грань вместо XOF, матрица `Â` заполнялась энтропией на 3% (ADR-0005 Поправка-1,
 * issue LivotovLabs/kodium#11). С 2026-08-14 до 2026-10-29 здесь стоял KyberKotlin
 * (Поправка-2) — его форки ради сборок под iOS. Kodium 1.1.0 исправил #11 (`xof`/`prf`
 * читают SHAKE потоком нужной длины) и публикует iOS — форки ушли.
 *
 * Нижний пакет, а не `io.kodium.core.MLKEM`: только в нём генерация ключа принимает
 * подставленную случайность, без которой вектор `mlkem768_escrow` не воспроизвести (Р3).
 *
 * **Схему закрытия выбирает Kodium, по байтам открытого ключа** — сломанная схема 1.0.0
 * осталась вариантом `LEGACY`, настройки «только FIPS 203» нет. Поэтому [encapsulate]
 * сначала проверяет вариант и на `LEGACY` отказывает (Р2): закрытый по ней блок escrow
 * анклав (Go, только FIPS 203) не открыл бы, а отправка прошла бы молча. Честный ключ так не
 * опознаётся; подложный не пройдёт подпись анклава — проверка страхует от ошибки.
 *
 * BouncyCastle остался в `testImplementation` как независимый оракул: сверка двух
 * реализаций идёт на каждой сборке — см. `CrossImplementationTest`. Отсутствие
 * именно такой сверки и позволило дефекту Kodium 1.0.0 дожить до релиза.
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

    /**
     * Инкапсуляция на публичный ключ. @return Pair(sharedSecret 32 B, ciphertext 1088 B)
     *
     * @throws IllegalArgumentException ключ не того размера или опознан как `LEGACY` (Р2).
     */
    fun encapsulate(publicKey: ByteArray): Pair<ByteArray, ByteArray> {
        require(publicKey.size == PublicKeySize) { "ML-KEM-768 public key: ожидалось $PublicKeySize байт" }
        require(MLKEM.variantOf(publicKey) == MlKemVariant.FIPS_203) { "ключ ML-KEM не FIPS 203 (LEGACY)" }
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
     * ВНИМАНИЕ на порядок половин. `KyberKeyGenerator` (у Kodium 1.1.0 тот же, что был
     * у KyberKotlin) запрашивает у источника случайности СНАЧАЛА `z` (уходит в ключ
     * декапсуляции для implicit rejection), ПОТОМ `d` (из него растёт K-PKE) — то есть
     * обратный нашему layout. Поэтому
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

    private fun toPair(kp: KyberKEMKeyPair): Pair<ByteArray, ByteArray> =
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

    // Здесь был SecureRandomProvider «на случай, если понадобится явный
     // источник». Удалён при переводе на KMP: его не вызывала ни одна строка —
    // keyPair() отдаёт случайность самому MLKEM_768, а KAT-проверки идут через
    // FixedRandom. Мёртвый код с java.security.SecureRandom внутри мешал бы
    // компиляции под iOS ради ничего.
}
