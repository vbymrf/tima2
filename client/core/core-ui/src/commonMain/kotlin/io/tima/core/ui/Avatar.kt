package io.tima.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit

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
) {
    val colors = Tima.colors
    Box(
        modifier = modifier
            .size(size.side)
            .background(
                color = background ?: colors.softAccent,
                shape = RoundedCornerShape(size.rounding),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Caption(
            text = letters,
            fontSize = size.fontSize,
            weight = FontWeight.ExtraBold,
            color = colors.text,
        )
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
}
