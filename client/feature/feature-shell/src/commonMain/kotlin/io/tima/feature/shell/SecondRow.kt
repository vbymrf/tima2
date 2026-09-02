package io.tima.feature.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import io.tima.core.ui.Caption
import io.tima.core.ui.Chip
import io.tima.core.ui.ChipKind
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
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
 * **Три ряда — три вида, и путать их нечем.** Вкладка — слово без заливки, текущая
 * залита салатовым; подвкладка — серая пилюля, выбранная залита тем же салатовым;
 * переключатель режима живёт в общей серой капсуле, и выбранное там **обведено**, а не
 * залито ([ModeSwitch]).
 *
 * Вид этот собирался в три приёма, и каждый раз ряды оказывались похожи сильнее, чем
 * думалось за кодом. Сперва все три набрались одинаковыми чипами, и «Открытое» с
 * «Личным» встали пятой и шестой подвкладкой. Потом подвкладка стала серой, а
 * переключатель — капсулой, но вкладка осталась чипом с тихой заливкой: два ряда
 * залитых пилюль друг под другом, и заказчик сказал прямо — «второй ряд полностью
 * повторяет первый». Тогда вкладка вернулась к макетному `.таб` без фона, а заливка у
 * выбранного режима сменилась обводкой.
 *
 * **Прижать вправо больше нельзя, и это следствие переноса.** Прижатое к краю не
 * переносится: оно либо стоит в строке, либо выдавливает соседей за край — то самое, от
 * чего уходили.
 *
 * **Подложка ряда — общая серая**, та же, что у вкладок и шапки. Шапка, вкладки и этот
 * ряд образуют один блок управления; линия у него одна, снизу, и рисует её каркас окна.
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
                // Нейтральная подложка, а не тихая: ряд подвкладок обязан читаться
                // иначе, чем ряд вкладок над ним. Решение заказчика — «серый, темнее
                // фона; активная — тот же салатовый».
                kind = if (name == selected) ChipKind.Selected else ChipKind.Neutral,
                onClick = { onPick(name) },
            )
        }
        trailing?.invoke()
    }
}

/**
 * Переключатель режимов — `.режимы` макета, один в один.
 *
 * **Серая капсула, внутри сегменты, выбранный обведён салатовым.** Форму назвал
 * заказчик: `телефон/окна/медиа.html`, где этим переключают «Лента / Слайды». Отметку
 * выбранного он назвал отдельно — проба 04 из `пробы/пробы-второй-ряд.html`:
 * «вместо зелёного фона выбранного делаем обводку». В `стиль.css` это три строки, и
 * они здесь воспроизведены значениями:
 *
 * ```css
 * .режимы     { background: var(--чёрный-6); border-radius: var(--радиус-круг); padding: 3px }
 * .режим      { padding: 3px 10px; border: 2px solid transparent; font-size: var(--щ6); color: var(--чёрный-50) }
 * .режим.тек  { border-color: var(--навигация); color: var(--текст) }
 * ```
 *
 * **Почему обводка, а не заливка.** Залитым салатовым в этих трёх рядах уже показаны
 * две разные вещи: текущая вкладка и выбранная подвкладка. Третья заливка подряд
 * говорила «выбрано» в третий раз, и ряды сливались в один. Обводка говорит то же
 * слово другим способом — и говорит его только здесь.
 *
 * **Рамка есть у всех сегментов**, у невыбранных прозрачная. Иначе выбранный сегмент
 * становился бы на четыре точки шире соседа, и переключатель дёргался бы при каждом
 * нажатии.
 *
 * **Капсула и есть то, что отличает переключатель от подвкладок.** Промежуточная
 * редакция того же дня набрала его отдельными чипами — вышли ещё две подвкладки, и
 * «Открытое» с «Личным» встали в один ряд с «Медиа» и «Каталогом», хотя выбирают
 * разное: подвкладка сужает список, режим переключает контур. Общая подложка склеивает
 * два сегмента в одну вещь, у которой всегда выбрана ровно одна половина.
 *
 * Два таких переключателя: «Лента / Слайды» в окне 3 — он стоит в ряду вкладок, потому
 * что это состояние всего окна, — и «Открытое / Личное» в коллекциях окна 5.
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
            .background(colors.quiet, CircleShape)
            .padding(SWITCH_EDGE),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (mode in modes) {
            val current = mode == selected
            Box(
                modifier = Modifier
                    .border(
                        width = SWITCH_OUTLINE,
                        color = if (current) colors.navigation else Color.Transparent,
                        shape = CircleShape,
                    )
                    .clickable { onPick(mode) }
                    .padding(horizontal = 10.dp, vertical = 3.dp),
                contentAlignment = Alignment.Center,
            ) {
                Caption(
                    text = mode,
                    fontSize = TimaType.sz6,
                    weight = FontWeight.Bold,
                    color = if (current) colors.text else colors.text2,
                )
            }
        }
    }
}

/**
 * Поле капсулы вокруг сегментов — `padding: 3px` макета.
 *
 * Числом, а не токеном отступа: в лестнице `--о1…--о6` трёх точек нет, и подгонять
 * `about1` под этот случай значило бы менять её для всех остальных.
 */
private val SWITCH_EDGE = 3.dp

/**
 * Толщина обводки выбранного сегмента — `.кн.контур` макета, 2 px.
 *
 * Отступы сегмента ужаты ровно на неё (12 → 10 и 5 → 3): обводка добавляется снаружи
 * содержимого, и без этого переключатель вырос бы на четыре точки в каждую сторону.
 */
private val SWITCH_OUTLINE = 2.dp
