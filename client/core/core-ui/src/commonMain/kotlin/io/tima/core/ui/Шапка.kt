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
fun ШапкаОкна(
    название: String,
    modifier: Modifier = Modifier,
    /** Буква логотипа. В подокне логотипа нет. */
    логотип: String? = null,
    /** Нажатие на логотип с названием: переключение окон. */
    onПереключитьОкна: (() -> Unit)? = null,
    /** Кнопки справа: поиск, настройки. Внутри плашки они белые. */
    справа: (@Composable () -> Unit)? = null,
) {
    val цвета = Тима.цвета
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(цвета.функц)
            .линияСнизу(цвета.линия)
            .padding(horizontal = 12.dp, vertical = TimaSpacing.о2),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(цвета.навигация, RoundedCornerShape(TimaShapes.квадратМал))
                .heightIn(min = TimaZones.зона1 - TimaSpacing.о4)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(TimaSpacing.о3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (onПереключитьОкна != null) {
                            Modifier.clickable(onClick = onПереключитьОкна)
                        } else {
                            Modifier
                        },
                    ),
                horizontalArrangement = Arrangement.spacedBy(TimaSpacing.о3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                логотип?.let {
                    // Логотип — белый квадрат внутри салатовой плашки. Квадрат, потому
                    // что это «что-то», а не «нажми»; белый, потому что он на зелёном.
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .background(цвета.вПлашке, RoundedCornerShape(TimaShapes.квадратМал)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Подпись(it, кегль = TimaType.щ4, вес = FontWeight.ExtraBold, цвет = цвета.навигация)
                    }
                }
                Подпись(
                    текст = название,
                    // Цвет названия — от заливки, а не свой токен: в светлой теме это
                    // чёрный, в тёмной белый. Отдельного «цвета названия» не бывает.
                    кегль = TimaType.щ3,
                    вес = FontWeight.ExtraBold,
                    цвет = цвета.наАкценте,
                    однойСтрокой = true,
                )
            }
            справа?.invoke()
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
fun ШапкаПодокна(
    название: String,
    onНазад: () -> Unit,
    modifier: Modifier = Modifier,
    /** Подпись под названием: «в сети», «3 участника». */
    подпись: String? = null,
    справа: (@Composable () -> Unit)? = null,
) {
    val цвета = Тима.цвета
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(цвета.функц)
            .линияСнизу(цвета.линия)
            .heightIn(min = TimaZones.зона1)
            .padding(horizontal = TimaSpacing.о4),
        horizontalArrangement = Arrangement.spacedBy(TimaSpacing.о3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // «Назад» рисуется, а не набирается глифом: «‹» есть не во всяком шрифте, а
        // пропавший знак навигации — это кнопка без надписи. См. Знаки.kt.
        КругКнопка(onClick = onНазад, живая = true) {
            Стрелка(Сторона.Влево, цвет = цвета.наАкценте)
        }
        androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
            Подпись(название, кегль = TimaType.щ3, вес = FontWeight.ExtraBold, однойСтрокой = true)
            подпись?.let { Третьестепенное(it) }
        }
        справа?.invoke()
    }
}
