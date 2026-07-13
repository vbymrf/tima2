package io.tima.app.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image

actual suspend fun pickImage(): PickedImage? = withContext(Dispatchers.IO) {
    val dialog = FileDialog(null as Frame?, "Выбор изображения", FileDialog.LOAD)
    dialog.setFilenameFilter { _, name ->
        name.lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") || it.endsWith(".webp") }
    }
    dialog.isVisible = true // блокирует до выбора — поэтому Dispatchers.IO
    val dir = dialog.directory ?: return@withContext null
    val name = dialog.file ?: return@withContext null
    val file = File(dir, name)
    val mime = when (file.extension.lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> "image/jpeg"
    }
    PickedImage(file.readBytes(), mime)
}

actual fun decodeImage(bytes: ByteArray): ImageBitmap? = try {
    Image.makeFromEncoded(bytes).toComposeImageBitmap()
} catch (_: Throwable) {
    null
}
