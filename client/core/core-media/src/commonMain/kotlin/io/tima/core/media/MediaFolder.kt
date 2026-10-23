package io.tima.core.media

import okio.FileSystem
import okio.Path

/** Файл в папке медиа: имя на диске, размер, когда положен. */
data class MediaEntry(val name: String, val size: Long, val atMs: Long)

/**
 * Своя папка медиа на устройстве (Решения §6): фото и файлы вне пузыря личной переписки. Файл
 * лежит **как пришёл с сервера — закрытым своим ключом**; ключ — в зашифрованном теле сообщения,
 * поэтому и на диске он не читается без базы. Имя на диске — номер файла, латиницей; имя для
 * человека — в сообщении.
 *
 * Раздел [kind]: `photo` — фото вне пузыря, `file` — файлы, `voice` — голосовые (ПЛАН-(ВМ) Р17);
 * срок и предел у каждого свой («Память и трафик → Медиа и другие файлы»).
 */
interface MediaFolder {
    fun put(kind: String, name: String, bytes: ByteArray)
    fun get(kind: String, name: String): ByteArray?
    fun delete(kind: String, name: String)
    fun entries(kind: String): List<MediaEntry>

    /** Всё, что лежит в разделе, — «Очистить сейчас». */
    fun clear(kind: String) = entries(kind).forEach { delete(kind, it.name) }

    companion object {
        const val PHOTO = "photo"
        const val FILE = "file"
        const val VOICE = "voice"

        /** Папки нет (проверки, iOS до своей реализации): ничего не хранится. */
        val NONE: MediaFolder = object : MediaFolder {
            override fun put(kind: String, name: String, bytes: ByteArray) = Unit
            override fun get(kind: String, name: String): ByteArray? = null
            override fun delete(kind: String, name: String) = Unit
            override fun entries(kind: String): List<MediaEntry> = emptyList()
        }
    }
}

/**
 * Папка на диске: `<корень>/<раздел>/<имя>`. Платформа даёт файловую систему и корень — каталог
 * данных приложения у неё свой (Android — `filesDir`, ПК — каталог данных TIMA).
 */
class DiskMediaFolder(private val fs: FileSystem, private val root: Path, private val nowMs: () -> Long) : MediaFolder {

    /** Имя на диске — только латиница, цифры, `-` и `_`: номер файла, без пути и точек. */
    private fun safe(name: String): String = name.filter { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' }.ifEmpty { "_" }

    private fun dir(kind: String): Path = root / safe(kind)

    override fun put(kind: String, name: String, bytes: ByteArray) {
        val d = dir(kind)
        fs.createDirectories(d)
        val target = d / safe(name)
        // Сначала во временный, потом переименование: оборванная запись не выглядит целым файлом.
        val tmp = d / (safe(name) + ".part")
        fs.write(tmp) { write(bytes) }
        fs.atomicMove(tmp, target)
    }

    override fun get(kind: String, name: String): ByteArray? {
        val path = dir(kind) / safe(name)
        return if (fs.exists(path)) runCatching { fs.read(path) { readByteArray() } }.getOrNull() else null
    }

    override fun delete(kind: String, name: String) {
        fs.delete(dir(kind) / safe(name), mustExist = false)
    }

    override fun entries(kind: String): List<MediaEntry> {
        val d = dir(kind)
        if (!fs.exists(d)) return emptyList()
        return fs.list(d).filterNot { it.name.endsWith(".part") }.mapNotNull { p ->
            val meta = fs.metadataOrNull(p) ?: return@mapNotNull null
            MediaEntry(p.name, meta.size ?: 0, meta.lastModifiedAtMillis ?: nowMs())
        }
    }
}
