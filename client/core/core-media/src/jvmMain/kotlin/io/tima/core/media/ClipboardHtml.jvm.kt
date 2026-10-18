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

/** ПК: картинка буфера Java (`imageFlavor`) — в PNG. */
@Composable
actual fun rememberClipboardImage(): () -> PickedFile? = remember {
    {
        runCatching {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            if (!clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)) return@runCatching null
            val image = clipboard.getData(DataFlavor.imageFlavor) as? java.awt.Image ?: return@runCatching null
            val buffered = java.awt.image.BufferedImage(image.getWidth(null), image.getHeight(null), java.awt.image.BufferedImage.TYPE_INT_ARGB)
            buffered.createGraphics().apply { drawImage(image, 0, 0, null); dispose() }
            val out = java.io.ByteArrayOutputStream()
            javax.imageio.ImageIO.write(buffered, "png", out)
            PickedFile(out.toByteArray(), "image/png", "clipboard.png")
        }.getOrNull()
    }
}
