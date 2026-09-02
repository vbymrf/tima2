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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
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
 * **Вся плашка — одна область нажатия**, от края до края. Нажатие открывает
 * переключение окон: то, что говорит «где я», ведёт туда, где это меняют. Кнопки
 * справа перехватывают своё нажатие сами и наружу его не выпускают.
 *
 * Три решения заказчика 2026-09-02, и все три расходятся с нарисованным макетом
 * намеренно — расхождения записаны в `doc/интерфейс.md §1`:
 *
 * - **разделителя под шапкой нет.** В макете у `.зона-1` стоит `border-bottom`, и
 *   линия отрезала шапку от ряда вкладок, хотя подложка у них одна и та же
 *   функциональная. Теперь шапка и вкладки читаются как один блок управления;
 * - **логотип есть на всех форматах.** Прежде он зависел от ширины — на ПК макет
 *   отдаёт «Т» шапке рейки, — и на настольной сборке буквы не было вовсе. Условие
 *   снято: логотип перестал зависеть от раскладки;
 * - **имя окна стоит ровно по центру плашки**, а не сразу за логотипом.
 */
@Composable
fun WindowHeader(
    title: String,
    modifier: Modifier = Modifier,
    /** Буква логотипа. В подокне логотипа нет. */
    logo: String? = null,
    /** Нажатие на плашку: переключение окон. */
    onSwitchWindows: (() -> Unit)? = null,
    /** Кнопки справа: поиск, настройки, переключатель режимов. Внутри плашки они белые. */
    right: (@Composable () -> Unit)? = null,
) {
    val colors = Tima.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.functional)
            .padding(horizontal = 12.dp, vertical = TimaSpacing.about2),
    ) {
        SidesAndCenter(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.navigation, RoundedCornerShape(TimaShapes.smallSquare))
                .heightIn(min = TimaZones.zone1 - TimaSpacing.about4)
                .then(
                    if (onSwitchWindows != null) {
                        Modifier.clickable(onClick = onSwitchWindows)
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 10.dp, vertical = 6.dp),
            start = {
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
            },
            center = {
                Caption(
                    text = title,
                    // Цвет названия — от заливки, а не свой токен. С 2026-09-02 это
                    // белый в обеих темах, то есть ровно `.имя-окна` макета.
                    fontSize = TimaType.sz3,
                    weight = FontWeight.ExtraBold,
                    color = colors.onAccent,
                    lineOne = true,
                    modifier = Modifier.padding(horizontal = TimaSpacing.about2),
                )
            },
            // Ряд справа собирает вызывающий: он же решает, что там стоит — две
            // кнопки или переключатель режимов рядом с ними.
            end = { right?.invoke() },
        )
    }
}

/**
 * Ряд «слева — по центру — справа», где центр стоит по центру **полосы**, а не
 * остатка.
 *
 * Обычный `Row` так не умеет, и разница видна сразу: слева логотип в 30 точек,
 * справа две кнопки в 80, и имя окна, поставленное между ними, уезжает влево на
 * четверть ширины. Промах тем заметнее, чем длиннее правая сторона, — а она растёт:
 * в «Медиа» рядом с кнопками стоит ещё переключатель режимов.
 *
 * Поэтому свободное место считается по **широкой** стороне и вычитается дважды:
 * центр получает симметричный проём и не наезжает на кнопки даже длинным именем —
 * он обрежется многоточием раньше.
 */
@Composable
private fun SidesAndCenter(
    modifier: Modifier = Modifier,
    start: @Composable () -> Unit,
    center: @Composable () -> Unit,
    end: @Composable () -> Unit,
) = Layout(
    contents = listOf(start, center, end),
    modifier = modifier,
) { (startAt, centerAt, endAt), constraints ->
    val free = constraints.copy(minWidth = 0, minHeight = 0)
    val startOnes = startAt.map { it.measure(free) }
    val endOnes = endAt.map { it.measure(free) }
    val side = maxOf(startOnes.sumOf { it.width }, endOnes.sumOf { it.width })
    val forCenter = (constraints.maxWidth - side * 2).coerceAtLeast(0)
    val centerOnes = centerAt.map { it.measure(free.copy(maxWidth = forCenter)) }

    val all = startOnes + centerOnes + endOnes
    val height = maxOf(all.maxOfOrNull { it.height } ?: 0, constraints.minHeight)
    layout(constraints.maxWidth, height) {
        fun place(one: Placeable, x: Int) = one.placeRelative(x, (height - one.height) / 2)
        var x = 0
        startOnes.forEach { place(it, x); x += it.width }
        centerOnes.forEach { place(it, (constraints.maxWidth - it.width) / 2) }
        x = constraints.maxWidth
        endOnes.asReversed().forEach { x -= it.width; place(it, x) }
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
