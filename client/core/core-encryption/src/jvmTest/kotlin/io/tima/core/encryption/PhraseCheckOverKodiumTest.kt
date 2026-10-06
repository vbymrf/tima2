package io.tima.core.encryption

import io.tima.crypto.AccountMnemonic
import io.tima.domain.account.PhraseFault
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhraseCheckOverKodiumTest {

    private val good = AccountMnemonic.generate()

    @Test
    fun верная_фраза_проходит() {
        assertNull(PhraseCheckOverKodium.check(good))
    }

    @Test
    fun слов_меньше_говорит_сколько() {
        assertEquals(PhraseFault.Count(11, 12), PhraseCheckOverKodium.check(good.dropLast(1)))
    }

    @Test
    fun слово_не_из_списка_называется_по_номеру() {
        val typo = good.toMutableList().also { it[4] = "zzzz" }
        assertEquals(PhraseFault.Unknown(listOf(5 to "zzzz")), PhraseCheckOverKodium.check(typo))
    }

    @Test
    fun слова_из_списка_но_сумма_не_сошлась() {
        // Перебираем замену последнего слова, пока не найдётся не сходящаяся по сумме: таких 15 из 16.
        val broken = AccountMnemonic.wordlist.asSequence()
            .map { good.dropLast(1) + it }
            .first { runCatching { AccountMnemonic.mnemonicToEntropy(it) }.isFailure }
        assertTrue(PhraseCheckOverKodium.check(broken) == PhraseFault.Checksum)
    }
}
