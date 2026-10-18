package io.tima.core.media

import androidx.compose.runtime.Composable

/** Выбранный файл: байты, вид и имя — имя уходит в сообщение (Решения §6). */
class PickedFile(val bytes: ByteArray, val mime: String, val name: String)

/** Что выбирать (ВЫБОР-РЕАЛИЗАЦИИ §11.2а): любой файл, документы, аудио, видео. */
enum class FileKind { ANY, DOCUMENT, AUDIO, VIDEO }

/**
 * Выбор файла системным окном: на Android — `ACTION_OPEN_DOCUMENT` (разрешения не нужно, приложение
 * получает только выбранное), на ПК — файловый диалог. `null` — человек передумал.
 */
@Composable
expect fun rememberFilePicker(kind: FileKind, onPicked: (PickedFile?) -> Unit): () -> Unit

/** Несколько фото сразу — до [max] (Решения §3: до 10 в одном сообщении). Пусто — передумал. */
@Composable
expect fun rememberPhotosPicker(max: Int, onPicked: (List<PickedImage>) -> Unit): () -> Unit

/**
 * «Сохранить как» (Решения §6): копия туда, куда укажет человек, — правилам хранения TIMa она уже
 * не подчиняется. `true` в [onDone] — сохранено.
 */
@Composable
expect fun rememberFileSaver(onDone: (Boolean) -> Unit): (name: String, mime: String, bytes: ByteArray) -> Unit

/**
 * Запрет и предупреждение по имени файла (Решения §6): исполняемые, `.html`, `.svg` — нельзя;
 * архивы и `.apk` — можно, с предупреждением.
 */
object FileRules {
    private val FORBIDDEN = setOf("exe", "msi", "bat", "cmd", "com", "scr", "ps1", "vbs", "js", "jar", "sh", "html", "htm", "xhtml", "svg")
    private val WARN = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "apk", "aab")

    fun forbidden(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in FORBIDDEN
    fun warned(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in WARN

    /** Документы для пункта «Документ»: PDF, Word, Excel, PowerPoint, OpenDocument, текст. */
    val DOCUMENT_MIMES = listOf(
        "application/pdf", "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.ms-powerpoint", "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.oasis.opendocument.text", "application/vnd.oasis.opendocument.spreadsheet",
        "application/vnd.oasis.opendocument.presentation", "text/plain",
    )

    /** Вид файла по расширению — где система его не назвала (ПК). */
    fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "application/pdf"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "odt" -> "application/vnd.oasis.opendocument.text"
        "txt" -> "text/plain"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "ogg", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        "mp4" -> "video/mp4"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "zip" -> "application/zip"
        "apk" -> "application/vnd.android.package-archive"
        else -> "application/octet-stream"
    }
}
