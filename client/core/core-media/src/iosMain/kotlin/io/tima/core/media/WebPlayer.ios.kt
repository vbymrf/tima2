package io.tima.core.media

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** На iOS приложения пока нет — браузерного движка тоже (ПЛАН-(ВМ) ВМ6). */
@Composable
actual fun rememberWebPlayer(): WebPlayer? = null

@Composable
actual fun WebPlayerSurface(player: WebPlayer, modifier: Modifier) = Unit
