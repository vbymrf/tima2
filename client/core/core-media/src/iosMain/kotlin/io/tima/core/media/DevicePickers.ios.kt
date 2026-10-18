package io.tima.core.media

import androidx.compose.runtime.Composable

@Composable
actual fun rememberCameraShot(onShot: (PickedImage?) -> Unit): (() -> Unit)? = null

@Composable
actual fun rememberContactPicker(onPicked: (PickedContact?) -> Unit): (() -> Unit)? = null

@Composable
actual fun rememberMyPlace(onLocated: (Pair<Double, Double>?) -> Unit): (() -> Unit)? = null
