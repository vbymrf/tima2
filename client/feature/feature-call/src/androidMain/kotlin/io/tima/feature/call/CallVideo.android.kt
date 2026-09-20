package io.tima.feature.call

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.tima.core.call.LiveKitVideoHandle
import io.tima.core.call.VideoHandle

/**
 * Android: поверхность libwebrtc внутри `AndroidView`.
 *
 * **`onRelease` обязателен.** Поверхность держит дорожку и контекст отрисовки; брошенная
 * без `close`, она переживает звонок и продолжает получать кадры в никуда. Compose зовёт
 * `onRelease`, когда вид уходит из композиции, — это единственный момент, когда мы о её
 * уходе узнаём.
 */
@Composable
actual fun CallVideo(handle: VideoHandle, modifier: Modifier) {
    // Чужая реализация ручки сюда прийти не может — их одна на платформу, — но проверка
    // стоит ноль и превращает невозможное падение в пустое место.
    val live = handle as? LiveKitVideoHandle ?: return
    AndroidView(
        modifier = modifier,
        factory = { context -> live.open(context) },
        onRelease = { view -> live.close(view) },
    )
}
