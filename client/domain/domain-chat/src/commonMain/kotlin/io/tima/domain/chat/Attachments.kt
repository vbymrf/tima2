package io.tima.domain.chat

/**
 * Файл, уже загруженный и закрытый своим ключом, — для тела сообщения (Решения §3, §6).
 * [preview] — превью фото от отправителя (до 20 КБ), [name] — имя файла вне пузыря.
 */
class OutMedia(
    val mediaId: String,
    val key: ByteArray,
    val mime: String,
    val width: Int = 0,
    val height: Int = 0,
    val size: Long = 0,
    val preview: ByteArray = ByteArray(0),
    val name: String = "",
)

/**
 * Тела сообщений-вложений (Решения §2–§6) — реализует `core-encryption` тем же конвейером, что
 * и текст. Порт, потому что разметка и `MessageBody` выше `core-encryption` не поднимаются.
 */
interface AttachmentBodies {
    /** Фото без текста — вне пузыря, превью внутри тела (личная переписка). */
    fun album(photos: List<OutMedia>): ByteArray

    /** Файл вне пузыря (личная переписка). */
    fun file(file: OutMedia): ByteArray

    /** Картинки внутри сообщения — по одной в ряд во всю ширину (группа, канал). */
    fun inlineImages(images: List<OutMedia>): ByteArray

    fun contact(name: String, phone: String, userId: String): ByteArray

    fun place(lat: Double, lon: Double, label: String): ByteArray

    /** Ссылка; [cloudKind] не пусто — файл в облаке (`file`, `document`, `audio`, `video`). */
    fun link(url: String, title: String, cloudKind: String = ""): ByteArray
}

/** Порт загрузки: закрыть ключом и отдать в хранилище; почтальон — для личной переписки. */
interface AttachmentUploads {
    /** @return загруженный файл или `null` — нет сети, предел, квота. */
    suspend fun upload(bytes: ByteArray, mime: String, postmanChat: String?, postmanPeer: String?): Pair<String, ByteArray>?
}

/** Чем кончилась отправка вложения. */
sealed interface AttachResult {
    data class Queued(val dedupKey: String) : AttachResult
    data object Offline : AttachResult
    data object TooMany : AttachResult
    /** Отказ по файлу: `large`, `type`, `nothing`. */
    data class Refused(val reason: String) : AttachResult
}

/**
 * Отправка вложений (Решения §2, §3, §6): фото — в личной без текста вне пузыря (полное у
 * почтальона, превью в теле), в группе — картинками внутри сообщения; файлы — в личной почтальоном
 * (до 10 МБ, себе 50), в группе — только ссылкой на облако; контакт, место, ссылка — карточками.
 *
 * Загрузка — до очереди: сообщение без загруженного файла некуда отправлять. Нет сети — отказ
 * сразу, словами; сообщение не встаёт в очередь пустым.
 */
class SendAttachments(
    private val queue: OutgoingQueue,
    private val uploads: AttachmentUploads,
    private val bodies: AttachmentBodies,
    private val keys: DedupKeys,
) {
    /** Подготовленное фото: полное (1280) и превью. */
    class Photo(val full: ByteArray, val preview: ByteArray, val width: Int, val height: Int)

    suspend fun photos(chatId: String, level: Int, peerId: String?, photos: List<Photo>): AttachResult {
        if (photos.isEmpty()) return AttachResult.Refused("nothing")
        if (photos.size > MAX_PHOTOS) return AttachResult.TooMany
        val personal = peerId != null
        val uploaded = photos.map { p ->
            val (id, key) = uploads.upload(p.full, "image/jpeg", if (personal) chatId else null, peerId) ?: return AttachResult.Offline
            OutMedia(id, key, "image/jpeg", p.width, p.height, p.full.size.toLong(), if (personal) p.preview else ByteArray(0))
        }
        return enqueue(chatId, level, if (personal) bodies.album(uploaded) else bodies.inlineImages(uploaded))
    }

    /** Файл: в личной — почтальоном; в группе файлов нет — только ссылкой (Решения §2). */
    suspend fun file(chatId: String, level: Int, peerId: String, self: Boolean, bytes: ByteArray, mime: String, name: String): AttachResult {
        if (bytes.isEmpty()) return AttachResult.Refused("nothing")
        if (bytes.size > if (self) MAX_SELF_BYTES else MAX_FILE_BYTES) return AttachResult.Refused("large")
        val (id, key) = uploads.upload(bytes, mime, chatId, peerId) ?: return AttachResult.Offline
        return enqueue(chatId, level, bodies.file(OutMedia(id, key, mime, size = bytes.size.toLong(), name = name)))
    }

    fun contact(chatId: String, level: Int, name: String, phone: String, userId: String = ""): AttachResult =
        enqueue(chatId, level, bodies.contact(name, phone, userId))

    fun place(chatId: String, level: Int, lat: Double, lon: Double, label: String): AttachResult =
        enqueue(chatId, level, bodies.place(lat, lon, label))

    fun link(chatId: String, level: Int, url: String, title: String = "", cloudKind: String = ""): AttachResult =
        enqueue(chatId, level, bodies.link(url, title, cloudKind))

    private fun enqueue(chatId: String, level: Int, body: ByteArray): AttachResult {
        val key = keys.newKey()
        queue.enqueue(key, chatId, body, level, 0, KIND_TEXT)
        return AttachResult.Queued(key)
    }

    companion object {
        /** До 10 фото в одном сообщении (Решения §3). */
        const val MAX_PHOTOS = 10

        /** Файл другому — до 10 МБ, себе — до 50 МБ (Решения §6). */
        const val MAX_FILE_BYTES = 10 * 1024 * 1024
        const val MAX_SELF_BYTES = 50 * 1024 * 1024
    }
}
