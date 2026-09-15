package io.tima.feature.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.tima.core.media.SquareCrop
import io.tima.core.ui.Button
import io.tima.core.ui.ButtonKind
import io.tima.core.ui.Secondary
import io.tima.core.ui.SubwindowHeader
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaShapes
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.words

/**
 * Обрезка аватара квадратом — ПЛАН-КОНТАКТОВ.md, Д8 (решение заказчика 2026-09-15).
 *
 * **Окно неподвижно, двигается картинка.** Так на телефоне привычнее: палец тянет то,
 * что под ним, а не рамку. Растягивание двумя пальцами — увеличение; за край картинки
 * окно не уходит — [SquareCrop.clampPan] прижимает сдвиг к пределам на каждом жесте, а
 * не только в момент «Готово», иначе человек видел бы одно, а получал другое.
 *
 * **Считается один раз**, по «Обрезать»: экран показывает то же самое через
 * `graphicsLayer`, и перерисовывать картинку на каждый кадр незачем.
 */
@Composable
fun AvatarCropScreen(
    image: ImageBitmap,
    onDone: (ImageBitmap) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Tima.colors
    val words = Tima.words.chat
    val density = LocalDensity.current
    val viewportPx = with(density) { VIEWPORT.toPx() }
    val crop = remember(image, viewportPx) { SquareCrop(image, viewportPx) }

    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    Column(modifier.fillMaxSize().background(colors.surface)) {
        SubwindowHeader(title = words.avatarCrop, onBack = onCancel)
        Box(
            modifier = Modifier.fillMaxSize().padding(TimaSpacing.about5),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(TimaSpacing.about4),
            ) {
                Box(
                    modifier = Modifier
                        .size(VIEWPORT)
                        .clip(RoundedCornerShape(TimaShapes.bigSquare))
                        .background(colors.functional)
                        .pointerInput(crop) {
                            detectTransformGestures { _, drag, scale, _ ->
                                zoom = (zoom * scale).coerceIn(SquareCrop.MIN_ZOOM, SquareCrop.MAX_ZOOM)
                                pan = crop.clampPan(pan + drag, zoom)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    // Картинка вписана по меньшей стороне (ContentScale.Crop на квадрате даёт
                    // ровно baseScale), а сдвиг и увеличение — слоем поверх. Та же геометрия,
                    // что считает SquareCrop.cut.
                    Image(
                        bitmap = image,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = zoom
                                scaleY = zoom
                                translationX = pan.x
                                translationY = pan.y
                            },
                    )
                }
                Secondary(words.avatarCropHint)
                Row(horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2)) {
                    Button(label = words.avatarCrop, onClick = { onDone(crop.cut(pan, zoom)) })
                    Button(label = Tima.words.common.cancel, kind = ButtonKind.Quiet, onClick = onCancel)
                }
            }
        }
    }
}

/** Окно обрезки. 280 — влезает в 360 с полями и не мельче, чем нужно пальцам. */
private val VIEWPORT = 280.dp
