package io.tima.core.encryption

import io.tima.crypto.AccountMnemonic
import io.tima.domain.account.AccountIdentities
import io.tima.domain.account.NewAccountIdentity

/**
 * Личность аккаунта из секретной фразы — переходник к порту `domain-account`.
 *
 * Двенадцать слов, из них HKDF с меткой `tima/account-identity/v1` и `KodiumPrivateKey`
 * (ADR-0010). Метка и порядок слов **печёные**: их правка делает уже выданные фразы
 * нерабочими, и записаны они в таблице неизменяемых (`crypto-invariants.mdc`).
 *
 * **Слова наружу уходят один раз и только человеку.** Ни в журнал, ни в хранилище, ни на
 * сервер: серверу отправляется публичная часть, и по ней он узнаёт ту же личность на новом
 * устройстве.
 */
object AccountIdentitiesOverKodium : AccountIdentities {

    override fun fresh(): NewAccountIdentity {
        val слова = AccountMnemonic.generate()
        return NewAccountIdentity(words = слова, identityPub = публичный(слова))
    }

    /**
     * Фраза не та — `null`, а не исключение.
     *
     * Неверная фраза это обычное действие человека (опечатка, не тот листок), а не поломка
     * кода. Различать «слов не двенадцать», «слово не из списка» и «контрольная сумма не
     * сошлась» человеку незачем: во всех трёх случаях надо перепроверить фразу.
     */
    override fun fromWords(words: List<String>): ByteArray? =
        runCatching { публичный(words.map { it.trim().lowercase() }.filter { it.isNotEmpty() }) }
            .getOrNull()

    private fun публичный(words: List<String>): ByteArray =
        AccountMnemonic.identityFromMnemonic(words).getPublicKey().signingKey
}
