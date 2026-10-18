package io.tima.core.encryption

import io.tima.crypto.MediaCipher

/**
 * Файлы сообщения: закрыть и открыть ключом файла (media-storage.md §5). Ключ едет в зашифрованном
 * теле сообщения (`MediaRef.media_key`), сервер видит только закрытые байты. Фасад — единственная
 * дверь к шифру файлов выше `core-encryption`.
 */
object MediaFiles {
    /** Открыть скачанное; ключ не подошёл или байты битые — `null`. */
    fun open(key: ByteArray, sealed: ByteArray): ByteArray? = MediaCipher.open(key, sealed).getOrNull()

    /** Закрыть перед загрузкой. */
    fun seal(key: ByteArray, plain: ByteArray): ByteArray = MediaCipher.seal(key, plain).getOrThrow()
}
