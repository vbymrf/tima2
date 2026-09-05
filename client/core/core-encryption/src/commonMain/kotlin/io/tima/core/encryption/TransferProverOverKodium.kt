package io.tima.core.encryption

import io.tima.crypto.AccountMnemonic
import io.tima.crypto.MessageSigner
import io.tima.domain.account.TransferProver

/**
 * Доказательство права на передаваемый аккаунт — подпись кода его ключом личности
 * (ПЛАН-КОНТАКТОВ.md, Д12).
 *
 * **Так и проверяется «ввёл фразу».** Фразы сервер не видел и видеть не должен, а ключ из
 * неё выводится однозначно: подпись кода этим ключом и есть доказательство, что фраза у
 * предъявителя есть. Отправлять саму фразу было бы и лишним, и опасным — она живёт дольше
 * передачи.
 *
 * Разбор кода живёт здесь, а не в `domain`: код приходит в base64url, и превращение
 * строки в байты — знание кодировки, а не правило продукта. Для слоя выше код —
 * непрозрачная строка от сервера.
 */
object TransferProverOverKodium : TransferProver {

    /**
     * @return `null` — слова не складываются в личность либо код не разбирается. Различать
     *   человеку незачем: в обоих случаях перепроверяются фраза и код.
     */
    override fun prove(words: List<String>, code: String): ByteArray? {
        val bytes = decodeBase64Url(code) ?: return null
        if (bytes.size != CODE) return null
        val clean = words.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val key = runCatching { AccountMnemonic.identityFromMnemonic(clean) }.getOrNull() ?: return null
        return MessageSigner.sign(key, bytes).getOrNull()
    }

    /** Тридцать два байта — столько порождает сервер (`transfers.go`). */
    private const val CODE = 32
}

/**
 * base64url без выравнивания — то, чем сервер кодирует код передачи
 * (`base64.RawURLEncoding`).
 *
 * Свой разбор, а не общий base64: алфавиты разные, и обычный разбор споткнётся на «-» и
 * «_». Вход недоверенный — код человек вводит руками или приносит камерой, — поэтому
 * негодное даёт `null`, а не исключение.
 */
private fun decodeBase64Url(text: String): ByteArray? {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    val line = text.trim().trimEnd('=')
    if (line.isEmpty()) return null
    val out = ArrayList<Byte>(line.length * 3 / 4)
    var buffer = 0
    var bits = 0
    for (glyph in line) {
        val value = alphabet.indexOf(glyph)
        if (value < 0) return null
        buffer = (buffer shl 6) or value
        bits += 6
        if (bits >= 8) {
            bits -= 8
            out += ((buffer shr bits) and 0xFF).toByte()
        }
    }
    return out.toByteArray()
}
