package io.tima.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.tima.core.ui.TextPlace
import io.tima.core.ui.ProvidePlace
import io.tima.core.ui.Button
import io.tima.core.ui.ButtonKind
import io.tima.core.ui.Caption
import io.tima.core.ui.IconButton
import io.tima.core.ui.Name
import io.tima.core.ui.Tertiary
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.TimaZones
import io.tima.core.ui.words
import io.tima.domain.chat.FoundMessage

/** Метки листа «сообщение по ссылке» (СЦЕНАРИИ-ПО-ОКНАМ.md). */
object PeekTags {
    const val SHEET = "chat:peek"
    const val OPEN_CHAT = "chat:peek.open"
    const val CLOSE = "chat:peek.close"
}

/**
 * Сообщение по ссылке, которого нет в ленте (Решения §11.4): давнее, из другой переписки, спрятанное
 * продолжение — показывается одно. Недоступное — словами, почему и что сделать.
 */
@Composable
fun PeekSheet(found: FoundMessage, onClose: () -> Unit, onOpenChat: (() -> Unit)?, author: String? = null) {
    val colors = Tima.colors
    val w = Tima.words.showcase
    ProvidePlace(TextPlace.MENU) {
    Box(
        modifier = Modifier.fillMaxSize().background(colors.text.copy(alpha = 0.45f)).clickable(onClick = onClose),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = TimaZones.zone1)
                .background(colors.surface)
                .clickable(enabled = false) {}
                .testTag(PeekTags.SHEET)
                .padding(TimaSpacing.about4),
            verticalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { ProvidePlace(TextPlace.HEADERS) { Name(author ?: w.peekTitle) } }
                IconButton(glyph = "✕", onClick = onClose, modifier = Modifier.testTag(PeekTags.CLOSE))
            }
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                ProvidePlace(TextPlace.MESSAGES) {
                when (found) {
                    is FoundMessage.Found -> {
                        val rich = found.line.rich
                        when {
                            found.line.deleted -> Tertiary(Tima.words.chat.deleted)
                            rich != null -> RichBody(rich, Modifier.fillMaxWidth())
                            else -> Caption(found.line.text.orEmpty(), fontSize = TimaType.sz4)
                        }
                    }
                    is FoundMessage.Unavailable -> Tertiary(w.peekUnavailable(found.reason, found.level))
                    FoundMessage.Offline -> Tertiary(w.peekOffline)
                }
                }
            }
            if (onOpenChat != null && found is FoundMessage.Found) {
                Button(w.peekOpenChat, onOpenChat, Modifier.fillMaxWidth().testTag(PeekTags.OPEN_CHAT), kind = ButtonKind.Quiet)
            }
        }
    }
    }
}
