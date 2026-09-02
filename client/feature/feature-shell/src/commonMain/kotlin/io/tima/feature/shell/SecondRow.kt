package io.tima.feature.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.tima.core.ui.Caption
import io.tima.core.ui.Chip
import io.tima.core.ui.ChipKind
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.Tima
import io.tima.core.ui.bottomLine

/**
 * Второй ряд окна: фильтры и режимы под вкладками.
 *
 * ── ЧТО ЭТО ЗА РЯД И ЧЕМ ОН ОТЛИЧАЕТСЯ ОТ ВКЛАДОК ───────────────────────────
 *
 * `интерфейс.md §1`: «Между зоной 1 и зоной 2 живут вкладки, а под ними — режимы,
 * фильтры или полоса разделов, если они в этом окне есть».
 *
 * **Вкладка говорит, откуда содержимое; чип — про что оно.** Отсюда и разное
 * поведение: вкладка меняет экран, чип сужает список, ничего не пряча (`§13`). Оба
 * набора выглядят пилюлями, и это не небрежность — залитая пилюля везде означает
 * «выбрано», и человеку не приходится учить второй язык.
 *
 * **Ряд один на все окна.** Пять копий разошлись бы молча: в одном окне фильтры под
 * линией, в другом над ней. Заметили бы глазами через полгода.
 */

/**
 * Ряд фильтров: `.строка-фильтров`.
 *
 * Стоит **на подложке содержимого, а не на функциональной** — так в макете, и это
 * осмысленно: вкладки принадлежат управлению окном, фильтр принадлежит списку под
 * ним. Линия снизу отделяет ряд от самого списка.
 *
 * Чипы прокручиваются вбок, [trailing] — нет: переключатель прижат к правому краю и
 * остаётся на виду, сколько бы чипов ни было. «Все · Контактов · Неизвестные ·
 * Пропущенные» на телефоне в 380 точек уже не помещаются.
 */
@Composable
fun FilterRow(
    /**
     * Чипы ряда. Пусто — законный случай: у окна 3 во втором ряду только режим, и
     * ряд тогда состоит из одного прижатого вправо переключателя.
     */
    items: List<String> = emptyList(),
    selected: String = "",
    onPick: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    /** Правый край ряда: переключатель режимов. Есть не у всякого ряда. */
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = Tima.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface)
            .bottomLine(colors.line)
            .padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2),
        horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Чипы занимают остаток и прокручиваются внутри него; переключатель меряется
        // первым и своей ширины не отдаёт.
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (name in items) {
                Chip(
                    label = name,
                    kind = if (name == selected) ChipKind.Selected else ChipKind.Quiet,
                    onClick = { onPick(name) },
                )
            }
        }
        trailing?.invoke()
    }
}

/**
 * Переключатель режимов: `.режимы`.
 *
 * От ряда чипов отличается смыслом, и потому выглядит иначе — **сегменты в общей
 * пилюле**, а не отдельные чипы. Чип сужает список и снимается; режим переключает
 * состояние, и снять его нельзя: что-то из двух выбрано всегда.
 *
 * Два таких переключателя: «Лента / Слайды» в окне 3 и «Открытое / Личное» в
 * коллекциях окна 5.
 */
@Composable
fun ModeSwitch(
    modes: List<String>,
    selected: String,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Tima.colors
    Row(
        modifier = modifier
            .background(colors.softAccent, CircleShape)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (mode in modes) {
            val current = mode == selected
            Box(
                modifier = Modifier
                    .background(
                        if (current) colors.navigation else Color.Transparent,
                        CircleShape,
                    )
                    .clickable { onPick(mode) }
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Caption(
                    text = mode,
                    fontSize = TimaType.sz6,
                    weight = FontWeight.Bold,
                    color = if (current) colors.onAccent else colors.text2,
                )
            }
        }
    }
}
