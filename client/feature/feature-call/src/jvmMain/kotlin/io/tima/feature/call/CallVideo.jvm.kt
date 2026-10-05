package io.tima.feature.call

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import io.tima.core.call.PictureVideo
import io.tima.core.call.VideoHandle
import io.tima.core.call.VideoPicture
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/**
 * Видео на ПК — кадрами (ПЛАН-(ПК)-ЗВОНКОВ-ПК, ПК4).
 *
 * Поверхности, как у libwebrtc на Android, здесь нет: движок отдаёт кадры BGRA
 * ([PictureVideo]), и каждый кадр становится картинкой Skia без перестановки байтов —
 * порядок у них общий. Ручка чужого движка (которого на ПК не бывает) рисует пустое место,
 * как и раньше.
 *
 * Вписывается с полями, а не обрезается: собеседник с телефона снимает стоя, окно ПК
 * лежит, и обрезка срезала бы ему голову и подбородок.
 */
@Composable
actual fun CallVideo(handle: VideoHandle, modifier: Modifier) {
    val video = handle as? PictureVideo ?: return
    val picture by video.pictures.collectAsState()
    val shown = picture ?: return
    val bitmap = remember(shown) { shown.toBitmap() }
    Image(bitmap = bitmap, contentDescription = null, modifier = modifier, contentScale = ContentScale.Fit)
}

private fun VideoPicture.toBitmap() =
    Image.makeRaster(
        ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE),
        bgra,
        width * 4,
    ).toComposeImageBitmap()
