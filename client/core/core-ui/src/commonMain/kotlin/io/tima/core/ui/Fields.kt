package io.tima.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
fun Field(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "",
    /** Числовое: телефон, код. Меняет клавиатуру на телефоне, на ПК — ничего. */
    numeric: Boolean = false,
    byCenter: Boolean = false,
    fontSize: TextUnit = TimaType.sz3,
    /**
     * Одна строка: длинный текст и подсказка не переносятся, а уезжают за край.
     *
     * Для поиска это обязательно (заказчик 2026-09-19): поле поиска стоит в шапке окна
     * между вкладками и списком, и второй строкой оно сдвигает вниз весь список — ровно в
     * тот момент, когда человек набирает и смотрит на результат.
     */
    lineOne: Boolean = false,
    /**
     * Забрать ввод сразу, как поле появилось, — и поднять клавиатуру.
     *
     * Для строки поиска это не украшение: её вызвали кнопкой «🔍», то есть намерение уже
     * высказано, и требовать второго нажатия по самому полю значит спросить дважды об
     * одном. Для обычных форм по умолчанию выключено: там полей несколько, и выбирать
     * за человека, с какого начать, не наше дело.
     */
    autoFocus: Boolean = false,
    /**
     * Толщина пилюли — как у круглой кнопки рядом ([TimaSizes.iconButton]), а не как у
     * формы ввода. Для строки поиска это решение заказчика 2026-09-19: поле стоит в одном
     * ряду с крестиком, и пилюля вдвое толще кнопки рядом выглядит опечаткой.
     */
    narrow: Boolean = false,
) {
    val colors = Tima.colors
    val focus = remember { FocusRequester() }
    if (autoFocus) {
        LaunchedEffect(Unit) { focus.requestFocus() }
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(if (narrow) Modifier.height(TimaSizes.iconButton) else Modifier)
            .background(colors.softAccent, CircleShape)
            .padding(
                horizontal = if (narrow) TimaSpacing.about4 else TimaSpacing.about5,
                vertical = if (narrow) 0.dp else 14.dp,
            ),
        contentAlignment = if (byCenter) Alignment.Center else Alignment.CenterStart,
    ) {
        if (value.isEmpty() && hint.isNotEmpty()) {
            Caption(hint, fontSize = fontSize, color = colors.text3, lineOne = lineOne)
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = TextStyle(
                fontSize = fontSize,
                color = colors.text,
                textAlign = if (byCenter) TextAlign.Center else TextAlign.Start,
            ),
            cursorBrush = SolidColor(colors.navigation),
            singleLine = lineOne,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
            ),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
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
fun Trouble(text: String, modifier: Modifier = Modifier) = Box(
    modifier = modifier
        .fillMaxWidth()
        .background(Tima.colors.softAccent, CircleShape)
        .padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2),
) {
    Caption(text, fontSize = TimaType.sz5, weight = androidx.compose.ui.text.font.FontWeight.Bold)
}
