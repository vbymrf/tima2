package io.tima.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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

    // Закреплённые группой (ПЛАН-(СЦ) СЦ12).
    const val GROUP_BAR = "chat:pinned.group"
    const val SHEET = "pins:sheet"
    const val SHEET_ITEM = "pins:item"
    const val SHEET_TITLE = "pins:title"
    const val SHEET_CLEAR = "pins:clear"
    const val SHEET_REMOVE = "pins:remove"
    const val CHOICE_ADD = "pins:choice.add"
    const val CHOICE_REPLACE = "pins:choice.replace"
    const val CHOICE_CLEAR = "pins:choice.clear"
    const val CHOICE_SOME = "pins:choice.some"
}

/** Полоса группы закреплённых (СЦ12): «Закреплена группа сообщений.», ниже число и название; справа — число. */
@Composable
fun PinnedGroupBar(count: Int, title: String, onOpen: () -> Unit) {
    val colors = Tima.colors
    val w = Tima.words.socium
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(colors.functional).clickable(onClick = onOpen)
            .testTag(PinTags.GROUP_BAR)
            .padding(start = TimaSpacing.about4, end = TimaSpacing.about4, top = TimaSpacing.about1, bottom = TimaSpacing.about1),
        horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(colors.line))
        Column(Modifier.weight(1f)) {
            Caption(w.pinnedGroup, fontSize = TimaType.sz5, color = colors.text2, lineOne = true)
            Caption("$count · " + title.ifBlank { w.pinsDefaultTitle }, fontSize = TimaType.sz4, maxLines = 1)
        }
        io.tima.core.ui.Counter(count)
    }
}

/**
 * Подокно закреплённых (СЦ12): сверху название (нажатие — сменить, у кого право) и ✕; у сообщения три строки, нажатие —
 * переход; внизу «Удалить группу» и «Удалить сообщения» — первое нажатие даёт галочки, второе удаляет выбранные.
 */
@Composable
fun PinsSheet(
    title: String,
    items: List<Pair<Long, String?>>,
    may: Boolean,
    onOpen: (Long) -> Unit,
    onRemove: ((List<Long>) -> Unit)?,
    onClear: (() -> Unit)?,
    onRename: ((String) -> Unit)?,
    onClose: () -> Unit,
    startChoosing: Boolean = false,
) {
    val colors = Tima.colors
    val w = Tima.words.socium
    var choosing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(startChoosing) }
    var chosen by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(setOf<Long>()) }
    var renaming by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize().background(colors.text.copy(alpha = 0.3f)).clickable(onClick = onClose)) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(colors.surface).clickable(enabled = false) {}
                .padding(TimaSpacing.about4).testTag(PinTags.SHEET),
            verticalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val r = renaming
                if (r != null && onRename != null) {
                    io.tima.core.ui.Field(r, { renaming = it.take(64) }, modifier = Modifier.weight(1f), hint = w.pinsDefaultTitle)
                    io.tima.core.ui.Button(w.save, onClick = { onRename(r.trim()); renaming = null })
                } else {
                    Caption(
                        title.ifBlank { w.pinsDefaultTitle },
                        fontSize = TimaType.sz4,
                        weight = androidx.compose.ui.text.font.FontWeight.Bold,
                        modifier = Modifier.weight(1f).testTag(PinTags.SHEET_TITLE)
                            .clickable(enabled = may && onRename != null) { renaming = title },
                    )
                }
                IconButton(glyph = "✕", onClick = onClose)
            }
            androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().height(320.dp)) {
                items(items.size) { i ->
                    val (id, text) = items[i]
                    Row(
                        Modifier.fillMaxWidth().clickable { if (choosing) chosen = if (id in chosen) chosen - id else chosen + id else onOpen(id) }
                            .padding(vertical = TimaSpacing.about2).testTag(PinTags.SHEET_ITEM),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
                    ) {
                        if (choosing) io.tima.core.ui.CheckMark(id in chosen)
                        Caption(text ?: Tima.words.showcase.pinnedStub, fontSize = TimaType.sz4, maxLines = 3)
                    }
                }
            }
            if (may) {
                Row(horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about3)) {
                    onClear?.let { io.tima.core.ui.Button(w.pinsDeleteGroup, onClick = it, kind = io.tima.core.ui.ButtonKind.Quiet, modifier = Modifier.testTag(PinTags.SHEET_CLEAR)) }
                    onRemove?.let { remove ->
                        io.tima.core.ui.Button(
                            if (choosing) w.pinsDeleteChosen(chosen.size) else w.pinsDeleteSome,
                            onClick = {
                                if (!choosing) choosing = true else if (chosen.isNotEmpty()) { remove(chosen.toList()); chosen = emptySet(); choosing = false }
                            },
                            kind = io.tima.core.ui.ButtonKind.Quiet,
                            modifier = Modifier.testTag(PinTags.SHEET_REMOVE),
                        )
                    }
                }
            }
        }
    }
}

/** «Закрепить», когда уже закреплено (СЦ12): «Добавить · Заменить · Удалить все · Удалить часть · Отмена». */
@Composable
fun PinChoice(full: Boolean, onAdd: () -> Unit, onReplace: () -> Unit, onClear: () -> Unit, onSome: () -> Unit, onCancel: () -> Unit) {
    val colors = Tima.colors
    val w = Tima.words.socium
    Box(Modifier.fillMaxSize().background(colors.text.copy(alpha = 0.3f)).clickable(onClick = onCancel)) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(colors.surface).clickable(enabled = false) {}.padding(TimaSpacing.about4),
            verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Caption(w.pinsAlready, fontSize = TimaType.sz4, weight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(glyph = "✕", onClick = onCancel)
            }
            if (full) Caption(w.pinsFull, fontSize = TimaType.sz5, color = colors.text2)
            io.tima.core.ui.Button(w.pinsAdd, onClick = onAdd, enabled = !full, modifier = Modifier.fillMaxWidth().testTag(PinTags.CHOICE_ADD))
            io.tima.core.ui.Button(w.pinsReplace, onClick = onReplace, kind = io.tima.core.ui.ButtonKind.Quiet, modifier = Modifier.fillMaxWidth().testTag(PinTags.CHOICE_REPLACE))
            io.tima.core.ui.Button(w.pinsClearAll, onClick = onClear, kind = io.tima.core.ui.ButtonKind.Quiet, modifier = Modifier.fillMaxWidth().testTag(PinTags.CHOICE_CLEAR))
            io.tima.core.ui.Button(w.pinsDeleteSome, onClick = onSome, kind = io.tima.core.ui.ButtonKind.Quiet, modifier = Modifier.fillMaxWidth().testTag(PinTags.CHOICE_SOME))
            io.tima.core.ui.Button(w.cancel, onClick = onCancel, kind = io.tima.core.ui.ButtonKind.Quiet, modifier = Modifier.fillMaxWidth())
        }
    }
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
