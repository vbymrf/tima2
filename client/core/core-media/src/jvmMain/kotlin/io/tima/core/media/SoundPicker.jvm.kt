package io.tima.core.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/** На ПК своего списка стандартных мелодий нет — кнопки «Из стандартных» не будет. */
actual val systemSoundsAvailable: Boolean = false

@Composable
actual fun rememberSystemSoundPicker(use: SoundUse, onPicked: (SoundPick?) -> Unit): () -> Unit =
    { onPicked(null) }

/**
 * ПК: файловый диалог, копия — в `%LOCALAPPDATA%\TIMA\sounds`. Диалог модальный, поэтому
 * в фоновом потоке — как у выбора картинки.
 */
@Composable
actual fun rememberSoundFilePicker(name: String, onPicked: (SoundPick?) -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    return {
        scope.launch {
            val picked = withContext(Dispatchers.IO) {
                val dialog = FileDialog(null as Frame?, "Звук", FileDialog.LOAD)
                dialog.setFilenameFilter { _, n -> n.lowercase().substringAfterLast('.') in SoundLimits.EXTENSIONS }
                dialog.isVisible = true
                val dir = dialog.directory
                val file = dialog.file
                if (dir == null || file == null) return@withContext null
                val source = File(dir, file)
                val ext = source.extension.lowercase()
                when {
                    ext !in SoundLimits.EXTENSIONS -> SoundPick.BadType
                    source.length() > SoundLimits.MAX_BYTES -> SoundPick.TooBig
                    else -> {
                        val base = System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home")
                        val target = File(File(File(base, "TIMA"), "sounds").apply { mkdirs() }, "$name.$ext")
                        target.parentFile.listFiles { f -> f.nameWithoutExtension == name }?.forEach { it.delete() }
                        runCatching { source.copyTo(target, overwrite = true) }
                            .map { SoundPick.File(it.absolutePath, source.nameWithoutExtension) }
                            .getOrDefault(SoundPick.BadType)
                    }
                }
            }
            onPicked(picked)
        }
    }
}
