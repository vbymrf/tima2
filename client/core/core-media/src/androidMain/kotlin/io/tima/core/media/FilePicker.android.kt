package io.tima.core.media

import android.net.Uri
import android.provider.DocumentsContract
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

/** Android: место выбирает системное окно создания файла; пишется кусками в его поток. */
@Composable
actual fun rememberSaveTarget(onTarget: (SaveTarget?) -> Unit): (name: String, mime: String, source: String) -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri: Uri? ->
        val out = uri?.let { runCatching { context.contentResolver.openOutputStream(it) }.getOrNull() }
        if (uri == null || out == null) {
            onTarget(null)
            return@rememberLauncherForActivityResult
        }
        onTarget(object : SaveTarget {
            override fun write(bytes: ByteArray, count: Int) = out.write(bytes, 0, count)
            override fun finish(ok: Boolean) {
                runCatching { out.close() }
                if (!ok) runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
            }
        })
    }
    return { name, _, _ -> launcher.launch(name) }
}

/** Android: «Загрузчик» системы — в «Загрузки», ход в шторке. */
@Composable
actual fun rememberSystemDownload(): ((url: String, name: String, mime: String) -> Boolean)? {
    val context = LocalContext.current
    return remember(context) {
        { url, name, mime ->
            runCatching {
                val manager = context.getSystemService(android.app.DownloadManager::class.java) ?: return@runCatching false
                val request = android.app.DownloadManager.Request(Uri.parse(url))
                    .setTitle(name)
                    .setMimeType(mime)
                    .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, name)
                    .addRequestHeader("User-Agent", MEDIA_USER_AGENT)
                manager.enqueue(request)
                true
            }.getOrDefault(false)
        }
    }
}

@Composable
actual fun rememberFolderSaver(onDone: (Int) -> Unit): (List<SavedFile>) -> Unit {
    val context = LocalContext.current
    val pending = remember { mutableStateOf<List<SavedFile>>(emptyList()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree: Uri? ->
        val files = pending.value
        pending.value = emptyList()
        if (tree == null) {
            onDone(0)
            return@rememberLauncherForActivityResult
        }
        // Папка выбрана — файлы создаёт в ней система: одинаковые имена она сама разводит номером.
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        var saved = 0
        for (f in files) {
            val ok = runCatching {
                val uri = DocumentsContract.createDocument(context.contentResolver, parent, f.mime, f.name) ?: return@runCatching false
                context.contentResolver.openOutputStream(uri)?.use { it.write(f.bytes) } != null
            }.getOrDefault(false)
            if (ok) saved++
        }
        onDone(saved)
    }
    return { files ->
        pending.value = files
        launcher.launch(null)
    }
}
