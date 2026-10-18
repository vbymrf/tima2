package io.tima.shared

import io.tima.core.encryption.MediaFiles
import io.tima.core.media.Media
import io.tima.domain.chat.AttachmentUploads

/**
 * Загрузка файла сообщения (Решения §3, §6): свой ключ у каждого файла, байты закрыты до выхода с
 * устройства, ключ уезжает в зашифрованном теле сообщения. Сервер видит только закрытое.
 */
class SealedUploads(private val media: () -> Media) : AttachmentUploads {
    override suspend fun upload(bytes: ByteArray, mime: String, postmanChat: String?, postmanPeer: String?): Pair<String, ByteArray>? {
        val key = MediaFiles.newKey()
        val sealed = MediaFiles.seal(key, bytes)
        val id = media().uploadSealed(sealed, mime, postmanChat, postmanPeer) ?: return null
        return id to key
    }
}
