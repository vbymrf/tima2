package io.tima.core.media

import androidx.compose.runtime.Composable

/** ПК: камеры для снимка нет — пункта нет (ВЫБОР-РЕАЛИЗАЦИИ §11.2а). */
@Composable
actual fun rememberCameraShot(onShot: (PickedImage?) -> Unit): (() -> Unit)? = null

/** ПК: системной книги нет — имя и номер вводятся руками. */
@Composable
actual fun rememberContactPicker(onPicked: (PickedContact?) -> Unit): (() -> Unit)? = null

/** ПК: места нет — координаты или ссылка на карту вводятся руками. */
@Composable
actual fun rememberMyPlace(onLocated: (Pair<Double, Double>?) -> Unit): (() -> Unit)? = null
