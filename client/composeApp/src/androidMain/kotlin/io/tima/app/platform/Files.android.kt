package io.tima.app.platform

import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Мост к системному выбору файла; реализацию ставит MainActivity (Activity Result API). */
object AndroidFilePicker {
    var pick: (suspend () -> PickedFile?)? = null
}

actual suspend fun pickFile(): PickedFile? = AndroidFilePicker.pick?.invoke()

actual suspend fun openFile(bytes: ByteArray, name: String, mime: String): Unit = withContext(Dispatchers.IO) {
    val ctx = AndroidAppContext.app
    val dir = File(ctx.cacheDir, "files").apply { mkdirs() }
    val file = File(dir, name.ifBlank { "file" })
    file.writeBytes(bytes)
    // Тот же FileProvider, что для обновления (authority ${applicationId}.updates, cache-path ".")
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".updates", file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime.ifBlank { "*/*" })
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    ctx.startActivity(intent)
}
