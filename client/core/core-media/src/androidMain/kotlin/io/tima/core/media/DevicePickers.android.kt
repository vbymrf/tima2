package io.tima.core.media

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

/** Android: системная камера; снимок — во временный файл, прочитан и стёрт сразу. */
@Composable
actual fun rememberCameraShot(onShot: (PickedImage?) -> Unit): (() -> Unit)? {
    val context = LocalContext.current
    val file = remember { File(File(context.cacheDir, "camera").apply { mkdirs() }, "shot.jpg") }
    val uri = remember { FileProvider.getUriForFile(context, "${context.packageName}.files", file) }
    val take = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val bytes = if (ok) runCatching { file.readBytes() }.getOrNull() else null
        file.delete()
        onShot(bytes?.let { PickedImage(it, "image/jpeg") })
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) take.launch(uri) else onShot(null)
    }
    return {
        // Камера заявлена приложением для звонков — без выданного разрешения чужую камеру не вызвать.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            take.launch(uri)
        } else {
            ask.launch(Manifest.permission.CAMERA)
        }
    }
}

/** Android: один номер из системной книги — приложение получает только выбранную строку. */
@Composable
actual fun rememberContactPicker(onPicked: (PickedContact?) -> Unit): (() -> Unit)? {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (uri == null) {
            onPicked(null)
            return@rememberLauncherForActivityResult
        }
        val picked = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, null,
            )?.use { c -> if (c.moveToFirst()) PickedContact(c.getString(0).orEmpty(), c.getString(1).orEmpty()) else null }
        }.getOrNull()
        onPicked(picked)
    }
    return {
        launcher.launch(Intent(Intent.ACTION_PICK).setType(ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE))
    }
}

/** Android: последнее известное место, иначе одно новое; разрешение — при нажатии. */
@Composable
actual fun rememberMyPlace(onLocated: (Pair<Double, Double>?) -> Unit): (() -> Unit)? {
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) locate(context, onLocated) else onLocated(null)
    }
    return {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fine || coarse) locate(context, onLocated)
        else ask.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
}

@SuppressLint("MissingPermission")
private fun locate(context: Context, onLocated: (Pair<Double, Double>?) -> Unit) {
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    if (manager == null) {
        onLocated(null)
        return
    }
    val providers = manager.getProviders(true)
    val known = providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
        .maxByOrNull { it.time }
    if (known != null && System.currentTimeMillis() - known.time < FRESH_MS) {
        onLocated(known.latitude to known.longitude)
        return
    }
    val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).firstOrNull { it in providers }
    if (provider == null) {
        onLocated(known?.let { it.latitude to it.longitude })
        return
    }
    @Suppress("DEPRECATION")
    runCatching {
        manager.requestSingleUpdate(provider, { l: Location -> onLocated(l.latitude to l.longitude) }, Looper.getMainLooper())
    }.onFailure { onLocated(known?.let { it.latitude to it.longitude }) }
}

/** Последнее известное место свежее этого — годится без нового запроса. */
private const val FRESH_MS = 5 * 60_000L
