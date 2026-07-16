package io.tima.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import io.tima.app.platform.AndroidAppContext
import io.tima.app.platform.AndroidImagePicker
import io.tima.app.platform.PickedImage
import io.tima.app.session.initSessionDir
import kotlinx.coroutines.CompletableDeferred

class MainActivity : ComponentActivity() {

    private var pendingPick: CompletableDeferred<PickedImage?>? = null

    private val imagePicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val picked = uri?.let {
            contentResolver.openInputStream(it)?.use { stream -> stream.readBytes() }?.let { bytes ->
                PickedImage(bytes, contentResolver.getType(it) ?: "image/jpeg")
            }
        }
        pendingPick?.complete(picked)
        pendingPick = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidAppContext.app = applicationContext
        initSessionDir(applicationContext.filesDir)
        AndroidImagePicker.pick = {
            val deferred = CompletableDeferred<PickedImage?>()
            pendingPick = deferred
            imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            deferred.await()
        }
        setContent { App() }
    }
}
