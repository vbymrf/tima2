@file:OptIn(ExperimentalEncodingApi::class)

package io.tima.app.platform

import io.tima.crypto.AccountMnemonic
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private val b64url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

actual fun newIdentity(): IdentityKeys = fromWords(AccountMnemonic.generate())

actual fun identityFromPhrase(words: List<String>): IdentityKeys? =
    try {
        fromWords(words.map { it.trim().lowercase() }.filter { it.isNotEmpty() })
    } catch (_: Throwable) {
        null // неизвестное слово или контрольная сумма не сошлась
    }

private fun fromWords(words: List<String>): IdentityKeys {
    val key = AccountMnemonic.identityFromMnemonic(words) // валидирует checksum
    return IdentityKeys(
        phrase = words,
        secretB64 = b64url.encode(key.secretKey),
        pubB64 = b64url.encode(key.getPublicKey().signingKey),
        backupB64 = b64url.encode(AccountMnemonic.backupKeyFromMnemonic(words)),
    )
}
