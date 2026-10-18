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
