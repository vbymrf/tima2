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
