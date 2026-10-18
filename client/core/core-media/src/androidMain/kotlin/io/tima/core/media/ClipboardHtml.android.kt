package io.tima.core.media

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** Android: первый элемент буфера, его HTML-вид (браузер, почта, офис кладут его рядом с текстом). */
@Composable
actual fun rememberClipboardHtml(): () -> String? {
    val context = LocalContext.current
    return remember(context) {
        {
            runCatching {
                val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
                clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.htmlText
            }.getOrNull()
        }
    }
}
