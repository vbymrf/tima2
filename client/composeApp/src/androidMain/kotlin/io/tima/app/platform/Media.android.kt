package io.tima.app.platform

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/** Мост к Photo Picker: реализацию ставит MainActivity (Activity Result API). */
object AndroidImagePicker {
    var pick: (suspend () -> PickedImage?)? = null
}

actual suspend fun pickImage(): PickedImage? = AndroidImagePicker.pick?.invoke()

actual fun decodeImage(bytes: ByteArray): ImageBitmap? =
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
