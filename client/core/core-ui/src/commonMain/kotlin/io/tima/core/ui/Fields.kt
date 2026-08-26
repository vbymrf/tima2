package io.tima.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

/**
 * Поле ввода — пилюля на тихой подложке.
 *
 * `BasicTextField` из foundation, а не готовое поле material3: у нас своя система форм и
 * цветов, и брать чужую значило бы спорить с макетом в каждом состоянии поля.
 *
 * **Живёт в дизайн-системе, а не в экранах.** Первая редакция держала такое поле частным в
 * `feature-auth`; на втором экране с вводом его пришлось бы повторить, и с этого начинается
 * расхождение — два поля, отличающихся на пару точек, которых никто не сравнивал.
 *
 * Подсказка рисуется **под** текстом, а не вместо него: пустое поле показывает подсказку,
 * непустое перекрывает её содержимым. Отдельного состояния «показывать подсказку» нет —
 * оно расходилось бы с текстом.
 */
@Composable
fun Поле(
    значение: String,
    onИзменение: (String) -> Unit,
    modifier: Modifier = Modifier,
    подсказка: String = "",
    /** Числовое: телефон, код. Меняет клавиатуру на телефоне, на ПК — ничего. */
    числовое: Boolean = false,
    поЦентру: Boolean = false,
    кегль: TextUnit = TimaType.щ3,
) {
    val цвета = Тима.цвета
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(цвета.акцентМягкий, CircleShape)
            .padding(horizontal = TimaSpacing.о5, vertical = 14.dp),
        contentAlignment = if (поЦентру) Alignment.Center else Alignment.CenterStart,
    ) {
        if (значение.isEmpty() && подсказка.isNotEmpty()) {
            Подпись(подсказка, кегль = кегль, цвет = цвета.текст3)
        }
        BasicTextField(
            value = значение,
            onValueChange = onИзменение,
            textStyle = TextStyle(
                fontSize = кегль,
                color = цвета.текст,
                textAlign = if (поЦентру) TextAlign.Center else TextAlign.Start,
            ),
            cursorBrush = SolidColor(цвета.навигация),
            keyboardOptions = KeyboardOptions(
                keyboardType = if (числовое) KeyboardType.Number else KeyboardType.Text,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Сообщение о беде — словами, без красного.
 *
 * Красного в палитре нет вовсе, и опасное отличается словом и местом. Там, где беда
 * единственное изменение на экране, она заметна и без цвета.
 */
@Composable
fun Беда(текст: String, modifier: Modifier = Modifier) = Box(
    modifier = modifier
        .fillMaxWidth()
        .background(Тима.цвета.акцентМягкий, CircleShape)
        .padding(horizontal = TimaSpacing.о4, vertical = TimaSpacing.о2),
) {
    Подпись(текст, кегль = TimaType.щ5, вес = androidx.compose.ui.text.font.FontWeight.Bold)
}
