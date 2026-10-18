package io.tima.core.media

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** Android: системное окно выбора — приложение получает только выбранный файл, без разрешений. */
@Composable
actual fun rememberFilePicker(kind: FileKind, onPicked: (PickedFile?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) {
            onPicked(null)
            return@rememberLauncherForActivityResult
        }
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "file"
        val bytes = runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        val mime = resolver.getType(uri) ?: FileRules.mimeOf(name)
        onPicked(bytes?.let { PickedFile(it, mime, name) })
    }
    val types = when (kind) {
        FileKind.ANY -> arrayOf("*/*")
        FileKind.DOCUMENT -> FileRules.DOCUMENT_MIMES.toTypedArray()
        FileKind.AUDIO -> arrayOf("audio/*")
        FileKind.VIDEO -> arrayOf("video/*")
    }
    return { launcher.launch(types) }
}

/** Android: системный выбор фото, несколько сразу; на старых Android он сам откатывается на окно выбора. */
@Composable
actual fun rememberPhotosPicker(max: Int, onPicked: (List<PickedImage>) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(max.coerceAtLeast(2))) { uris ->
        val resolver = context.contentResolver
        onPicked(
            uris.take(max).mapNotNull { uri ->
                val bytes = runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() ?: return@mapNotNull null
                PickedImage(bytes, resolver.getType(uri) ?: "image/jpeg")
            },
        )
    }
    return { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}

/** Android: «Сохранить как» — системное окно создания файла, байты пишутся туда. */
@Composable
actual fun rememberFileSaver(onDone: (Boolean) -> Unit): (name: String, mime: String, bytes: ByteArray) -> Unit {
    val context = LocalContext.current
    val pending = remember { mutableStateOf<ByteArray?>(null) }
    val mime = remember { mutableStateOf("application/octet-stream") }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri: Uri? ->
        val bytes = pending.value
        pending.value = null
        if (uri == null || bytes == null) {
            onDone(false)
            return@rememberLauncherForActivityResult
        }
        val ok = runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } != null }.getOrDefault(false)
        onDone(ok)
    }
    return { name, type, bytes ->
        pending.value = bytes
        mime.value = type
        launcher.launch(name)
    }
}
