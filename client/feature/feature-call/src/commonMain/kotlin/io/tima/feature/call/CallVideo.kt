package io.tima.feature.call

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.tima.core.call.VideoHandle

/**
 * Картинка звонка — единственное место экрана, которое знает про платформу.
 *
 * На Android внутри `SurfaceViewRenderer` из libwebrtc, завёрнутый в `AndroidView`:
 * Compose-вида у него нет, и общим кодом его не нарисовать. На ПК и iOS звонков нет
 * вовсе — там пустота, и это честно: рисовать нечего, потому что и дорожек нет.
 *
 * **Всё остальное на экране звонка общее.** Ради этого поверхность и вынесена в одну
 * функцию: разойтись между платформами может только она.
 */
@Composable
expect fun CallVideo(handle: VideoHandle, modifier: Modifier)
