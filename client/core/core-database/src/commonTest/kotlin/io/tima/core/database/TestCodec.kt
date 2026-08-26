package io.tima.core.database

import io.tima.domain.chat.MessageBodyCodec

/**
 * Кодек-подделка: тело — просто UTF-8.
 *
 * Настоящий кодек (`zstd(protobuf(…))`) живёт в `core-encryption`, и `core-database` о нём
 * не знает — сюда приезжает доменный порт. Проверяется здесь не упаковка, а то, что
 * переходник тело читает и что нечитаемое не роняет страницу.
 */
internal object Codec : MessageBodyCodec {
    /** Тело, которое кодек читать отказывается: у входящего это «ключа нет». */
    val UNREADABLE: ByteArray = byteArrayOf(-1)

    override fun encodeText(text: String): ByteArray = text.encodeToByteArray()

    override fun decodeText(body: ByteArray): String? =
        if (body.contentEquals(UNREADABLE)) null else body.decodeToString()
}
