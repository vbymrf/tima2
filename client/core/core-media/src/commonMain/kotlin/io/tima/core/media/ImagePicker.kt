package io.tima.core.media

import androidx.compose.runtime.Composable

/** Выбранная картинка: как есть, без разбора. Разбор — [decodeImage]. */
class PickedImage(val bytes: ByteArray, val mime: String)

/**
 * Выбор картинки с устройства.
 *
 * **Composable, а не функция**: на Android выбор идёт через ActivityResult, а зарегистрировать
 * его можно только внутри композиции (`rememberLauncherForActivityResult`). Возвращает
 * «открыть выборщик»; ответ приходит в [onPicked] — `null`, если человек передумал.
 *
 * На ПК — файловый диалог; на iOS приложения нет, и там честный `null` сразу.
 */
@Composable
expect fun rememberImagePicker(onPicked: (PickedImage?) -> Unit): () -> Unit
