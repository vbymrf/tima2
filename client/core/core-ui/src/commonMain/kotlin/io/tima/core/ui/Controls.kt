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
            .background(background ?: if (live) colors.navigation else colors.softAccent, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Caption(
            text = glyph,
            fontSize = TimaType.sz4,
            weight = FontWeight.Bold,
            color = colorGlyph ?: if (live) colors.onAccent else colors.text,
        )
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
    val colors = Tima.colors
    Box(
        modifier = modifier
            .size(36.dp)
            .background(background ?: if (live) colors.navigation else colors.softAccent, CircleShape)
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
        ChipKind.Selected -> colors.navigation
        ChipKind.Confirmed -> colors.confirmed
    }
    val labelColor = when (kind) {
        ChipKind.Quiet -> colors.text2
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

enum class ChipKind { Quiet, Selected, Confirmed }

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
