package io.tima.core.encryption

import io.tima.crypto.AccountMnemonic
import io.tima.crypto.MessageSigner
import io.tima.domain.account.IdentitySigner

/**
 * Подпись ключом личности — переходник к порту `domain-account`.
 *
 * **Ключ выводится из двенадцати слов и на устройстве не хранится** (ADR-0010). Поэтому
 * подписать им можно только там, где человек фразу ввёл, — и только сразу: слова приходят
 * сюда, превращаются в подпись и уходят. Держать их в состоянии экрана или в хранилище
 * значило бы свести на нет весь заслон: укравшему устройство доставалась бы и фраза.
 *
 * Тем же ключом заверяется заведение виртуального аккаунта (ПЛАН-КОНТАКТОВ.md, Д10):
 * кода из SMS для аккаунта без телефона не будет никогда, и другого доказательства у
 * сервера нет.
 */
object IdentitySignerOverKodium : IdentitySigner {

    /**
     * @return `null` — слова не складываются в личность: не то число слов, слово не из
     *   списка, контрольная сумма не сошлась. Человеку во всех трёх случаях надо
     *   перепроверить фразу, и различать их незачем.
     */
    override fun sign(words: List<String>, bytes: ByteArray): ByteArray? {
        val clean = words.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val key = runCatching { AccountMnemonic.identityFromMnemonic(clean) }.getOrNull() ?: return null
        return MessageSigner.sign(key, bytes).getOrNull()
    }
}
