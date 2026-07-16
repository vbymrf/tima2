package io.tima.app.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File
import java.nio.file.Files
import javax.swing.JFileChooser

actual suspend fun pickFile(): PickedFile? = withContext(Dispatchers.IO) {
    val chooser = JFileChooser()
    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        val f = chooser.selectedFile
        PickedFile(f.readBytes(), f.name, Files.probeContentType(f.toPath()) ?: "application/octet-stream")
    } else null
}

actual suspend fun openFile(bytes: ByteArray, name: String, mime: String): Unit = withContext(Dispatchers.IO) {
    val safe = name.ifBlank { "file" }.replace(Regex("[^\\w.\\-]"), "_")
    val file = File.createTempFile("tima-", "-$safe")
    file.writeBytes(bytes)
    if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(file)
}
