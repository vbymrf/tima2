package io.tima.shared

import io.tima.core.encryption.MediaFiles
import io.tima.core.media.Media
import io.tima.domain.chat.AttachmentUploads

/**
 * Загрузка файла сообщения (Решения §3, §6): свой ключ у каждого файла, байты закрыты до выхода с
 * устройства, ключ уезжает в зашифрованном теле сообщения. Сервер видит только закрытое.
 */
class SealedUploads(
    private val media: () -> Media,
    /**
     * Файл почтальона ушёл — закрытые байты в свою папку (Решения §6): показать без сети и загрузить
     * снова, когда опоздавшее устройство попросит. `null` — не храним (картинки внутри сообщения).
     */
    private val keepSent: ((mediaId: String, sealed: ByteArray, mime: String) -> Unit)? = null,
) : AttachmentUploads {
    override suspend fun upload(bytes: ByteArray, mime: String, postmanChat: String?, postmanPeer: String?): Pair<String, ByteArray>? {
        val key = MediaFiles.newKey()
        val sealed = MediaFiles.seal(key, bytes)
        val id = media().uploadSealed(sealed, mime, postmanChat, postmanPeer) ?: return null
        if (postmanChat != null) keepSent?.invoke(id, sealed, mime)
        return id to key
    }
}
