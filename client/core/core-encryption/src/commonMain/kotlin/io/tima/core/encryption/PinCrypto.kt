package io.tima.core.encryption

import io.kodium.core.KDF
import io.kodium.core.nacl
import io.tima.domain.account.PinPhraseVerdict
import io.tima.domain.account.PhraseWords

/**
 * Криптография пин-кода (ПЛАН-(ПН)-ПИН-КОДА, Р8): медленный хеш вместо пина и сверка фразы для
 * сброса.
 *
 * **Хеш — PBKDF2-HMAC-SHA256, [ROUNDS] раундов.** Четыре цифры — десять тысяч вариантов, и
 * никакой хеш их не спрячет от того, кто вскрыл хранилище платформы. Медленный хеш нужен, чтобы
 * перебор шёл часами, а не долями секунды; от человека с телефоном в руках защищают паузы и
 * «только фраза» после десяти ошибок, а не хеш.
 */
object PinCrypto {
    const val ROUNDS = 20_000
    private const val HASH_BYTES = 32
    private const val SALT_BYTES = 16

    fun hash(pin: ByteArray, salt: ByteArray): ByteArray = KDF.deriveKey(pin, salt, ROUNDS, HASH_BYTES)

    fun salt(): ByteArray = nacl.randomBytes(SALT_BYTES)

    /**
     * Подходит ли фраза к одному из ключей личности (Р3): своему ключу аккаунта или — у
     * временного — ключу владельца. Ключи даёт сервер (Р13); здесь только сверка.
     *
     * @param keys открытые ключи личности, с которыми сверять; `null` — сервер не ответил.
     */
    fun verdict(text: String, keys: List<ByteArray>?): PinPhraseVerdict {
        val words = PhraseWords.parse(text)
        PhraseCheckOverKodium.check(words)?.let { return PinPhraseVerdict.Fault(it) }
        if (keys == null) return PinPhraseVerdict.Offline
        val mine = AccountIdentitiesOverKodium.fromWords(words) ?: return PinPhraseVerdict.Wrong
        return if (keys.any { it.contentEquals(mine) }) PinPhraseVerdict.Ok else PinPhraseVerdict.Wrong
    }
}
