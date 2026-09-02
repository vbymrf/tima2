package io.tima.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Круглые управляющие элементы: кнопки, чипы, счётчики.
 *
 * Вторая половина правила формы: **круг означает «нажми»**. Счётчик при этом круглый,
 * хотя не нажимается, — и это осознанное исключение из макета: он пилюля, потому что
 * живёт в одном ряду с чипами и кнопками, а квадратный счётчик читался бы аватаром.
 */

/** Толщина обводки у «контурных» кнопок: `.кн.контур` — 2 px. */
private val OUTLINE = 2.dp

/**
 * Кнопка. Салатовая заливка — **навигация и действие**: «отправить», «написать»,
 * «назад», «подписаться».
 */
@Composable
fun Button(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Action,
) {
    val colors = Tima.colors
    val background = when (kind) {
        ButtonKind.Action -> colors.navigation
        ButtonKind.Quiet -> colors.softAccent
        // Опасное — БЕЗ ЦВЕТА: красного в палитре нет вовсе. Остаются слово,
        // незаполненная кнопка и последнее место в списке.
        ButtonKind.Dangerous -> Color.Transparent
    }
    val colorLabel = when (kind) {
        ButtonKind.Action -> colors.onAccent
        else -> colors.text
    }

    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 42.dp)
            .background(background, CircleShape)
            .then(
                if (kind == ButtonKind.Dangerous) {
                    Modifier.border(OUTLINE, colors.text, CircleShape)
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = TimaSpacing.about2),
        contentAlignment = Alignment.Center,
    ) {
        Caption(label, fontSize = TimaType.sz5, weight = FontWeight.Bold, color = colorLabel)
    }
}

/**
 * Виды кнопок из макета.
 *
 * Опасного действия **красным цветом не бывает**: красного в палитре нет. Отличается
 * оно словом, незаполненной кнопкой и местом — последним в списке.
 */
enum class ButtonKind { Action, Quiet, Dangerous }

/**
 * Кнопка-иконка: круг 36 px. `.икона`.
 *
 * @param живая главное действие — салатовая. Внутри салатовой плашки шапки такая
 *   кнопка становится белой: салатовое на салатовом не видно, и это решено в
 *   [ШапкаОкна], а не здесь.
 */
