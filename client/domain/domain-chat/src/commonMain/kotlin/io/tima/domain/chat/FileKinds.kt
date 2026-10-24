package io.tima.domain.chat

/**
 * Что за файл — по имени и по первым байтам (заказчик 2026-10-24: файл по ссылке «Облака» — «увидеть
 * расширение по ссылке, дать описание, что за файл, предупреждать, если исполняемый или может быть
 * опасным»; «могут положить исполняемый в файл-картинку»). Вид по имени говорит отправитель, вид по
 * началу файла — сам файл: разошлись, и внутри программа — подмена.
 */
object FileKinds {

    /** Насколько опасен: программа, может быть опасным (архив, документ с макросами, страница), нет. */
    enum class Danger { NONE, RISKY, PROGRAM }

    /**
     * Вид файла. [key] — слово описания у словаря: `program-android`, `program-windows`, `program-mac`,
     * `program-linux`, `program-script`, `document-macro`, `archive`, `pdf`, `document`, `table`,
     * `slides`, `image`, `audio`, `video`, `page`, `unknown`.
     */
    data class Kind(val key: String, val danger: Danger)

    private val byExt: Map<String, String> = buildMap {
        for (e in listOf("apk", "aab", "xapk", "apks")) put(e, "program-android")
        for (e in listOf("exe", "msi", "com", "scr", "msix", "appx", "dll")) put(e, "program-windows")
        for (e in listOf("dmg", "pkg", "app")) put(e, "program-mac")
        for (e in listOf("deb", "rpm", "appimage", "run", "bin", "elf")) put(e, "program-linux")
        for (e in listOf("bat", "cmd", "ps1", "vbs", "vbe", "js", "jse", "wsf", "jar", "sh", "py", "hta", "lnk", "reg")) put(e, "program-script")
        for (e in listOf("docm", "xlsm", "pptm", "dotm", "xltm")) put(e, "document-macro")
        for (e in listOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "iso")) put(e, "archive")
        put("pdf", "pdf")
        for (e in listOf("doc", "docx", "odt", "rtf", "txt", "md")) put(e, "document")
        for (e in listOf("xls", "xlsx", "ods", "csv")) put(e, "table")
        for (e in listOf("ppt", "pptx", "odp")) put(e, "slides")
        for (e in listOf("jpg", "jpeg", "png", "gif", "webp", "heic", "bmp", "svg")) put(e, "image")
        for (e in listOf("mp3", "m4a", "ogg", "oga", "opus", "wav", "flac", "aac")) put(e, "audio")
        for (e in listOf("mp4", "m4v", "mov", "webm", "mkv", "avi", "3gp")) put(e, "video")
        for (e in listOf("html", "htm", "xhtml")) put(e, "page")
    }

    fun dangerOf(key: String): Danger = when {
        key.startsWith("program-") -> Danger.PROGRAM
        key == "document-macro" || key == "archive" || key == "page" -> Danger.RISKY
        else -> Danger.NONE
    }

    /** Имя файла из адреса: последняя часть пути, раскодированная; нет — пусто. */
    fun nameOf(url: String): String {
        val path = url.substringAfter("://", "").substringAfter('/', "").substringBefore('?').substringBefore('#')
        return decode(path.trimEnd('/').substringAfterLast('/'))
    }

    /** Расширение имени — строчными; нет — пусто. */
    fun extOf(name: String): String = name.substringAfterLast('.', "").lowercase().takeIf { it.length in 1..8 && '/' !in it }.orEmpty()

    /** Вид по имени. Двойное расширение не прячет программу: «фото.jpg.exe» — программа. */
    fun ofName(name: String): Kind {
        val key = byExt[extOf(name)] ?: "unknown"
        // Картинка по последнему расширению, а перед ним — программа: «счёт.exe.pdf» тоже подозрителен.
        val inner = byExt[extOf(name.substringBeforeLast('.', ""))]
        val danger = if (inner != null && dangerOf(inner) == Danger.PROGRAM && dangerOf(key) != Danger.PROGRAM) Danger.RISKY else dangerOf(key)
        return Kind(key, danger)
    }

    /**
     * Вид по началу файла — подписи форматов: `MZ` — программа Windows, ELF — Linux, `PK` с
     * `AndroidManifest.xml` или `classes.dex` — Android, иначе архив; PDF, JPEG, PNG, GIF, WebP, MP3, Ogg,
     * FLAC, MP4, HTML. Не узнали — `null`.
     */
    fun sniff(head: ByteArray): String? {
        fun at(i: Int, vararg b: Int) = head.size >= i + b.size && b.indices.all { head[i + it].toInt() and 0xFF == b[it] }
        fun ascii(i: Int, s: String) = at(i, *s.map { it.code }.toIntArray())
        val text by lazy { head.decodeToString().lowercase() }
        return when {
            ascii(0, "MZ") -> "program-windows"
            at(0, 0x7F, 0x45, 0x4C, 0x46) -> "program-linux"
            at(0, 0xCA, 0xFE, 0xBA, 0xBE) || at(0, 0xCF, 0xFA, 0xED, 0xFE) || at(0, 0xFE, 0xED, 0xFA, 0xCF) -> "program-mac"
            ascii(0, "#!") -> "program-script"
            ascii(0, "PK") -> if ("androidmanifest.xml" in text || "classes.dex" in text) "program-android" else "archive"
            ascii(0, "Rar!") || at(0, 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) || at(0, 0x1F, 0x8B) -> "archive"
            ascii(0, "%PDF") -> "pdf"
            at(0, 0xFF, 0xD8, 0xFF) || at(0, 0x89, 0x50, 0x4E, 0x47) || ascii(0, "GIF8") || (ascii(0, "RIFF") && ascii(8, "WEBP")) -> "image"
            ascii(0, "ID3") || at(0, 0xFF, 0xFB) || at(0, 0xFF, 0xF3) || ascii(0, "OggS") || ascii(0, "fLaC") || (ascii(0, "RIFF") && ascii(8, "WAVE")) -> "audio"
            ascii(4, "ftyp") || at(0, 0x1A, 0x45, 0xDF, 0xA3) -> "video"
            text.trimStart().let { it.startsWith("<!doctype html") || it.startsWith("<html") } -> "page"
            else -> null
        }
    }

    /**
     * Итог: вид и опасность — по имени и, если пришло, по началу файла. Начало опаснее имени — верим
     * началу и говорим о подмене ([Verdict.disguised]).
     */
    data class Verdict(val kind: Kind, val disguised: Boolean)

    fun verdict(name: String, head: ByteArray?): Verdict {
        val named = ofName(name)
        val sniffed = head?.let(::sniff) ?: return Verdict(named, false)
        val real = Kind(sniffed, dangerOf(sniffed))
        // Документ Office (docx, xlsx…) — тоже PK-архив: это не подмена.
        val office = sniffed == "archive" && named.key in setOf("document", "table", "slides", "document-macro")
        return when {
            office -> Verdict(named, false)
            real.danger.ordinal > named.danger.ordinal -> Verdict(real, true)
            else -> Verdict(named, false)
        }
    }

    /**
     * По ссылке файл, а не страница (заказчик 2026-10-24, 2а#): сайт назвал имя файла (`Content-Disposition`)
     * или отдаёт не HTML; не ответил — по окончанию адреса (`.apk`, `.pdf`…). Страница и неизвестное
     * окончание — не файл.
     */
    fun isFile(url: String, peek: CloudPeek?): Boolean {
        if (peek != null) {
            if (peek.name != null) return true
            val type = peek.type?.lowercase()
            if (type != null) return !(type.startsWith("text/html") || type.startsWith("application/xhtml"))
        }
        val key = ofName(nameOf(url)).key
        return key != "unknown" && key != "page"
    }

    /**
     * Имя для «Сохранить как» (заказчик 2026-10-24): имя от сервера может нести путь («../../x»), служебные
     * знаки и чужое расширение — пути и служебных знаков нет, длина разумная; подмена — к имени
     * дописывается настоящее расширение («котик.jpg.exe»): система и человек видят, что это программа.
     */
    fun safeName(name: String, verdict: Verdict?): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
            .filter { it.code >= 32 && it !in "<>:\"|?*" }
            .trim().trimStart('.').take(120)
            .ifBlank { "file" }
        val real = verdict?.takeIf { it.disguised }?.kind?.key?.let(REAL_EXT::get) ?: return base
        return if (extOf(base) == real) base else "$base.$real"
    }

    private val REAL_EXT = mapOf(
        "program-windows" to "exe", "program-android" to "apk", "program-linux" to "bin", "program-mac" to "bin",
        "program-script" to "sh", "archive" to "zip", "pdf" to "pdf", "page" to "html",
    )

    private fun decode(s: String): String {
        val bytes = ArrayList<Byte>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (v != null) {
                    bytes += v.toByte()
                    i += 3
                    continue
                }
            }
            c.toString().encodeToByteArray().forEach { bytes += it }
            i++
        }
        return bytes.toByteArray().decodeToString()
    }
}

/**
 * Начало файла по ссылке «Облака» — для окна файла у получателя: адрес после переходов, тип и размер от
 * сервера, имя из заголовка, первые байты.
 */
class CloudPeek(val url: String, val type: String?, val size: Long?, val name: String?, val head: ByteArray)
