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
    fun `опечатка в слове ловится контрольной суммой в подавляющем большинстве случаев`() {
        // Контрольная сумма — 4 бита (128 бит энтропии / 32), поэтому НЕВЕРНОЕ слово
        // проскакивает с вероятностью 1/16. Прежняя версия теста подменяла одно слово
        // и требовала отказа всегда — и падала примерно в 6% прогонов без всякой вины
        // кода. Проверяем настоящее свойство: ловится подавляющее большинство.
        val words = AccountMnemonic.generate().toMutableList()
        val original = words[0]
        var caught = 0
        var tried = 0
        for (candidate in AccountMnemonic.wordlist) {
            if (candidate == original) continue
            tried++
            words[0] = candidate
            runCatching { AccountMnemonic.mnemonicToEntropy(words) }
                .onFailure { if (it is IllegalArgumentException) caught++ }
        }
        // Ожидаем ~15/16 ≈ 93.75%; берём запас на случайность выбранной фразы.
        val rate = caught.toDouble() / tried
        assertTrue(rate > 0.85, "опечатка ловится лишь в ${"%.1f".format(rate * 100)}% случаев")
        // И хотя бы одна подмена обязана отвергаться — иначе проверки нет вовсе.
        assertTrue(caught > 0)
    }

    @Test
    fun `неизвестное слово отвергается`() {
        val words = AccountMnemonic.generate().toMutableList()
        words[3] = "zzzzz"
        assertFailsWith<IllegalArgumentException> { AccountMnemonic.mnemonicToEntropy(words) }
    }
}
