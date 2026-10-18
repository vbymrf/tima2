package io.tima.core.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import com.github.panpf.sketch.AsyncImage
import com.github.panpf.sketch.Sketch
import com.github.panpf.sketch.rememberAsyncImageState
import com.github.panpf.sketch.request.ImageResult

/**
 * Загрузчик картинок приложения (ПЛАН-(ЗК)). Один на приложение — его ставит корень; `null` —
 * загрузчика нет (снимки, харнесс), и картинка по номеру не рисуется: остаются буквы.
 */
val LocalSketch = staticCompositionLocalOf<Sketch?> { null }

/**
 * Аватар — **квадрат со скруглёнными краями**.
 *
 * Это половина главного правила формы в макете: **квадрат означает «кто-то или
 * что-то», круг — «нажми»**. Два языка формы не смешиваются, и поэтому аватар не
 * бывает круглым ни в каком размере: круглый аватар читался бы кнопкой.
 *
 * В первой редакции макета было наоборот — карточки квадратные, аватары круглые, — и
 * различие ничего не сообщало.
 */
@Composable
fun Avatar(
    /** Одна-две буквы. Картинка приезжает позже, вместе с медиа. */
    letters: String,
    modifier: Modifier = Modifier,
    size: AvatarSize = AvatarSize.Normal,
    /** Заливка. `null` — тихая подложка темы: аватар не спорит с содержимым. */
    background: Color? = null,
    /**
     * Картинка. Есть — рисуется она, букв не видно; нет — буквы, как и было.
     *
     * Та же форма, что у букв: квадрат со скруглением. Картинка не делает аватар круглым —
     * правило формы (квадрат — «кто-то», круг — «нажми») от содержимого не зависит.
     */
    image: ImageBitmap? = null,
    /**
     * Картинка по номеру файла — `tima-media://<номер>` (ПЛАН-(ЗК) ЗК4). Её грузит и раскодирует
     * под размер места загрузчик [LocalSketch]; ушёл аватар с экрана — загрузка отменяется. Пока
     * не доехала или не доехала вовсе — буквы. [image] при этом не нужен: он для картинок,
     * которые уже есть байтами (переписка, обрезка своего аватара).
     */
    picture: String? = null,
    /**
     * Надпись поверх картинки — «ГЗ» у группы звонка на аватаре создателя (заказчик
     * 2026-10-02). Без картинки не рисуется: тогда сами буквы и есть надпись.
     */
    overlay: String? = null,
) {
    val colors = Tima.colors
    val shape = RoundedCornerShape(size.rounding)
    Box(
        modifier = modifier
            .size(size.side)
            .clip(shape)
            .background(color = background ?: colors.softAccent, shape = shape),
        contentAlignment = Alignment.Center,
    ) {
        val sketch = LocalSketch.current
        when {
            image != null -> {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                AvatarOverlay(overlay, size)
            }
            picture != null && sketch != null -> {
                val state = rememberAsyncImageState()
                val shown = state.result is ImageResult.Success
                // Буквы под картинкой, пока она не доехала: место не пустует и не прыгает.
                if (!shown) AvatarLetters(letters, size)
                AsyncImage(
                    uri = picture,
                    sketch = sketch,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    state = state,
                    contentScale = ContentScale.Crop,
                )
                if (shown) AvatarOverlay(overlay, size)
            }
            else -> AvatarLetters(letters, size)
        }
    }
}

@Composable
private fun AvatarLetters(letters: String, size: AvatarSize) {
    Caption(
        text = letters,
        fontSize = size.fontSize,
        weight = FontWeight.ExtraBold,
        color = Tima.colors.text,
    )
}

/**
 * Подложка полупрозрачная из цветов темы: надпись читается и на светлом, и на тёмном лице, а
 * лицо под ней остаётся узнаваемым. Без картинки не рисуется — тогда сами буквы и есть надпись.
 */
@Composable
private fun AvatarOverlay(overlay: String?, size: AvatarSize) {
    val colors = Tima.colors
    overlay?.let {
        Box(Modifier.fillMaxSize().background(colors.text.copy(alpha = 0.4f)), contentAlignment = Alignment.Center) {
            Caption(text = it, fontSize = size.fontSize, weight = FontWeight.ExtraBold, color = colors.surface)
        }
    }
}

/**
 * Размеры из макета: `.ава`, `.ава.мал`, `.ава.бол`.
 *
 * Скругление растёт вместе со стороной — 8, 10, 16 — и это не украшение: у мелкого
 * квадрата большое скругление съедает саму квадратность, а у крупного маленькое
 * возвращает жёсткость, которую скругление и снимало.
 */
enum class AvatarSize(val side: Dp, val rounding: Dp, val fontSize: TextUnit) {
    Small(side = TimaZones.avatar * 0.76f, rounding = TimaShapes.smallSquare, fontSize = TimaType.sz6),
    Normal(side = TimaZones.avatar, rounding = TimaShapes.square, fontSize = TimaType.sz5),
    Big(side = TimaZones.avatar * 1.9f, rounding = TimaShapes.bigSquare, fontSize = TimaType.sz2),
    /** Окно 0 в голосовом звонке: лицо собеседника крупно, как настоящий аватар (2026-10-08). */
    Huge(side = TimaZones.avatar * 3f, rounding = TimaShapes.bigSquare, fontSize = TimaType.sz1),
}
