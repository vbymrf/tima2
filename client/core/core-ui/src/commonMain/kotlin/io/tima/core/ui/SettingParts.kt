package io.tima.core.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
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
                if (onClick != null) ExpandMark(open)
            }
        },
    )
}

/**
 * Стрелка нажимаемой строки: «›», раскрытая — остриём вверх.
 *
 * **Крупная и зелёная** (заказчик 2026-10-07: «Делаем стрелку вправо крупнее и зеленой не меняя
 * режим управления кликом. Т.е что было понятно - кликабельно»). Серая мелкая «›» читалась как
 * украшение, и строку с раскрытием не узнавали нажимаемой. Зелёный — цвет навигации и действия,
 * тот же, что у «назад» и кнопок.
 *
 * **Нарисована, а не знаком шрифта** — по той же причине, что [RadioMark]: «›» даже на крупном
 * кегле занимает треть своей клетки и остаётся мелкой, а «⌃» другого шрифтового размера.
 * Раскрытая — та же стрелка, повёрнутая.
 */
@Composable
fun ExpandMark(open: Boolean = false, modifier: Modifier = Modifier) {
    val color = Tima.colors.navigation
    Canvas(modifier.size(EXPAND_WIDTH, EXPAND_HEIGHT).rotate(if (open) -90f else 0f)) {
        val stroke = EXPAND_STROKE.toPx()
        val left = size.width * 0.25f
        val right = size.width * 0.75f
        val top = size.height * 0.5f - (right - left)
        val bottom = size.height * 0.5f + (right - left)
        drawLine(color, Offset(left, top), Offset(right, size.height / 2), stroke, StrokeCap.Round)
        drawLine(color, Offset(right, size.height / 2), Offset(left, bottom), stroke, StrokeCap.Round)
    }
}

/** Мера стрелки: высотой с «?» рядом, черта толщиной с рамку отметки. */
private val EXPAND_WIDTH = 16.dp
private val EXPAND_HEIGHT = 26.dp
private val EXPAND_STROKE = 3.dp

/**
 * Выбор одного из — строкой с раскрытием, как «Экономичный режим» в «Уведомлениях»: справа
 * выбранное и стрелка, нажатие раскрывает варианты с точками на мягкой подложке.
 *
 * @param label надпись варианта; @param about пояснение варианта, `null` — без него
 */
@Composable
fun <T> ChoiceLine(
    glyph: String?,
    title: String,
    about: String?,
    options: List<T>,
    chosen: T,
    label: (T) -> String,
    onChoose: (T) -> Unit,
    modifier: Modifier = Modifier,
    optionAbout: (T) -> String? = { null },
    /** Надпись справа; по умолчанию — надпись выбранного. */
    short: String = label(chosen),
) {
    var open by rememberSaveable { mutableStateOf(false) }
    ListLine(
        modifier = modifier,
        onClick = { open = !open },
        left = glyph?.let { { Name(it) } },
        right = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2)) {
                Secondary(short, lineOne = true)
                ExpandMark(open)
            }
        },
        middle = {
            Caption(title, fontSize = TimaType.sz4, weight = FontWeight.Bold, maxLines = 2)
            about?.let { Tertiary(it) }
        },
    )
    if (!open) return
    Column(Modifier.fillMaxWidth().background(Tima.colors.softAccent)) {
        for (option in options) {
            val on = option == chosen
            ListLine(
                modifier = Modifier.padding(start = TimaSpacing.about5),
                onClick = { onChoose(option) },
                left = { RadioMark(on) },
                middle = {
                    Caption(label(option), fontSize = TimaType.sz4, weight = if (on) FontWeight.Bold else FontWeight.Normal)
                    optionAbout(option)?.let { Tertiary(it) }
                },
            )
        }
    }
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
