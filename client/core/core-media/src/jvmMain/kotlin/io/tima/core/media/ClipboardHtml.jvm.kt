package io.tima.core.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor

/** ПК: системный буфер Java, вид `text/html`. */
@Composable
actual fun rememberClipboardHtml(): () -> String? = remember {
    {
        runCatching {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            val flavor = DataFlavor("text/html;class=java.lang.String")
            if (clipboard.isDataFlavorAvailable(flavor)) clipboard.getData(flavor) as? String else null
        }.getOrNull()
    }
}
