package io.tima.core.media

import androidx.compose.runtime.Composable

/**
 * HTML из системного буфера — для «С оформл.» (Решения §5). Буфер без HTML или платформа его не
 * отдаёт — `null`: вставится обычный текст.
 */
@Composable
expect fun rememberClipboardHtml(): () -> String?

/**
 * Картинка из системного буфера (Решения §10.4, «картинка из буфера сама по себе»): скопированная
 * в браузере или снимок экрана. Нет картинки или платформа не отдаёт — `null`: вставится текст.
 */
@Composable
expect fun rememberClipboardImage(): () -> PickedFile?
