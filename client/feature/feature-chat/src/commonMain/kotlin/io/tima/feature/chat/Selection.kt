package io.tima.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.tima.core.ui.Caption
import io.tima.core.ui.Chip
import io.tima.core.ui.ChipKind
import io.tima.core.ui.IconButton
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.TimaZones
import io.tima.core.ui.words
import io.tima.domain.chat.ChatLine

/** Метки выбора сообщений (СЦЕНАРИИ-ПО-ОКНАМ, «Переписка»). */
object SelectTags {
    const val MARK = "msg:select.mark"
    const val BAR = "chat:select.bar"
    const val CLOSE = "chat:select.close"
    fun action(key: String) = "chat:select.$key"
}

/**
 * Выбор нескольких сообщений (заказчик 2026-10-19): долгое нажатие ставит галочку — у своих слева,
 * у чужих справа; пока что-то выбрано, обычное нажатие ставит и снимает галочку вместо меню.
 */
class ChatSelection(val keys: Set<String>, val toggle: (ChatLine) -> Unit) {
    val active: Boolean get() = keys.isNotEmpty()
}

val LocalChatSelection = staticCompositionLocalOf<ChatSelection?> { null }

/** Выбирается ли сообщение: служебное и удалённое — нет. */
internal fun selectable(line: ChatLine): Boolean = !line.deleted && (line.text != null || line.rich != null)

/** Галочка выбора рядом с сообщением. */
@Composable
internal fun SelectMark(on: Boolean, onClick: (() -> Unit)? = null) {
    val colors = Tima.colors
    Box(
        // Галочка нажимается сама (заказчик 2026-10-21): по ней выбор ставят и снимают.
        (if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 6.dp).size(26.dp)
            .background(if (on) colors.navigation else colors.surface, CircleShape)
            .border(2.dp, if (on) colors.navigation else colors.line, CircleShape)
            .testTag(SelectTags.MARK),
        contentAlignment = Alignment.Center,
    ) {
        if (on) Caption("✓", fontSize = 15.sp, weight = FontWeight.Black, color = colors.onAccent)
    }
}

/**
 * Полоса выбора на месте строки ввода: сколько выбрано и что с ними сделать — в Подборку,
 * скопировать, переслать, удалить; «✕» — выйти из выбора.
 */
@Composable
fun SelectionBar(
    count: Int,
    onPick: (() -> Unit)?,
    onCopy: () -> Unit,
    onForward: (() -> Unit)?,
    onDelete: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Tima.colors
    val w = Tima.words.chat
    Row(
        modifier.fillMaxWidth().heightIn(min = TimaZones.zone4).background(colors.functional)
            // Полоса закрывает строку ввода целиком: нажатие мимо кнопок не уходит под неё.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
            .padding(horizontal = TimaSpacing.about3, vertical = TimaSpacing.about2)
            .testTag(SelectTags.BAR),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
    ) {
        IconButton(glyph = "✕", onClick = onClose, modifier = Modifier.testTag(SelectTags.CLOSE))
        Caption(w.selected(count), fontSize = TimaType.sz5, weight = FontWeight.Bold, lineOne = true)
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            onPick?.let { Chip(w.selectPick, kind = ChipKind.Quiet, onClick = it, modifier = Modifier.testTag(SelectTags.action("pick"))) }
            Chip(w.selectCopy, kind = ChipKind.Quiet, onClick = onCopy, modifier = Modifier.testTag(SelectTags.action("copy")))
            onForward?.let { Chip(w.selectForward, kind = ChipKind.Quiet, onClick = it, modifier = Modifier.testTag(SelectTags.action("forward"))) }
            Chip(w.selectDelete, kind = ChipKind.Selected, onClick = onDelete, modifier = Modifier.testTag(SelectTags.action("delete")))
        }
    }
}
