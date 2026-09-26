package io.tima.core.media

import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import java.io.File

actual val systemSoundsAvailable: Boolean = true

/**
 * Android: системный выбор мелодий — тот же список, что в настройках телефона.
 *
 * Выбрали «по умолчанию» — это не адрес мелодии, а «как в системе», и так и сохраняется:
 * человек, сменивший мелодию телефона, ждёт, что сменится и здесь.
 */
@Composable
actual fun rememberSystemSoundPicker(use: SoundUse, onPicked: (SoundPick?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val type = if (use == SoundUse.Ring) RingtoneManager.TYPE_RINGTONE else RingtoneManager.TYPE_NOTIFICATION
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode != android.app.Activity.RESULT_OK || data == null) {
            onPicked(null)
            return@rememberLauncherForActivityResult
        }
        val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) {
            data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        }
        if (uri == null || RingtoneManager.isDefault(uri)) {
            onPicked(SoundPick.Default)
            return@rememberLauncherForActivityResult
        }
        val title = runCatching { RingtoneManager.getRingtone(context, uri)?.getTitle(context) }.getOrNull()
            ?: uri.lastPathSegment.orEmpty()
        onPicked(SoundPick.System(uri.toString(), title))
    }
    return {
        launcher.launch(
            Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, type)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                // «Без звука» — отдельной кнопкой у нас, а не пунктом системного списка:
                // так он одинаков для стандартной мелодии и своего файла.
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false),
        )
    }
}

/**
 * Android: системный выбор файла, копия — в `files/sounds` приложения.
 *
 * Разрешения на чтение хранилища не нужно: выбор отдаёт доступ к одному файлу.
 */
@Composable
actual fun rememberSoundFilePicker(name: String, onPicked: (SoundPick?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) {
            onPicked(null)
            return@rememberLauncherForActivityResult
        }
        val resolver = context.contentResolver
        var display = ""
        var size = -1L
        runCatching {
            resolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val s = c.getColumnIndex(OpenableColumns.SIZE)
                    if (n >= 0) display = c.getString(n).orEmpty()
                    if (s >= 0) size = c.getLong(s)
                }
            }
        }
        val ext = display.substringAfterLast('.', "").lowercase()
        if (ext !in SoundLimits.EXTENSIONS) {
            onPicked(SoundPick.BadType)
            return@rememberLauncherForActivityResult
        }
        if (size > SoundLimits.MAX_BYTES) {
            onPicked(SoundPick.TooBig)
            return@rememberLauncherForActivityResult
        }
        val dir = File(context.filesDir, "sounds").apply { mkdirs() }
        // Прежний файл того же назначения — прочь: иначе папка копила бы все выбранные.
        dir.listFiles { f -> f.nameWithoutExtension == name }?.forEach { it.delete() }
        val target = File(dir, "$name.$ext")
        val copied = runCatching {
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { out -> input.copyTo(out) }
            }
            target.length() in 1..SoundLimits.MAX_BYTES
        }.getOrDefault(false)
        if (!copied) {
            target.delete()
            onPicked(if (target.length() > SoundLimits.MAX_BYTES) SoundPick.TooBig else SoundPick.BadType)
            return@rememberLauncherForActivityResult
        }
        onPicked(SoundPick.File(target.absolutePath, display.substringBeforeLast('.')))
    }
    return { launcher.launch("audio/*") }
}
