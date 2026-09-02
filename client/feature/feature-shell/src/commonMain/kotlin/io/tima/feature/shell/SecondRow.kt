package io.tima.feature.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.tima.core.ui.Chip
import io.tima.core.ui.ChipKind
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.Tima

/**
 * Второй ряд окна: фильтры и режимы под вкладками.
 *
 * ── ЧТО ЭТО ЗА РЯД И ЧЕМ ОН ОТЛИЧАЕТСЯ ОТ ВКЛАДОК ───────────────────────────
 *
 * `интерфейс.md §1`: «Между зоной 1 и зоной 2 живут вкладки, а под ними — режимы,
 * фильтры или полоса разделов, если они в этом окне есть».
 *
 * **Вкладка говорит, откуда содержимое; чип — про что оно.** Отсюда и разное
 * поведение: вкладка меняет экран, чип сужает список, ничего не пряча (`§13`).
 *
 * ── ЧТО РЕШЕНО 2026-09-02 ───────────────────────────────────────────────────
 *
 * **Ничего не прячется: ряд переносится на новую строку.** Первая редакция прокручивала
 * чипы вбок, и на колонке ПК в 340 точек «Каталог» просто исчезал за краем — прокрутка
 * есть, а признака прокрутки нет, и чип выглядел несуществующим. Перенос виден сразу:
 * ряд стал выше, значит в нём есть ещё.
 *
 * **Переключатель режима набран теми же ярлычками, что и всё вокруг**, а не отдельной
 * сегментной пилюлей. Решение заказчика. Цена названа: фильтр и режим стали неотличимы
 * на вид, хотя ведут себя по-разному — чип сужает список, режим переключает состояние
 * окна и снять его нельзя. Различие осталось в поведении и в порядке: режим последний.
 *
 * **Прижать вправо больше нельзя, и это следствие переноса.** Прижатое к краю не
 * переносится: оно либо стоит в строке, либо выдавливает соседей за край — то самое, от
 * чего уходили.
 *
 * **Фон общий и серый** — тот же, что у вкладок и шапки. Шапка, вкладки и этот ряд
 * образуют один блок управления; линия у него одна, снизу, и рисует её каркас окна.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilterRow(
    /** Чипы ряда. Пусто — законный случай: бывает ряд из одного только режима. */
    items: List<String> = emptyList(),
    selected: String = "",
    onPick: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    /** Хвост ряда: переключатель режимов. Есть не у всякого ряда. */
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = Tima.colors
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.functional)
            .padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2),
        horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
        verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
    ) {
        for (name in items) {
            Chip(
                label = name,
                kind = if (name == selected) ChipKind.Selected else ChipKind.Quiet,
                onClick = { onPick(name) },
            )
        }
        trailing?.invoke()
    }
}

/**
 * Переключатель режимов: `.режимы`.
 *
 * Набран **теми же ярлычками**, что вкладки и фильтры, — решение заказчика 2026-09-02.
 * Прежняя редакция рисовала сегменты в общей пилюле, чтобы отличать «переключить
 * состояние» от «сузить список»; отличие снято намеренно, и вместе с ним снята вторая
 * форма, которую человеку пришлось бы учить.
 *
 * Два таких переключателя: «Лента / Слайды» в окне 3 — он стоит в ряду вкладок, потому
 * что это состояние всего окна, — и «Открытое / Личное» в коллекциях окна 5, во втором
 * ряду рядом со своими подвкладками.
 */
@Composable
fun ModeSwitch(
    modes: List<String>,
    selected: String,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) = Row(
    modifier = modifier,
    horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
    verticalAlignment = Alignment.CenterVertically,
) {
    for (mode in modes) {
        Chip(
            label = mode,
            kind = if (mode == selected) ChipKind.Selected else ChipKind.Quiet,
            onClick = { onPick(mode) },
        )
    }
}
