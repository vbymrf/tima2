package io.tima.core.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * ПК: файловый диалог AWT. Модальный и блокирующий, поэтому в фоновом потоке —
 * иначе замирает вся композиция, пока человек ищет файл.
 */
@Composable
actual fun rememberImagePicker(onPicked: (PickedImage?) -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    return {
        scope.launch {
            val picked = withContext(Dispatchers.IO) {
                val dialog = FileDialog(null as Frame?, "Картинка для аватара", FileDialog.LOAD)
                dialog.setFilenameFilter { _, name -> name.lowercase().substringAfterLast('.') in EXTENSIONS }
                dialog.isVisible = true
                val dir = dialog.directory
                val file = dialog.file
                if (dir == null || file == null) {
                    null
                } else {
                    val f = File(dir, file)
                    PickedImage(f.readBytes(), mimeOf(f.extension))
                }
            }
            onPicked(picked)
        }
    }
}

private val EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")

private fun mimeOf(ext: String): String = when (ext.lowercase()) {
    "png" -> "image/png"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    "bmp" -> "image/bmp"
    else -> "image/jpeg"
}
