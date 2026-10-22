package io.tima.core.media

import androidx.compose.runtime.Composable

/** iOS: приложения ещё нет — честное «ничего не выбрано». */
@Composable
actual fun rememberFilePicker(kind: FileKind, onPicked: (PickedFile?) -> Unit): () -> Unit = { onPicked(null) }

@Composable
actual fun rememberPhotosPicker(max: Int, onPicked: (List<PickedImage>) -> Unit): () -> Unit = { onPicked(emptyList()) }

@Composable
actual fun rememberFileSaver(onDone: (Boolean) -> Unit): (name: String, mime: String, bytes: ByteArray) -> Unit = { _, _, _ -> onDone(false) }

@Composable
actual fun rememberFolderSaver(onDone: (Int) -> Unit): (List<SavedFile>) -> Unit = { onDone(0) }
