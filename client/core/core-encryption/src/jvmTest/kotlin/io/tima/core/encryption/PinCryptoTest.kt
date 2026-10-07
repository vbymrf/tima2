package io.tima.core.encryption

import io.tima.crypto.AccountMnemonic
import io.tima.domain.account.PinPhraseVerdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Пин-код (ПЛАН-(ПН)): хеш с солью и сверка фразы для сброса. */
class PinCryptoTest {

    @Test
    fun хеш_зависит_от_соли_и_повторяем() {
        val a = PinCrypto.salt()
        val b = PinCrypto.salt()
        assertFalse(a.contentEquals(b), "соль не случайна")
        assertTrue(PinCrypto.hash("1234".encodeToByteArray(), a).contentEquals(PinCrypto.hash("1234".encodeToByteArray(), a)))
        assertFalse(PinCrypto.hash("1234".encodeToByteArray(), a).contentEquals(PinCrypto.hash("1234".encodeToByteArray(), b)))
    }

    @Test
    fun фраза_сверяется_со_своим_ключом_или_ключом_владельца() {
        val words = AccountMnemonic.generate()
        val other = AccountMnemonic.generate()
        val mine = AccountIdentitiesOverKodium.fromWords(words)!!
        val owner = AccountIdentitiesOverKodium.fromWords(other)!!
        assertEquals(PinPhraseVerdict.Ok, PinCrypto.verdict(words.joinToString(" "), listOf(mine)))
        assertEquals(PinPhraseVerdict.Ok, PinCrypto.verdict(other.joinToString(" "), listOf(mine, owner)), "фраза владельца у временного")
        assertEquals(PinPhraseVerdict.Wrong, PinCrypto.verdict(other.joinToString(" "), listOf(mine)))
        assertEquals(PinPhraseVerdict.Offline, PinCrypto.verdict(words.joinToString(" "), null), "без сети сверять не с чем")
        assertIs<PinPhraseVerdict.Fault>(PinCrypto.verdict("раз два три", listOf(mine)))
    }
}
