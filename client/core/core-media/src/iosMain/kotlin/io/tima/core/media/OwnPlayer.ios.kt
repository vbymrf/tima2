package io.tima.core.media

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** На iOS приложения пока нет — своего движка тоже (ПЛАН-(ВМ) ВМ5). */
@Composable
actual fun rememberOwnPlayer(): OwnPlayer? = null

@Composable
actual fun OwnPlayerSurface(player: OwnPlayer, modifier: Modifier) = Unit

@Composable
actual fun rememberFramePeek(url: String?): FramePeek? = null
