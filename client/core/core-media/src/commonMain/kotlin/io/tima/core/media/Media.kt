package io.tima.core.media

/**
 * Медиа-хранилище: положить файл, получить файл (media-storage.md §2).
 *
 * Порт здесь, реализация в core-network: три ручки — init, PUT по подписанной ссылке,
 * complete — это устройство сети, а не медиа. Экрану и хранилищу профиля нужны две
 * операции: отдать байты — получить идентификатор, отдать идентификатор — получить байты.
 */
interface Media {
    /** @return media_id или `null` — сети нет либо хранилище не настроено. */
    suspend fun upload(bytes: ByteArray, mime: String): String?

    /** @return байты файла или `null`. */
    suspend fun download(mediaId: String): ByteArray?

    /** Заглушка там, где медиа нет: харнесс, снимки. */
    object None : Media {
        override suspend fun upload(bytes: ByteArray, mime: String): String? = null
        override suspend fun download(mediaId: String): ByteArray? = null
    }
}
