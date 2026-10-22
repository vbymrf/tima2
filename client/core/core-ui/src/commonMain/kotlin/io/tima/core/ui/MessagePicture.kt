package io.tima.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.github.panpf.sketch.AsyncImage
import com.github.panpf.sketch.rememberAsyncImageState
import com.github.panpf.sketch.request.ComposableImageRequest
import com.github.panpf.sketch.request.ImageResult
import com.github.panpf.sketch.resize.Precision

/**
 * Картинка внутри сообщения (ПЛАН-(РС) РС4): по адресу загрузчика (`tima-media://…`), место под
 * размер от отправителя — лента не прыгает, пока картинка едет. Не загрузилась — серое место с
 * подписью [missing]: невидимого содержимого нет (ADR-0044 §6).
 *
 * @param aspect ширина к высоте; `null` — неизвестно, место квадратное.
 */
@Composable
fun MessagePicture(uri: String, aspect: Float?, missing: String, modifier: Modifier = Modifier) {
    val colors = Tima.colors
    val shape = RoundedCornerShape(8.dp)
    val ratio = (aspect ?: 1f).coerceIn(0.4f, 3f)
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = MIN_PICTURE)
            .aspectRatio(ratio)
            .clip(shape)
            .background(colors.functional, shape),
        contentAlignment = Alignment.Center,
    ) {
        val sketch = LocalSketch.current
        if (sketch == null) {
            Tertiary(missing, lineOne = true)
            return@Box
        }
        val state = rememberAsyncImageState()
        if (state.result is ImageResult.Error) Tertiary(missing, lineOne = true)
        AsyncImage(
            uri = uri,
            sketch = sketch,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            state = state,
            contentScale = ContentScale.Crop,
        )
    }
}

/** Наименьшая высота картинки в сообщении — её видно, даже если она узкая и длинная. */
private val MIN_PICTURE = 64.dp

/**
 * Фото вне пузыря в ленте (ADR-0052): само фото, не превью — загрузчик ужимает его под место, это и
 * есть превью получателя. Пока едет — пустое место, размер задаёт [modifier]. Подписи нет: фото,
 * которого уже не будет, лента не показывает вовсе.
 *
 * Точность — **ровно размер места** (заказчик 2026-10-22: «превью получше качества, где два и более»):
 * по умолчанию загрузчик ужимает кратно двум и не больше места по числу точек — квадрат 126 dp из
 * 1280×960 получал 240 точек на 330 и выглядел мыльным.
 */
@Composable
fun PhotoTile(uri: String, modifier: Modifier = Modifier) {
    Box(modifier) {
        val sketch = LocalSketch.current ?: return@Box
        AsyncImage(
            request = ComposableImageRequest(uri) { precision(Precision.EXACTLY) },
            sketch = sketch,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    }
}
