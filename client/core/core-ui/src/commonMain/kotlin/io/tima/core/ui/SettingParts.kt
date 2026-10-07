package io.tima.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Части экрана настроек с группами (ПЛАН-(ПН)-ПИН-КОДА §4, пробы `пробы-пин-код.html`, приняты
 * заказчиком 2026-10-07): строка с «?», пузырь состояния, подокно «?», плашка подтверждения.
 */

/** Цвет пузыря состояния: подложкой, текст всегда чёрный (заказчик 2026-10-07). */
enum class PillTone { Plain, Good, Waiting, Danger }

/**
 * Пузырь состояния — «заверено», «выключен», «ждёт подтверждения».
 *
 * **На своей строке под описанием, а не справа** (заказчик 2026-10-07): справа он ужимал бы
 * название, а крупный шрифт из «Шрифты и размеры» ломал бы строку.
 */
@Composable
fun StatusPill(text: String, tone: PillTone, modifier: Modifier = Modifier) {
    val colors = Tima.colors
    val fill = when (tone) {
        PillTone.Plain -> colors.line
        PillTone.Good -> colors.softAccent
        PillTone.Waiting -> colors.activity.copy(alpha = 0.30f)
        PillTone.Danger -> colors.alarm.copy(alpha = 0.14f)
    }
    Caption(
        text,
        modifier
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(50))
            .background(fill)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        fontSize = TimaType.sz6,
        weight = FontWeight.Bold,
        color = colors.text,
    )
}

/**
 * Строка настройки: значок, название, что это, пузырь состояния; справа «?» и стрелка.
 *
 * @param danger строка раздела «Аккаунт» — название красным.
 * @param onHelp `null` — знака «?» нет.
 * @param open раскрыта ли строка — стрелка вниз.
 */
@Composable
fun SettingLine(
    glyph: String,
    title: String,
    about: String?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    pill: String? = null,
    pillTone: PillTone = PillTone.Plain,
    danger: Boolean = false,
    onHelp: (() -> Unit)? = null,
    open: Boolean = false,
) {
    val colors = Tima.colors
    ListLine(
        modifier = modifier,
        onClick = onClick,
        left = { Name(glyph) },
        middle = {
            Caption(title, fontSize = TimaType.sz4, weight = FontWeight.Bold, color = if (danger) colors.alarm else colors.text, maxLines = 3)
            about?.let { Secondary(it) }
            pill?.let { StatusPill(it, pillTone) }
        },
        right = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2)) {
                onHelp?.let { HelpMark(it) }
                if (onClick != null) Secondary(if (open) "⌃" else "›")
            }
        },
    )
}

/** Знак «?» — кружок; открывает подокно с описанием. */
@Composable
fun HelpMark(onClick: () -> Unit) {
    val colors = Tima.colors
    Box(
        Modifier
            .size(26.dp)
            .clip(CircleShape)
            .border(1.5.dp, colors.text3, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Caption("?", fontSize = TimaType.sz5, weight = FontWeight.ExtraBold, color = colors.text2)
    }
}

/**
 * Подокно «?» — лист снизу поверх затемнённого экрана: что делает и что получится. Ничего не
 * меняет; закрывается кнопкой или нажатием мимо. Ставится последним в `Box` экрана.
 */
@Composable
fun BoxScope.HelpSheet(
    title: String,
    does: String,
    result: String,
    labels: HelpLabels,
    danger: Boolean,
    onClose: () -> Unit,
) {
    val colors = Tima.colors
    Box(
        Modifier
            .matchParentSize()
            .background(colors.text.copy(alpha = 0.38f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
    )
    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
            .background(colors.surface)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about4),
        verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
    ) {
        Caption(title, fontSize = TimaType.sz3, weight = FontWeight.ExtraBold, color = if (danger) colors.alarm else colors.text)
        Tertiary(labels.does.uppercase())
        Secondary(does)
        Tertiary(labels.result.uppercase())
        Secondary(result)
        Spacer(Modifier.height(TimaSpacing.about1))
        Button(label = labels.ok, onClick = onClose, modifier = Modifier.fillMaxWidth())
    }
}

/** Подписи подокна «?» — из словаря вызывающего. */
data class HelpLabels(val does: String, val result: String, val ok: String)

/**
 * Плашка подтверждения на месте нажатого пункта: итог, что будет, «Отмена» и действие.
 *
 * @param danger раздел «Аккаунт» — красная, действие красной кнопкой; иначе спокойная.
 */
@Composable
fun ConfirmPlate(
    title: String,
    text: String,
    cancel: String,
    action: String,
    danger: Boolean,
    onCancel: () -> Unit,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Tima.colors
    val accent = if (danger) colors.alarm else colors.activity
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2)
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(TimaShapes.smallRadius))
            .background(accent.copy(alpha = if (danger) 0.08f else 0.16f)),
    ) {
        Spacer(Modifier.width(4.dp).fillMaxHeight().background(accent))
        Column(
            Modifier.padding(horizontal = TimaSpacing.about3, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
        ) {
            Caption(title, fontSize = TimaType.sz4, weight = FontWeight.ExtraBold, color = if (danger) colors.alarm else colors.text)
            Caption(text, fontSize = TimaType.sz5, color = colors.text)
            Row(horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2)) {
                Button(label = cancel, onClick = onCancel, kind = ButtonKind.Quiet, modifier = Modifier.weight(1f))
                Button(label = action, onClick = onAction, kind = if (danger) ButtonKind.Dangerous else ButtonKind.Action, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** Слой поверх экрана — для подокна «?». */
@Composable
fun Layered(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) =
    Box(modifier.fillMaxSize(), content = content)
