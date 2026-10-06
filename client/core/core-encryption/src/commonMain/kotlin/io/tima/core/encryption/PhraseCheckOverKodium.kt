package io.tima.core.encryption

import io.tima.crypto.AccountMnemonic
import io.tima.domain.account.PhraseChecker
import io.tima.domain.account.PhraseFault

/**
 * Проверка фразы по словарю и контрольной сумме — до сервера (отчёт DGAR, 2026-10-06).
 *
 * Сам вывод ключа из фразы здесь не меняется и не повторяется: только то, что открыто любому, —
 * сколько слов, все ли из списка, сходится ли контрольная сумма. Контрольная сумма короткая
 * (4 бита): одна опечатка, давшая другое слово из списка, проходит её в одном случае из
 * шестнадцати — тогда фраза «настоящая», но чужая, и это скажет уже сервер.
 */
object PhraseCheckOverKodium : PhraseChecker {
    private val known: Set<String> = AccountMnemonic.wordlist.toSet()

    override fun check(words: List<String>): PhraseFault? {
        val unknown = words.withIndex().filter { (_, w) -> w !in known }.map { (i, w) -> i + 1 to w }
        if (words.size != AccountMnemonic.WORD_COUNT) {
            // Число слов важнее: при слившихся словах «не из списка» было бы следствием, а не причиной.
            return PhraseFault.Count(words.size, AccountMnemonic.WORD_COUNT)
        }
        if (unknown.isNotEmpty()) return PhraseFault.Unknown(unknown)
        return if (runCatching { AccountMnemonic.mnemonicToEntropy(words) }.isSuccess) null else PhraseFault.Checksum
    }
}
