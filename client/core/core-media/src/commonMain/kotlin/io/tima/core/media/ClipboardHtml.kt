package io.tima.core.media

import androidx.compose.runtime.Composable

/**
 * HTML из системного буфера — для «С оформл.» (Решения §5). Буфер без HTML или платформа его не
 * отдаёт — `null`: вставится обычный текст.
 */
@Composable
expect fun rememberClipboardHtml(): () -> String?
