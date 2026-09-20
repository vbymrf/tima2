package io.tima.feature.call

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.tima.core.call.VideoHandle

/**
 * Звонков здесь нет, значит нет и дорожек: [VideoHandle] на этой платформе никто не
 * создаёт. Пустая реализация нужна только для того, чтобы общий экран собирался.
 */
@Composable
actual fun CallVideo(handle: VideoHandle, modifier: Modifier) = Unit
