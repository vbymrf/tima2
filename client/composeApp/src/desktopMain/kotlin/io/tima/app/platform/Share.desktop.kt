package io.tima.app.platform

import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File

actual suspend fun shareText(title: String, text: String) {
    // В буфер обмена (можно сразу вставить)…
    runCatching {
        val sel = StringSelection(text)
        Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
    }
    // …и в файл рядом, открываем ассоциированным приложением
    runCatching {
        val f = File(System.getProperty("user.home"), "tima-diagnostics.txt")
        f.writeText(text)
        if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(f)
    }
}
