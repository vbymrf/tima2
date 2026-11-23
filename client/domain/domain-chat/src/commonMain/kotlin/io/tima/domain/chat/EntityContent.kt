package io.tima.domain.chat

/**
 * «Содержимое» сущности (doc_mig/ПЛАН-(СЦ)-СОЦИУМА.md СЦ20, заказчик 2026-11-23): вложения переписки группы и записей
 * канала по подразделам. Собирается только с устройства; первое открытие вкладки разбирает сохранённое, дальше —
 * только новое (решение «в»).
 */
enum class ContentSection(val wire: String) {
    Photo("photo"), Video("video"), Music("music"), Voice("voice"), File("file"), Link("link");

    companion object {
        fun fromWire(wire: String): ContentSection? = entries.firstOrNull { it.wire == wire }
    }
}

/** Вложение: в каком сообщении, какое по счёту, когда; у файла — медиа и ключ, у ссылки — адрес. */
class ContentItem(
    val section: ContentSection,
    val messageId: Long,
    val index: Int,
    val atMs: Long,
    val name: String = "",
    val size: Long = 0,
    val mediaId: String = "",
    val key: ByteArray = ByteArray(0),
    val mime: String = "",
    val url: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long = 0,
    val title: String = "",
    val authorId: String = "",
) {
    /** Медиа для показа тем же пузырём, что в переписке. */
    fun media(): RichMedia = RichMedia(mediaId, key, mime, width, height, name, size, voice = section == ContentSection.Voice, durationMs = durationMs)
}

/** Хранилище «Содержимого» на устройстве. */
interface ContentStore {
    suspend fun items(entityKey: String): List<ContentItem>

    /** До какого сообщения разобрано; −1 — ни разу. */
    suspend fun scannedUpTo(entityKey: String): Long
    suspend fun put(entityKey: String, items: List<ContentItem>, upTo: Long)
    suspend fun drop(entityKey: String, messageId: Long)
}

/** Разбор сообщения на вложения (СЦ20): фото, видео, музыка, голосовые, файлы; ссылки — блоками и адресами в тексте. */
object ContentIndex {
    private val URL = Regex("""https?://[^\s<>()]+""")

    fun of(messageId: Long, atMs: Long, rich: RichContent?, text: String?, authorId: String = ""): List<ContentItem> {
        val out = mutableListOf<ContentItem>()
        var idx = 0
        val links = mutableSetOf<String>()
        if (rich != null) {
            // Картинка карточки ссылки — часть ссылки, не фото.
            val cardImages = rich.blocks.filterIsInstance<RichBlock.Link>().map { it.media }.filter { it >= 0 }.toSet()
            for ((i, m) in rich.media.withIndex()) {
                if (i in cardImages) continue
                val section = when {
                    m.voice -> ContentSection.Voice
                    m.mime.startsWith("image/") -> ContentSection.Photo
                    m.mime.startsWith("video/") -> ContentSection.Video
                    m.mime.startsWith("audio/") -> ContentSection.Music
                    else -> ContentSection.File
                }
                out += ContentItem(section, messageId, idx++, atMs, m.name, m.size, m.mediaId, m.key, m.mime, "", m.width, m.height, m.durationMs, authorId = authorId)
            }
            for (b in rich.blocks) {
                val (url, title, section) = when (b) {
                    is RichBlock.Link -> Triple(b.url, b.title, ContentSection.Link)
                    is RichBlock.Cloud -> Triple(b.url, b.title, ContentSection.Link)
                    is RichBlock.Video -> Triple(b.url, b.description, ContentSection.Video)
                    else -> continue
                }
                if (url.isBlank() || !links.add(url)) continue
                out += ContentItem(section, messageId, idx++, atMs, url = url, title = title, authorId = authorId)
            }
        }
        text?.let { t ->
            for (m in URL.findAll(t)) {
                val url = m.value.trimEnd('.', ',', ';', ':', '!', '?', '»', '"')
                if (!links.add(url)) continue
                out += ContentItem(ContentSection.Link, messageId, idx++, atMs, url = url, authorId = authorId)
            }
        }
        return out
    }
}
