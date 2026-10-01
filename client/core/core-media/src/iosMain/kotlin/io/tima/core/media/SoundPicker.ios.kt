package io.tima.core.media

import androidx.compose.runtime.Composable

/** Приложения под iOS мы не раздаём — порт есть, выбора нет. */
actual val systemSoundsAvailable: Boolean = false

@androidx.compose.runtime.Composable
actual fun systemDefaultSoundTitle(use: SoundUse): String? = null

@Composable
actual fun rememberSystemSoundPicker(use: SoundUse, onPicked: (SoundPick?) -> Unit): () -> Unit =
    { onPicked(null) }

@Composable
actual fun rememberSoundFilePicker(name: String, onPicked: (SoundPick?) -> Unit): () -> Unit =
    { onPicked(null) }
