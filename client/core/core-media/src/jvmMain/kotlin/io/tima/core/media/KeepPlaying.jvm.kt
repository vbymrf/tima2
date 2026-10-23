package io.tima.core.media

/** ПК: окно живёт, пока не закрыто, — службы не нужно. */
actual fun keepPlaying(player: OwnPlayer, on: Boolean) = Unit
