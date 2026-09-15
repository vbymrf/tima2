package io.tima.core.media

import androidx.compose.runtime.Composable

/** iOS: приложения ещё нет, выборщика тоже. Честный «ничего не выбрано», а не заглушка с картинкой. */
@Composable
actual fun rememberImagePicker(onPicked: (PickedImage?) -> Unit): () -> Unit = { onPicked(null) }
