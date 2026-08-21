package io.tima.crypto

import org.kotlincrypto.hash.sha2.SHA256

/** Байтовые утилиты канонической сериализации (schema/proto/README.md §canonical_bytes). */

internal fun u32le(value: Int): ByteArray = ByteArray(4) { i -> (value ushr (8 * i)).toByte() }

internal fun u64le(value: Long): ByteArray = ByteArray(8) { i -> (value ushr (8 * i)).toByte() }

/** lp(x) = u32(len(x)) ⊕ x; строки — UTF-8. */
internal fun lp(value: String): ByteArray {
    val utf8 = value.encodeToByteArray()
    return u32le(utf8.size) + utf8
}

// SHA-256 берётся готовой реализацией на чистом Kotlin, а не пишется здесь:
// «не изобретать примитивы» дороже одной зависимости. java.security.MessageDigest
// не годится — общий код обязан компилироваться под iOS.
internal fun sha256(data: ByteArray): ByteArray = SHA256().digest(data)

// Без String.format: он существует только на JVM, а этот файл — общий код.
// Таблица вместо форматирования заодно быстрее: hex вызывается на каждом векторе.
private const val HEX_DIGITS = "0123456789abcdef"

fun ByteArray.toHex(): String {
    val out = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xFF
        out[2 * i] = HEX_DIGITS[v ushr 4]
        out[2 * i + 1] = HEX_DIGITS[v and 0x0F]
    }
    return out.concatToString()
}

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "Нечётная длина hex-строки" }
    return ByteArray(length / 2) { i -> substring(2 * i, 2 * i + 2).toInt(16).toByte() }
}
