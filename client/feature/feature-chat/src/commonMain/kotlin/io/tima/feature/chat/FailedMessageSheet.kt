package io.tima.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.tima.core.ui.Caption
import io.tima.core.ui.IconButton
import io.tima.core.ui.ListLine
import io.tima.core.ui.Name
import io.tima.core.ui.ProvidePlace
import io.tima.core.ui.Secondary
import io.tima.core.ui.Tertiary
import io.tima.core.ui.TextPlace
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.TimaZones
import io.tima.core.ui.words

/**
 * Подокно неотправленного сообщения — решение заказчика 2026-09-19.
 *
 * Сверху — **причина словами и кодом**: слова человеку, код тому, кто получит от него снимок
 * или отчёт — по коду находится строка в `group_messages.go`, по словам нет. Незнакомый код —
 * только код: выдуманные слова хуже честного кода. Причины нет (сообщение старше столбца
 * `fail_reason`) — так и сказано.
 *
 * Ниже три действия: **отправить ещё раз** (та же строка, с текущим кругом — человек уже
 * видел причину и мог сменить круг), **удалить**, **сообщить о проблеме** — отчёт тем же
 * механизмом, что в настройках, с подставленным кодом: за крестиком может стоять наш баг.
 */
@Composable
fun FailedMessageSheet(
    /** Что не отправилось — для напоминания, о чём речь. */
    text: String?,
    /** Код отказа сервера; `null` — не сохранён. */
    reason: String?,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onReport: (() -> Unit)?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Tima.colors
    val words = Tima.words.chat
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.text.copy(alpha = 0.45f))
            .clickable(onClick = onClose),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = TimaZones.zone1)
                .background(colors.surface)
                .clickable(enabled = false) {},
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.functional)
                    .padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about3),
                horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) { ProvidePlace(TextPlace.HEADERS) { Name(words.notSent) } }
                IconButton(glyph = "✕", onClick = onClose)
            }
            Column(
                Modifier.fillMaxWidth().padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about3),
                verticalArrangement = Arrangement.spacedBy(TimaSpacing.about1),
            ) {
                if (!text.isNullOrBlank()) Caption("«$text»", fontSize = TimaType.sz4, color = colors.text2, maxLines = 3)
                when {
                    reason == null -> Secondary(words.notSentNoReason)
                    else -> {
                        val said = words.failReason(reason)
                        // Слова — крупно, код — мелко рядом: оба нужны разным людям.
                        Caption(said ?: reason, fontSize = TimaType.sz5, color = colors.alarm)
                        if (said != null) Tertiary(reason, lineOne = true)
                    }
                }
            }
            ListLine(onClick = onRetry, middle = { Name(words.sendAgain) }, right = { Tertiary("›", lineOne = true) })
            ListLine(onClick = onDelete, middle = { Name(words.deleteMessage) }, right = { Tertiary("›", lineOne = true) })
            if (onReport != null) {
                ListLine(onClick = onReport, middle = { Name(words.reportProblem) }, right = { Tertiary("›", lineOne = true) })
            }
        }
    }
}
