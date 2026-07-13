package io.tima.crypto

import io.kodium.KodiumPrivateKey
import io.kodium.ratchet.HKDF
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Recovery-фраза и ключ личности аккаунта (ADR-0010 §этап 3).
 *
 * Фраза — мнемоника структуры BIP39 (энтропия + контрольная сумма → индексы слов),
 * из неё детерминированно выводится **ключ личности** (Ed25519), один на пользователя и
 * стабильный между устройствами. Он доказывает «это тот же владелец аккаунта» на новом
 * устройстве — в отличие от ключей устройств, которые у каждого устройства свои.
 *
 * Словарь MVP — псевдослова из слогов (произносимые, детерминированные, без внешних данных).
 * Замена на осмысленный словарь (напр. русский BIP39) не меняет логику вывода ключа при том
 * же WORDLIST_SIZE и порядке индексов — но сменит сами слова, поэтому это breaking change для
 * уже выданных фраз (делать только с версионированием).
 */
object AccountMnemonic {

    const val ENTROPY_BYTES = 16       // 128 бит энтропии → 12 слов
    const val WORD_COUNT = 12
    private const val WORDLIST_SIZE = 2048 // 11 бит на слово

    /** 2048 псевдослов вида CVCV (согласная-гласная-согласная-гласная). */
    val wordlist: List<String> = buildWordlist()
    private val wordIndex: Map<String, Int> = wordlist.withIndex().associate { (i, w) -> w to i }

    private fun buildWordlist(): List<String> {
        val cons = "bdfgklmnprstvz".toList() // 14
        val vow = "aeiou".toList()           // 5 → 14*5*14*5 = 4900 комбинаций, берём первые 2048
        val words = ArrayList<String>(WORDLIST_SIZE)
        outer@ for (c1 in cons) for (v1 in vow) for (c2 in cons) for (v2 in vow) {
            words.add("$c1$v1$c2$v2")
            if (words.size >= WORDLIST_SIZE) break@outer
        }
        return words
    }

    /** Новая случайная фраза (12 слов). */
    fun generate(random: SecureRandom = SecureRandom()): List<String> =
        entropyToMnemonic(ByteArray(ENTROPY_BYTES).also(random::nextBytes))

    fun entropyToMnemonic(entropy: ByteArray): List<String> {
        require(entropy.size == ENTROPY_BYTES) { "энтропия должна быть $ENTROPY_BYTES байт" }
        val checksumBits = ENTROPY_BYTES * 8 / 32 // 4 бита для 128 бит
        val bits = StringBuilder()
        for (b in entropy) bits.append(byteBits(b))
        bits.append(byteBits(sha256(entropy)[0]).substring(0, checksumBits))
        return (0 until WORD_COUNT).map { i -> wordlist[bits.substring(i * 11, i * 11 + 11).toInt(2)] }
    }

    fun mnemonicToEntropy(words: List<String>): ByteArray {
        require(words.size == WORD_COUNT) { "нужно $WORD_COUNT слов, дано ${words.size}" }
        val bits = StringBuilder()
        for (w in words) {
            val idx = wordIndex[w.trim().lowercase()] ?: throw IllegalArgumentException("неизвестное слово: $w")
            bits.append(idx.toString(2).padStart(11, '0'))
        }
        val entropy = ByteArray(ENTROPY_BYTES)
        for (i in 0 until ENTROPY_BYTES) entropy[i] = bits.substring(i * 8, i * 8 + 8).toInt(2).toByte()
        val checksumBits = ENTROPY_BYTES * 8 / 32
        val expected = byteBits(sha256(entropy)[0]).substring(0, checksumBits)
        val actual = bits.substring(ENTROPY_BYTES * 8, ENTROPY_BYTES * 8 + checksumBits)
        require(expected == actual) { "контрольная сумма фразы не совпала — проверьте слова" }
        return entropy
    }

    /**
     * Ключ личности из фразы: детерминированный Ed25519 (+X25519) через
     * `KodiumPrivateKey.fromRaw(HKDF(entropy, "tima/account-identity/v1"))`.
     * Подпись/проверка — через [MessageSigner]; публичная часть — `signingKey`.
     */
    fun identityFromMnemonic(words: List<String>): KodiumPrivateKey {
        val seed = HKDF.deriveSecrets(
            salt = null,
            ikm = mnemonicToEntropy(words),
            info = "tima/account-identity/v1".encodeToByteArray(),
            length = 32,
        )
        return KodiumPrivateKey.fromRaw(seed)
    }

    /**
     * Симметричный ключ резервной копии из фразы (ADR-0010 §этап 4). Отдельная HKDF-метка,
     * чтобы backup-ключ не совпадал с identity: identity подписывает, backup шифрует
     * резервные обёртки «сообщений себе» (у self-чата нет живых источников для peer-восстановления).
     */
    fun backupKeyFromMnemonic(words: List<String>): ByteArray = HKDF.deriveSecrets(
        salt = null,
        ikm = mnemonicToEntropy(words),
        info = "tima/account-backup/v1".encodeToByteArray(),
        length = 32,
    )

    private fun byteBits(b: Byte): String = (b.toInt() and 0xFF).toString(2).padStart(8, '0')
    private fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
}
