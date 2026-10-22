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

    /**
     * Загрузить уже закрытые ключом файла байты (картинка и файл сообщения, Решения §3, §6).
     * [postmanChat] и [postmanPeer] — файл вне пузыря в личной переписке: его держит почтальон.
     * @return media_id или `null` — сети нет, предел, квота.
     */
    suspend fun uploadSealed(sealed: ByteArray, mime: String, postmanChat: String? = null, postmanPeer: String? = null): String? = null

    /**
     * Забрать файл почтальона (Решения §6): байты, «на сервере его уже нет» (срок прошёл — можно
     * попросить снова) или неудача связи. Отличие важно: «нет» просится заново, связь — повторяется.
     */
    suspend fun fetch(mediaId: String): MediaFetch = download(mediaId)?.let { MediaFetch.Got(it) } ?: MediaFetch.Failed

    /** Опоздавшее устройство просит файл снова — `media.wanted` устройствам, у которых он есть. */
    suspend fun requestAgain(mediaId: String): Boolean = false

    /** Загрузить файл почтальона снова — тот же номер и ключ: у этого устройства он есть. */
    suspend fun reupload(mediaId: String, sealed: ByteArray): Boolean = false

    /**
     * Что сервер принимает (ПЛАН-(ИФ) ИФ1): поля ответа `GET /media/rules` строками; `null` — не
     * ответил. Разбор — выше, где живут правила.
     */
    suspend fun rules(): Map<String, String>? = null

    /** Убрать свой файл почтальона — фото заменено правкой (ПЛАН-(ИФ) ИФ-В2). */
    suspend fun drop(mediaId: String): Boolean = false

    /** Исход забора файла почтальона. */
    sealed interface MediaFetch {
        class Got(val bytes: ByteArray) : MediaFetch
        data object Gone : MediaFetch
        data object Failed : MediaFetch
    }

    /** Заглушка там, где медиа нет: харнесс, снимки. */
    object None : Media {
        override suspend fun upload(bytes: ByteArray, mime: String): String? = null
        override suspend fun download(mediaId: String): ByteArray? = null
    }
}
