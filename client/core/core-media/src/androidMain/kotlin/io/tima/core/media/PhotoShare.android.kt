package io.tima.core.media

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File

/**
 * Android: картинка — во временный файл приложения и системное «Поделиться». Прежний файл стирается
 * перед новым: открытым на диске остаётся не больше одного, и только до следующего раза.
 */
@Composable
actual fun rememberShareImage(): ((bytes: ByteArray, mime: String) -> Boolean)? {
    val context = LocalContext.current
    return remember(context) {
        { bytes, mime ->
            runCatching {
                val dir = File(context.cacheDir, "share").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val file = File(dir, if (mime == "image/png") "photo.png" else "photo.jpg")
                file.writeBytes(bytes)
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                val send = Intent(Intent.ACTION_SEND)
                    .setType(mime)
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }.getOrDefault(false)
        }
    }
}

/** Файл во временную папку «share» — та же, что у фото: открытым остаётся один, до следующего раза. */
private fun shared(context: android.content.Context, bytes: ByteArray, name: String): android.net.Uri {
    val dir = File(context.cacheDir, "share").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }
    val file = File(dir, safeFileName(name))
    file.writeBytes(bytes)
    return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
}

@Composable
actual fun rememberShareFile(): ((bytes: ByteArray, mime: String, name: String) -> Boolean)? {
    val context = LocalContext.current
    return remember(context) {
        { bytes, mime, name ->
            runCatching {
                val send = Intent(Intent.ACTION_SEND)
                    .setType(mime.ifBlank { "application/octet-stream" })
                    .putExtra(Intent.EXTRA_STREAM, shared(context, bytes, name))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }.getOrDefault(false)
        }
    }
}

/** Android: программой, которая берётся за этот вид файла; такой нет — `false`. */
@Composable
actual fun rememberOpenFile(): ((bytes: ByteArray, mime: String, name: String) -> Boolean)? {
    val context = LocalContext.current
    return remember(context) {
        { bytes, mime, name ->
            runCatching {
                val view = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(shared(context, bytes, name), mime.ifBlank { "application/octet-stream" })
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(view)
                true
            }.getOrDefault(false)
        }
    }
}
