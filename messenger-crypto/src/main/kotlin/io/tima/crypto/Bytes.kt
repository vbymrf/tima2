package io.tima.crypto

import java.security.MessageDigest

/** Байтовые утилиты канонической сериализации (schema/proto/README.md §canonical_bytes). */

internal fun u32le(value: Int): ByteArray = ByteArray(4) { i -> (value ushr (8 * i)).toByte() }

internal fun u64le(value: Long): ByteArray = ByteArray(8) { i -> (value ushr (8 * i)).toByte() }

/** lp(x) = u32(len(x)) ⊕ x; строки — UTF-8. */
internal fun lp(value: String): ByteArray {
    val utf8 = value.encodeToByteArray()
    return u32le(utf8.size) + utf8
}

internal fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "Нечётная длина hex-строки" }
    return ByteArray(length / 2) { i -> substring(2 * i, 2 * i + 2).toInt(16).toByte() }
}
