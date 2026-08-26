package io.tima.core.ui

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit

/**
 * Единственная текстовая основа дизайн-системы.
 *
 * **Почему `BasicText`, а не `material3.Text`.** У нас своя система цветов и форм; тема
 * Material несла бы свою палитру, свои радиусы и свои размеры, и каждый компонент
 * начинался бы с их отключения. Спорить с чужой темой в каждом месте дороже, чем
 * написать одну подпись.
 *
 * Шрифт пока системный. В макете первым стоит Inter, но кладут шрифт в сборку
 * отдельным решением — у него лицензия и вес, и «пусть будет» здесь неуместно.
 */
@Composable
fun Caption(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = TimaType.sz4,
    weight: FontWeight = FontWeight.Normal,
    color: Color = Tima.colors.text,
    /**
     * Обрезать одной строкой с многоточием.
     *
     * Это про списки: `.обрез` в макете. Имя и превью там не переносятся — иначе строка
     * списка растёт от чужого длинного имени, и список перестаёт быть списком.
     */
    lineOne: Boolean = false,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(color = color, fontSize = fontSize, fontWeight = weight),
        maxLines = if (lineOne) 1 else Int.MAX_VALUE,
        overflow = if (lineOne) TextOverflow.Ellipsis else TextOverflow.Clip,
    )
}

/** Имя в строке списка: `.имя`. */
@Composable
fun Name(text: String, modifier: Modifier = Modifier) =
    Caption(text, modifier, TimaType.sz4, FontWeight.Bold, lineOne = true)

/**
 * Второй уровень: пояснение под заголовком, превью сообщения. `.втор`.
 *
 * **Переносится по умолчанию, и это исправление найденной поломки.** Сначала обе эти
 * подписи были жёстко одностроч­ными — их писали под строку списка, где перенос ломает
 * список. Потом ими же оказались набраны объяснения на экранах входа и привязки, и на
 * телефоне предупреждение «подключённое устройство сможет читать новые сообщения…»
 * обрезалось многоточием на первой же строке. То есть цена решения человеку не
 * сообщалась — при том, что текст был написан.
 *
 * Умолчание теперь наоборот: обрезает тот, кто строит список, и делает это явно. Забыть
 * такое дешевле — строка списка вырастет, и это видно и на снимке, и глазами. Забытый
 * перенос не виден вовсе: текст выглядит законченным.
 */
@Composable
fun Secondary(text: String, modifier: Modifier = Modifier, lineOne: Boolean = false) =
    Caption(text, modifier, TimaType.sz5, FontWeight.Normal, Tima.colors.text2, lineOne)

/** Третий уровень: мелкая пометка, время. `.трет`. Про перенос — см. [Второстепенное]. */
@Composable
fun Tertiary(text: String, modifier: Modifier = Modifier, lineOne: Boolean = false) =
    Caption(text, modifier, TimaType.sz6, FontWeight.SemiBold, Tima.colors.text3, lineOne)
