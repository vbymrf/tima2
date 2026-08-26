package io.tima.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Шапка окна.
 *
 * **Главное правило: плашка есть только у основных окон.** Салатовая плашка отвечает
 * на вопрос «в каком я окне»; в подокне — чате, звонке, настройках — этот вопрос не
 * стоит, оттуда выходят кнопкой «назад». Поэтому у подокна шапка остаётся полосой на
 * функциональной подложке, и зелёной плашки там нет.
 *
 * **Логотип и название — одна область нажатия**, от левого края плашки до кнопок.
 * Нажатие открывает переключение окон: то, что говорит «где я», ведёт туда, где это
 * меняют. В прежней редакции нажимался только логотип — цель в 30 px, о которой надо
 * было знать.
 */
@Composable
fun WindowHeader(
    title: String,
    modifier: Modifier = Modifier,
    /** Буква логотипа. В подокне логотипа нет. */
    logo: String? = null,
    /** Нажатие на логотип с названием: переключение окон. */
    onSwitchWindows: (() -> Unit)? = null,
    /** Кнопки справа: поиск, настройки. Внутри плашки они белые. */
    right: (@Composable () -> Unit)? = null,
) {
    val colors = Tima.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.functional)
            .bottomLine(colors.line)
            .padding(horizontal = 12.dp, vertical = TimaSpacing.about2),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.navigation, RoundedCornerShape(TimaShapes.smallSquare))
                .heightIn(min = TimaZones.zone1 - TimaSpacing.about4)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (onSwitchWindows != null) {
                            Modifier.clickable(onClick = onSwitchWindows)
                        } else {
                            Modifier
                        },
                    ),
                horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                logo?.let {
                    // Логотип — белый квадрат внутри салатовой плашки. Квадрат, потому
                    // что это «что-то», а не «нажми»; белый, потому что он на зелёном.
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .background(colors.inPlate, RoundedCornerShape(TimaShapes.smallSquare)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Caption(it, fontSize = TimaType.sz4, weight = FontWeight.ExtraBold, color = colors.navigation)
                    }
                }
                Caption(
                    text = title,
                    // Цвет названия — от заливки, а не свой токен: в светлой теме это
                    // чёрный, в тёмной белый. Отдельного «цвета названия» не бывает.
                    fontSize = TimaType.sz3,
                    weight = FontWeight.ExtraBold,
                    color = colors.onAccent,
                    lineOne = true,
                )
            }
            right?.invoke()
        }
    }
}

/**
 * Шапка подокна: полоса без плашки.
 *
 * «Назад» здесь **салатовая** — это навигация, и она главная кнопка шапки подокна.
 * Название набрано обычным текстом: плашки нет, значит и текста на заливке нет.
 */
@Composable
fun SubwindowHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Подпись под названием: «в сети», «3 участника». */
    caption: String? = null,
    right: (@Composable () -> Unit)? = null,
) {
    val colors = Tima.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.functional)
            .bottomLine(colors.line)
            .heightIn(min = TimaZones.zone1)
            .padding(horizontal = TimaSpacing.about4),
        horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // «Назад» рисуется, а не набирается глифом: «‹» есть не во всяком шрифте, а
        // пропавший знак навигации — это кнопка без надписи. См. Знаки.kt.
        ButtonCircle(onClick = onBack, live = true) {
            Arrow(Side.Left, color = colors.onAccent)
        }
        androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
            Caption(title, fontSize = TimaType.sz3, weight = FontWeight.ExtraBold, lineOne = true)
            // Подпись шапки — одна строка: шапка не растёт от длинного имени.
            caption?.let { Tertiary(it, lineOne = true) }
        }
        right?.invoke()
    }
}
