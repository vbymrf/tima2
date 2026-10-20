package io.tima.core.media

import androidx.compose.runtime.Composable

/** ПК: системного «Поделиться» нет — «Сохранить как». */
@Composable
actual fun rememberShareImage(): ((bytes: ByteArray, mime: String) -> Boolean)? = null

/** ПК: «Поделиться» нет — «Сохранить как». */
@Composable
actual fun rememberShareFile(): ((bytes: ByteArray, mime: String, name: String) -> Boolean)? = null

/** ПК: во временную папку и программой, назначенной системой. */
@Composable
actual fun rememberOpenFile(): ((bytes: ByteArray, mime: String, name: String) -> Boolean)? =
    if (!java.awt.Desktop.isDesktopSupported()) null else { bytes, _, name ->
        runCatching {
            val dir = java.io.File(System.getProperty("java.io.tmpdir"), "tima-open").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = java.io.File(dir, safeFileName(name))
            file.writeBytes(bytes)
            java.awt.Desktop.getDesktop().open(file)
            true
        }.getOrDefault(false)
    }