@Composable
fun IconButton(
    glyph: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    live: Boolean = false,
    background: Color? = null,
    colorGlyph: Color? = null,
) {
    val colors = Tima.colors
    Box(
        modifier = modifier
            .size(36.dp)
            .background(background ?: circleFill(live), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Caption(
            text = glyph,
            fontSize = TimaType.sz4,
            weight = FontWeight.Bold,
            color = colorGlyph ?: glyphColor(live),
        )
    }
}

/**
 * Стоим ли мы внутри салатовой плашки шапки.
 *
 * Правило макета — две строки: `.икона { background: var(--чёрный-6) }` и
 * `.зона-1 .икона { background: #ffffff }`. Салатовое на салатовом не видно, а серый
 * `--чёрный-6` на салатовом виден немногим лучше: шесть процентов чёрного поверх
 * зелёного дают почти тот же зелёный. **Внутри плашки круг белый, иначе круга нет
 * вовсе** — а без круга кнопка перестаёт выглядеть кнопкой, и заказчик увидел это
 * глазами 2026-09-02: «кнопки в шапке нужно разместить на своём круглом фоне».
 *
 * Признаком, а не параметром: правило принадлежит плашке, а не тому, кто кладёт в неё
 * кнопки. Параметром его пришлось бы передавать через каждое окно, и первый же
 * забывший вернул бы кнопку без круга.
 */
val LocalInPlate = staticCompositionLocalOf { false }

/**
 * Заливка круглой кнопки: белая в плашке, салатовая у главного действия, иначе серая.
 *
 * Серый здесь `--чёрный-6` — **тот же, что у невыбранной подвкладки**. Это не совпадение
 * и не экономия токена: в макете `.икона` и `.чип` берут одну и ту же тихую подложку,
 * и «как фон подвкладки» — ровно то, как заказчик её и назвал.
 */
/**
 * Цвет знака на круглой кнопке.
 *
 * **Внутри плашки он считается от заливки, а не берётся у темы.** Круг там белый, а
 * `colors.text` в тёмной теме тоже белый — знак пропадал целиком. Заливка круга при этом
 * человеку открыта («Внутри плашки» в «Оформлении»), поэтому и цвет знака обязан
 * считаться, а не быть выбранным заранее: см. [TimaContrast.readable].
 */
@Composable
private fun glyphColor(live: Boolean): Color {
    val colors = Tima.colors
    return when {
        LocalInPlate.current -> TimaContrast.readable(on = colors.inPlate, under = colors.navigation)
        live -> colors.onAccent
        else -> colors.text
    }
}

@Composable
private fun circleFill(live: Boolean): Color {
    val colors = Tima.colors
    return when {
        // Плашка сильнее «живости»: `.зона-1 .икона.жив { background: #ffffff }`.
        LocalInPlate.current -> colors.inPlate
        live -> colors.navigation
        else -> colors.quiet
    }
}

/**
 * Круглая кнопка с **рисунком** вместо знака: стрелка «назад», стрелка «отправить».
 *
 * Та же кнопка, что [КнопкаИконка], и отличается только тем, что внутри. Знаки, которых
 * нет в шрифте, рисуются (Знаки.kt), и такой кнопке нужно место под рисунок, а не под
 * строку.
 */
@Composable
fun ButtonCircle(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    live: Boolean = false,
    background: Color? = null,
    drawing: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .size(36.dp)
            .background(background ?: circleFill(live), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { drawing() }
}

/**
 * Чип: короткая пилюля. `.чип`.
 *
 * Значение несёт заливка, а не форма: салатовая — выбранное, зелёная — подтверждённое
 * (E2E), тихая — обычная пометка.
 */
@Composable
fun Chip(
    label: String,
    modifier: Modifier = Modifier,
    kind: ChipKind = ChipKind.Quiet,
    onClick: (() -> Unit)? = null,
) {
    val colors = Tima.colors
    val background = when (kind) {
        ChipKind.Quiet -> colors.softAccent
        ChipKind.Neutral -> colors.quiet
        ChipKind.Selected -> colors.navigation
        ChipKind.Confirmed -> colors.confirmed
    }
    val labelColor = when (kind) {
        ChipKind.Quiet, ChipKind.Neutral -> colors.text2
        else -> colors.onAccent
    }

    Box(
        modifier = modifier
            .background(background, CircleShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Caption(label, fontSize = TimaType.sz6, weight = FontWeight.Bold, color = labelColor)
    }
}

/**
 * Вкладка окна. `.таб`.
 *
 * **Невыбранная вкладка — слово без заливки.** В этом всё её отличие от чипа, и оно
 * рабочее: ряд вкладок и ряд подвкладок стоят друг под другом, и если оба набраны
 * залитыми пилюлями, человек видит один сплошной ряд из шести кнопок. Заказчик прочёл
 * это ровно так 2026-09-02: «второй ряд полностью повторяет первый».
 *
 * До того дня вкладка была [Chip] вида [ChipKind.Quiet] — залитая тихим оттенком
 * навигации, ростом и отступами с чип. Макет с самого начала говорил другое:
 *
 * ```css
 * .таб     { padding: 6px 14px; font-size: var(--щ5); color: var(--чёрный-50) }
 * .таб.тек { background: var(--навигация); color: #ffffff }
 * ```
 *
 * Ни фона, ни рамки у невыбранной — и размер крупнее чипового. Расходился код, а не
 * макет, поэтому чинится код.
 */
@Composable
fun Tab(
    label: String,
    current: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Tima.colors
    Box(
        modifier = modifier
            .background(if (current) colors.navigation else Color.Transparent, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Caption(
            text = label,
            fontSize = TimaType.sz5,
            weight = FontWeight.Bold,
            color = if (current) colors.onAccent else colors.text2,
        )
    }
}

/**
 * Виды чипа.
 *
 * [Quiet] и [Neutral] различаются только оттенком подложки, и различие это рабочее:
 * тихая подложка несёт оттенок навигации, нейтральная — нет. Ряд вкладок набран
 * первой, ряд подвкладок под ним — второй, и человек видит, какой из двух рядов
 * главнее, не читая надписей. Решение заказчика 2026-09-02.
 */
enum class ChipKind { Quiet, Neutral, Selected, Confirmed }

/**
 * Счётчик непрочитанного — **янтарь**. `.счёт`.
 *
 * Янтарь означает активность: новые сообщения, комментарии, непрочитанное. Текст на
 * нём чёрный в обеих темах: 12,32 : 1, и терять этот контраст не за что — янтарь не
 * зелёный, и правило про текст на заливке к нему не относится.
 */
@Composable
fun Counter(howMany: Int, modifier: Modifier = Modifier) {
    if (howMany <= 0) return
    val colors = Tima.colors
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 22.dp, minHeight = 22.dp)
            .background(colors.activity, CircleShape)
            .padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Caption(
            // Больше 99 показывать незачем: точное число не меняет решения человека,
            // а ширину пилюли меняет.
            text = if (howMany > 99) "99+" else howMany.toString(),
            fontSize = TimaType.sz6,
            weight = FontWeight.ExtraBold,
            color = colors.onAmber,
        )
    }
}

/** Ряд управляющих элементов с одинаковым зазором: чипы, кнопки шапки. */
@Composable
fun ControlRow(
    modifier: Modifier = Modifier,
    gap: androidx.compose.ui.unit.Dp = TimaSpacing.about2,
    content: @Composable () -> Unit,
) = Row(
    modifier = modifier,
    horizontalArrangement = Arrangement.spacedBy(gap),
    verticalAlignment = Alignment.CenterVertically,
) { content() }
