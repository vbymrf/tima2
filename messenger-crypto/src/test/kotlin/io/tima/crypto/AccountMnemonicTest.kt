package io.tima.crypto

import io.kodium.Kodium
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AccountMnemonicTest {

    @Test
    fun `словарь — 2048 уникальных слов`() {
        assertEquals(2048, AccountMnemonic.wordlist.size)
        assertEquals(2048, AccountMnemonic.wordlist.toSet().size)
    }

    @Test
    fun `фраза - энтропия - фраза (roundtrip)`() {
        val words = AccountMnemonic.generate()
        assertEquals(12, words.size)
        val entropy = AccountMnemonic.mnemonicToEntropy(words)
        assertEquals(words, AccountMnemonic.entropyToMnemonic(entropy))
    }

    @Test
    fun `ключ личности детерминирован по фразе`() {
        val words = AccountMnemonic.generate()
        val a = AccountMnemonic.identityFromMnemonic(words)
        val b = AccountMnemonic.identityFromMnemonic(words)
        assertEquals(
            a.getPublicKey().signingKey.toHex(),
            b.getPublicKey().signingKey.toHex(),
            "одна фраза → один публичный ключ личности",
        )
    }

    @Test
    fun `подпись ключом личности проверяется его публичной частью`() {
        val identity = AccountMnemonic.identityFromMnemonic(AccountMnemonic.generate())
        val msg = "recover|group-1|device-2".encodeToByteArray()
        val sig = MessageSigner.sign(identity, msg).getOrThrow()
        assertTrue(MessageSigner.verify(identity.getPublicKey().signingKey, msg, sig))
        // Чужой ключ личности не подтверждает
        val other = AccountMnemonic.identityFromMnemonic(AccountMnemonic.generate())
        assertTrue(!MessageSigner.verify(other.getPublicKey().signingKey, msg, sig))
    }

    @Test
    fun `опечатка в слове ловится контрольной суммой`() {
        val words = AccountMnemonic.generate().toMutableList()
        // заменим слово на другое из словаря — checksum почти наверняка не сойдётся
        val wrong = AccountMnemonic.wordlist.first { it != words[0] }
        words[0] = wrong
        assertFailsWith<IllegalArgumentException> { AccountMnemonic.mnemonicToEntropy(words) }
    }

    @Test
    fun `неизвестное слово отвергается`() {
        val words = AccountMnemonic.generate().toMutableList()
        words[3] = "zzzzz"
        assertFailsWith<IllegalArgumentException> { AccountMnemonic.mnemonicToEntropy(words) }
    }
}
