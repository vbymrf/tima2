package io.tima.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.tima.core.ui.Caption
import io.tima.core.ui.IconButton
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.words

/** Метки закреплённого для сценариев (СЦЕНАРИИ-ПО-ОКНАМ.md). */
object PinTags {
    const val BAR = "chat:pinned"
    const val UNPIN = "chat:pinned.close"
    const val MENU_PIN = "msg:pin"
    const val MENU_UNPIN = "msg:unpin"
}

/**
 * Полоса закреплённого под шапкой (ПЛАН-(ЗО) ЗО3): «Закреплено» и строка сообщения; нажатие —
 * переход к нему, ✕ — снять. [text] `null` — сообщение не загружено или удалено: заглушка.
 */
@Composable
fun PinnedBar(text: String?, onOpen: () -> Unit, onUnpin: (() -> Unit)?) {
    val colors = Tima.colors
    val w = Tima.words.showcase
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(colors.functional)
            .clickable(onClick = onOpen)
            .testTag(PinTags.BAR)
            .padding(start = TimaSpacing.about4, end = TimaSpacing.about2, top = TimaSpacing.about1, bottom = TimaSpacing.about1),
        horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(colors.line))
        Column(Modifier.weight(1f)) {
            Caption(w.pinnedCaption, fontSize = TimaType.sz5, color = colors.text2, lineOne = true)
            Caption(text ?: w.pinnedStub, fontSize = TimaType.sz4, maxLines = 1)
        }
        if (onUnpin != null) IconButton(glyph = "✕", onClick = onUnpin, modifier = Modifier.testTag(PinTags.UNPIN))
    }
}
