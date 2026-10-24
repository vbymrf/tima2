package io.tima.domain.chat

/**
 * Файл, уже загруженный и закрытый своим ключом, — для тела сообщения (Решения §3, §6).
 * [name] — имя файла вне пузыря. Превью в теле нет (ADR-0052): фото забирается целиком, превью
 * делает получатель.
 */
class OutMedia(
    val mediaId: String,
    val key: ByteArray,
    val mime: String,
    val width: Int = 0,
    val height: Int = 0,
    val size: Long = 0,
    val name: String = "",
    /** Голосовое (ПЛАН-(ВМ) §2.2): длительность и рисунок волны — в теле, до скачивания. */
    val voice: Boolean = false,
    val durationMs: Long = 0,
    val wave: ByteArray? = null,
)

/**
 * Тела сообщений-вложений (Решения §2–§6) — реализует `core-encryption` тем же конвейером, что
 * и текст. Порт, потому что разметка и `MessageBody` выше `core-encryption` не поднимаются.
 */
interface AttachmentBodies {
    /** Фото без текста — вне пузыря, целиком у почтальона (личная переписка). */
    fun album(photos: List<OutMedia>): ByteArray

    /** Файл вне пузыря (личная переписка). */
    fun file(file: OutMedia): ByteArray

    /** Голосовое — вне пузыря, в личной и в группе (ПЛАН-(ВМ) Р2). */
    fun voice(voice: OutMedia): ByteArray

    /** Картинки внутри сообщения — по одной в ряд во всю ширину (группа, канал). */
    fun inlineImages(images: List<OutMedia>): ByteArray

    fun contact(name: String, phone: String, userId: String): ByteArray

    fun place(lat: Double, lon: Double, label: String): ByteArray

    /** Ссылка; [cloudKind] не пусто — файл в облаке (`file`, `document`, `audio`, `video`). */
    fun link(url: String, title: String, cloudKind: String = ""): ByteArray
}

/**
 * Что сервер принимает (заказчик 2026-10-22, ПЛАН-(ИФ) ИФ2): пределы, сторона фото, запреты по
 * имени. Клиент соблюдает их до отправки — файл зашифрован, и сервер видит только размер. Числа
 * по умолчанию — решения (Решения §3, §6), пока сервер не ответил ни разу.
 */
data class MediaRules(
    val fileBytes: Long = 10L * 1024 * 1024,
    val selfBytes: Long = 50L * 1024 * 1024,
    /** Большая сторона фото отдельным файлом — 1920 (заказчик 2026-10-24; было 1280). */
    val photoSide: Int = 1920,
    val maxPhotos: Int = 10,
    val blockedNames: List<String> = emptyList(),
) {
    /** Имя под запретом сервера — по окончанию, без учёта регистра. */
    fun blocked(name: String): Boolean = blockedNames.any { name.lowercase().endsWith(it.lowercase()) }
}

/** Откуда правила: спросить сервер не чаще раза в сутки, иначе — запомненные (реализует `shared`). */
fun interface MediaRulesSource {
    suspend fun rules(): MediaRules
}

/** Порт загрузки: закрыть ключом и отдать в хранилище; почтальон — для личной переписки. */
interface AttachmentUploads {
    /** @return загруженный файл или `null` — нет сети, предел, квота. */
    suspend fun upload(bytes: ByteArray, mime: String, postmanChat: String?, postmanPeer: String?): Pair<String, ByteArray>?

    /**
     * Голосовое — файл на срок у сервера (ПЛАН-(ВМ) Р3): личная — [peerId], группа — [groupId].
     * @return номер и ключ или `null` — нет сети, предел.
     */
    suspend fun uploadVoice(bytes: ByteArray, chatId: String, peerId: String?, groupId: String?): Pair<String, ByteArray>? = null
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
 * Отправка вложений (Решения §2, §3, §6): фото — в личной без текста вне пузыря (целиком у
 * почтальона, превью делает получатель), в группе — картинками внутри сообщения; файлы — в личной почтальоном
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
    private val source: MediaRulesSource = MediaRulesSource { MediaRules() },
) {
    /** Правила сервера — на этот раз (ПЛАН-(ИФ) ИФ2). */
    suspend fun rules(): MediaRules = source.rules()

    /** Подготовленное фото: большая сторона — по правилам сервера (1920). */
    class Photo(val full: ByteArray, val width: Int, val height: Int)

    suspend fun photos(chatId: String, level: Int, peerId: String?, photos: List<Photo>): AttachResult {
        if (photos.isEmpty()) return AttachResult.Refused("nothing")
        if (photos.size > rules().maxPhotos) return AttachResult.TooMany
        val personal = peerId != null
        val uploaded = photos.map { p ->
            val (id, key) = uploads.upload(p.full, "image/jpeg", if (personal) chatId else null, peerId) ?: return AttachResult.Offline
            OutMedia(id, key, "image/jpeg", p.width, p.height, p.full.size.toLong())
        }
        return enqueue(chatId, level, if (personal) bodies.album(uploaded) else bodies.inlineImages(uploaded))
    }

    /** Файл: в личной — почтальоном; в группе файлов нет — только ссылкой (Решения §2). */
    suspend fun file(chatId: String, level: Int, peerId: String, self: Boolean, bytes: ByteArray, mime: String, name: String): AttachResult {
        if (bytes.isEmpty()) return AttachResult.Refused("nothing")
        val rules = rules()
        if (rules.blocked(name)) return AttachResult.Refused("type")
        if (bytes.size > if (self) rules.selfBytes else rules.fileBytes) return AttachResult.Refused("large")
        val (id, key) = uploads.upload(bytes, mime, chatId, peerId) ?: return AttachResult.Offline
        return enqueue(chatId, level, bodies.file(OutMedia(id, key, mime, size = bytes.size.toLong(), name = name)))
    }

    /**
     * Голосовое (ПЛАН-(ВМ) ВМ3): Opus в Ogg, длительность и волна. Личная — [peerId], группа —
     * [groupId]; в канале голосовых нет (Р19) — экран кнопки не показывает.
     */
    suspend fun voice(chatId: String, level: Int, peerId: String?, groupId: String?, ogg: ByteArray, durationMs: Long, wave: ByteArray): AttachResult {
        if (ogg.isEmpty()) return AttachResult.Refused("nothing")
        if (ogg.size > rules().fileBytes) return AttachResult.Refused("large")
        val (id, key) = uploads.uploadVoice(ogg, chatId, peerId, groupId) ?: return AttachResult.Offline
        return enqueue(chatId, level, bodies.voice(OutMedia(id, key, VOICE_MIME, size = ogg.size.toLong(), voice = true, durationMs = durationMs, wave = wave)))
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
        /** До 10 фото в одном сообщении (Решения §3) — выбор в системном окне; дальше решает сервер. */
        const val MAX_PHOTOS = 10

        /** Голосовое — Opus в Ogg (ПЛАН-(ВМ) Р1). */
        const val VOICE_MIME = "audio/ogg; codecs=opus"
    }
}
