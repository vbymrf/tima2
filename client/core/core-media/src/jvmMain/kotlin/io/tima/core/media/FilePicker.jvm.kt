package io.tima.core.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/** ПК: файловый диалог AWT — в фоновом потоке, чтобы не замирала композиция. */
@Composable
actual fun rememberFilePicker(kind: FileKind, onPicked: (PickedFile?) -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    return {
        scope.launch {
            val picked = withContext(Dispatchers.IO) {
                val dialog = FileDialog(null as Frame?, "Файл", FileDialog.LOAD)
                val allowed: (String) -> Boolean = when (kind) {
                    FileKind.ANY -> { _ -> true }
                    FileKind.DOCUMENT -> { n -> FileRules.mimeOf(n) in FileRules.DOCUMENT_MIMES }
                    FileKind.AUDIO -> { n -> FileRules.mimeOf(n).startsWith("audio/") }
                    FileKind.VIDEO -> { n -> FileRules.mimeOf(n).startsWith("video/") }
                }
                dialog.setFilenameFilter { _, name -> allowed(name) }
                dialog.isVisible = true
                val dir = dialog.directory
                val file = dialog.file
                if (dir == null || file == null) null else File(dir, file).let { PickedFile(it.readBytes(), FileRules.mimeOf(it.name), it.name) }
            }
            onPicked(picked)
        }
    }
}

/** ПК: файловый диалог с выбором нескольких картинок. */
@Composable
actual fun rememberPhotosPicker(max: Int, onPicked: (List<PickedImage>) -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    return {
        scope.launch {
            val picked = withContext(Dispatchers.IO) {
                val dialog = FileDialog(null as Frame?, "Фото", FileDialog.LOAD)
                dialog.isMultipleMode = true
                dialog.setFilenameFilter { _, name -> FileRules.mimeOf(name).startsWith("image/") }
                dialog.isVisible = true
                dialog.files.orEmpty().take(max).map { PickedImage(it.readBytes(), FileRules.mimeOf(it.name)) }
            }
            onPicked(picked)
        }
    }
}

/** ПК: «Сохранить как» — файловый диалог сохранения. */
@Composable
actual fun rememberFileSaver(onDone: (Boolean) -> Unit): (name: String, mime: String, bytes: ByteArray) -> Unit {
    val scope = rememberCoroutineScope()
    return { name, _, bytes ->
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                val dialog = FileDialog(null as Frame?, "Сохранить как", FileDialog.SAVE)
                dialog.file = name
                dialog.isVisible = true
                val dir = dialog.directory
                val file = dialog.file
                if (dir == null || file == null) false else runCatching { File(dir, file).writeBytes(bytes); true }.getOrDefault(false)
            }
            onDone(ok)
        }
    }
}

/** ПК: место — файловый диалог; пишется кусками; дописанный файл помечается как скачанный из интернета. */
@Composable
actual fun rememberSaveTarget(onTarget: (SaveTarget?) -> Unit): (name: String, mime: String, source: String) -> Unit {
    val scope = rememberCoroutineScope()
    return { name, _, source ->
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                val dialog = FileDialog(null as Frame?, "Сохранить как", FileDialog.SAVE)
                dialog.file = name
                dialog.isVisible = true
                val dir = dialog.directory
                val chosen = dialog.file
                if (dir == null || chosen == null) null else File(dir, chosen)
            }
            val out = file?.let { runCatching { java.io.FileOutputStream(it) }.getOrNull() }
            if (file == null || out == null) {
                onTarget(null)
                return@launch
            }
            onTarget(object : SaveTarget {
                override fun write(bytes: ByteArray, count: Int) = out.write(bytes, 0, count)
                override fun finish(ok: Boolean) {
                    runCatching { out.close() }
                    if (ok) markFromInternet(file, source) else runCatching { file.delete() }
                }
            })
        }
    }
}

/** ПК: системной загрузки нет — пишет сама TIMA ([rememberSaveTarget]). */
@Composable
actual fun rememberSystemDownload(): ((url: String, name: String, mime: String) -> Boolean)? = null

/**
 * Пометка «скачано из интернета» (Zone.Identifier, зона 3) — как ставят браузеры и почта: при запуске
 * программы Windows спросит, а SmartScreen проверит. Не Windows или файловая система без потоков — без
 * пометки. `true` — поставлена.
 */
internal fun markFromInternet(file: File, source: String): Boolean {
    if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return false
    val host = source.substringBefore('?').substringBefore('#')
    return runCatching {
        java.io.FileOutputStream(file.absolutePath + ":Zone.Identifier").use {
            it.write("[ZoneTransfer]\r\nZoneId=3\r\nHostUrl=$host\r\n".toByteArray())
        }
        true
    }.getOrDefault(false)
}

@Composable
actual fun rememberFolderSaver(onDone: (Int) -> Unit): (List<SavedFile>) -> Unit {
    val scope = rememberCoroutineScope()
    return { files ->
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                val chooser = javax.swing.JFileChooser().apply {
                    dialogTitle = "Сохранить в папку"
                    fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
                }
                if (chooser.showSaveDialog(null) != javax.swing.JFileChooser.APPROVE_OPTION) return@withContext 0
                val dir = chooser.selectedFile ?: return@withContext 0
                files.count { f -> runCatching { free(dir, f.name).writeBytes(f.bytes); true }.getOrDefault(false) }
            }
            onDone(saved)
        }
    }
}

/** Имя, которого в папке ещё нет: «фото.jpg», «фото (2).jpg»… */
private fun free(dir: File, name: String): File {
    var file = File(dir, name)
    var n = 2
    val base = name.substringBeforeLast('.')
    val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
    while (file.exists()) file = File(dir, "$base ($n)$ext").also { n++ }
    return file
}
