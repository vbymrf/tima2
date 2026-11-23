package io.tima.feature.group

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.tima.core.ui.Button
import io.tima.core.ui.ButtonKind
import io.tima.core.ui.Caption
import io.tima.core.ui.Chip
import io.tima.core.ui.ChipKind
import io.tima.core.ui.Secondary
import io.tima.core.ui.SubwindowHeader
import io.tima.core.ui.Tertiary
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.words

/** Метки «Управление → Сообщения» — для сценариев. */
object ManageMessagesTags {
    const val WRITE_SUPER = "messages:write-super"
    const val WRITE_ZERO = "messages:write-zero"
    const val ROW = "messages:row"
    const val EDIT = "messages:edit"
    const val DELETE = "messages:delete"
    const val SUPER = "messages:super"
    const val SLOW = "messages:slow"
}

/** Строка списка: номер, текст, суперсообщение ли, можно ли править (своё). */
data class ShowcaseRow(val id: Long, val text: String, val isSuper: Boolean, val editable: Boolean)

/** Значения медленного режима комментариев канала (СЦ21), секунды. */
val SLOW_STEPS = listOf(0, 30, 60, 300, 900, 3600)

/**
 * «Управление → Сообщения» (ПЛАН-(СЦ) СЦ18, §1.10, 6–9): «Написать суперсообщение» и «Написать сообщение уровня 0»;
 * ниже — написанное: суперсообщение помечено, у каждого «Сделать суперсообщением» или «Снять», «Править» (своё) и
 * «Удалить» (двумя нажатиями). У канала — ещё медленный режим комментариев (СЦ21).
 */
@Composable
fun ManageMessagesScreen(
    title: String,
    /** `null` — не загрузилось. */
    rows: List<ShowcaseRow>?,
    onWriteSuper: () -> Unit,
    onWriteZero: () -> Unit,
    onEdit: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onSuper: (Long, Boolean) -> Unit,
    onBack: () -> Unit,
    /** Медленный режим комментариев канала; `null` — не канал или нет права. */
    slowMode: Int? = null,
    onSlowMode: (Int) -> Unit = {},
    notice: String? = null,
) {
    val w = Tima.words.socium
    var asked by remember { mutableStateOf<Long?>(null) }
    Column(Modifier.fillMaxSize().background(Tima.colors.surface)) {
        SubwindowHeader(title = w.manageMessages, onBack = onBack, caption = title, avatar = title.take(2).uppercase())
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = TimaSpacing.about4)) {
            item(key = "write") {
                FlowRow(
                    Modifier.fillMaxWidth().padding(vertical = TimaSpacing.about3),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(w.writeSuper, onClick = onWriteSuper, modifier = Modifier.testTag(ManageMessagesTags.WRITE_SUPER))
                    Button(w.writeLevelZero, onClick = onWriteZero, kind = ButtonKind.Quiet, modifier = Modifier.testTag(ManageMessagesTags.WRITE_ZERO))
                }
                notice?.let { Secondary(it) }
            }
            if (slowMode != null) {
                item(key = "slow") {
                    Column(Modifier.padding(vertical = TimaSpacing.about2)) {
                        Caption(w.slowModeComments, fontSize = TimaType.sz5, weight = FontWeight.Bold)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (sec in SLOW_STEPS) {
                                Chip(
                                    w.slowLabel(sec),
                                    kind = if (sec == slowMode) ChipKind.Selected else ChipKind.Neutral,
                                    onClick = { onSlowMode(sec) },
                                    modifier = Modifier.testTag(ManageMessagesTags.SLOW),
                                )
                            }
                        }
                    }
                }
            }
            when {
                rows == null -> item(key = "off") { Secondary(Tima.words.trouble.offline) }
                rows.isEmpty() -> item(key = "none") { Tertiary(w.levelZeroNone) }
                else -> items(rows.size, key = { rows[it].id }) { i ->
                    val r = rows[i]
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = TimaSpacing.about2).testTag(ManageMessagesTags.ROW),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (r.isSuper) Caption(w.superMark, fontSize = TimaType.sz6, weight = FontWeight.Bold, color = Tima.colors.confirmed)
                        Caption(r.text.ifBlank { "…" }, fontSize = TimaType.sz4, maxLines = 4)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(
                                if (r.isSuper) w.unsetSuper else w.makeSuper,
                                onClick = { onSuper(r.id, !r.isSuper) },
                                kind = ButtonKind.Quiet,
                                modifier = Modifier.testTag(ManageMessagesTags.SUPER),
                            )
                            if (r.editable) Button(w.editMessage, onClick = { onEdit(r.id) }, kind = ButtonKind.Quiet, modifier = Modifier.testTag(ManageMessagesTags.EDIT))
                            val sure = asked == r.id
                            Button(
                                if (sure) w.deleteSure else w.deleteMessage,
                                onClick = { if (sure) { asked = null; onDelete(r.id) } else asked = r.id },
                                kind = if (sure) ButtonKind.Dangerous else ButtonKind.Quiet,
                                modifier = Modifier.testTag(ManageMessagesTags.DELETE),
                            )
                        }
                    }
                }
            }
        }
    }
}
