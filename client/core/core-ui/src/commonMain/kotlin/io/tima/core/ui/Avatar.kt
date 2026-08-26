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
fun Аватар(
    /** Одна-две буквы. Картинка приезжает позже, вместе с медиа. */
    буквы: String,
    modifier: Modifier = Modifier,
    размер: РазмерАватара = РазмерАватара.Обычный,
    /** Заливка. `null` — тихая подложка темы: аватар не спорит с содержимым. */
    фон: Color? = null,
) {
    val цвета = Тима.цвета
    Box(
        modifier = modifier
            .size(размер.сторона)
            .background(
                color = фон ?: цвета.акцентМягкий,
                shape = RoundedCornerShape(размер.скругление),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Подпись(
            текст = буквы,
            кегль = размер.кегль,
            вес = FontWeight.ExtraBold,
            цвет = цвета.текст,
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
enum class РазмерАватара(val сторона: Dp, val скругление: Dp, val кегль: TextUnit) {
    Малый(сторона = TimaZones.аватар * 0.76f, скругление = TimaShapes.квадратМал, кегль = TimaType.щ6),
    Обычный(сторона = TimaZones.аватар, скругление = TimaShapes.квадрат, кегль = TimaType.щ5),
    Большой(сторона = TimaZones.аватар * 1.9f, скругление = TimaShapes.квадратБол, кегль = TimaType.щ2),
}
