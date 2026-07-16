package io.tima.app

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import io.tima.app.platform.AndroidAppContext
import io.tima.app.platform.AndroidFilePicker
import io.tima.app.platform.AndroidImagePicker
import io.tima.app.platform.AndroidPermissions
import io.tima.app.platform.PickedFile
import io.tima.app.platform.PickedImage
import io.tima.app.session.initSessionDir
import kotlinx.coroutines.CompletableDeferred

class MainActivity : ComponentActivity() {

    private var pendingPick: CompletableDeferred<PickedImage?>? = null
    private var pendingPerm: CompletableDeferred<Boolean>? = null
    private var pendingFile: CompletableDeferred<PickedFile?>? = null

    private val imagePicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val picked = uri?.let {
            contentResolver.openInputStream(it)?.use { stream -> stream.readBytes() }?.let { bytes ->
                PickedImage(bytes, contentResolver.getType(it) ?: "image/jpeg")
            }
        }
        pendingPick?.complete(picked)
        pendingPick = null
    }

    private val filePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val picked = uri?.let {
            val bytes = contentResolver.openInputStream(it)?.use { s -> s.readBytes() }
            if (bytes == null) null
            else PickedFile(bytes, displayName(it), contentResolver.getType(it) ?: "application/octet-stream")
        }
        pendingFile?.complete(picked)
        pendingFile = null
    }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        pendingPerm?.complete(result.values.all { it })
        pendingPerm = null
    }

    /** Человекочитаемое имя файла из content:// URI. */
    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) c.getString(idx)?.let { return it }
            }
        }
        return uri.lastPathSegment ?: "file"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidAppContext.app = applicationContext
        io.tima.app.diag.AppDiagnostics.platform = "Android"
        initSessionDir(applicationContext.filesDir)
        AndroidImagePicker.pick = {
            val deferred = CompletableDeferred<PickedImage?>()
            pendingPick = deferred
            imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            deferred.await()
        }
        AndroidFilePicker.pick = {
            val deferred = CompletableDeferred<PickedFile?>()
            pendingFile = deferred
            filePicker.launch("*/*")
            deferred.await()
        }
        AndroidPermissions.request = { perms ->
            val deferred = CompletableDeferred<Boolean>()
            pendingPerm = deferred
            permLauncher.launch(perms.toTypedArray())
            deferred.await()
        }
        setContent { App() }
    }
}
