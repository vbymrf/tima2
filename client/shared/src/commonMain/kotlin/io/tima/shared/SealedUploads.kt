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
        val started = msNow()
        val id = media().uploadSealed(sealed, mime, postmanChat, postmanPeer)
        // Загрузка — строкой журнала (ПЛАН-(ЖС) §6): «не отправляется файл» — предел, сеть или сервер.
        io.tima.core.diag.Journal.note(
            io.tima.core.diag.LogCode.FILE_SEND,
            if (id != null) "загружено" else "не вышло",
            "вид" to mime.substringBefore('/'),
            "размер" to sealed.size,
            "мс" to (msNow() - started),
            "почтальон" to (postmanChat != null),
        )
        if (id == null) return null
        if (postmanChat != null) keepSent?.invoke(id, sealed, mime)
        return id to key
    }
}
