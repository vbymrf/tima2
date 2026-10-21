package io.tima.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.tima.core.ui.Caption
import io.tima.core.ui.Chip
import io.tima.core.ui.ChipKind
import io.tima.core.ui.Name
import io.tima.core.ui.Tertiary
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType

/** Строка списка выбора: что видно и строка под ним. */
data class Choice(val title: String, val about: String = "")

/**
 * Выбор из списка над окном — на какое сообщение ответить, что вставить из подборки (Решения §10.4).
 * Пустой список — строка [empty] вместо него.
 */
@Composable
fun ChoiceSheet(title: String, items: List<Choice>, empty: String, onPick: (Int) -> Unit, onCancel: () -> Unit) {
    val colors = Tima.colors
    Box(
        Modifier.fillMaxSize().background(colors.text.copy(alpha = 0.45f)).clickable(onClick = onCancel),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 480.dp).background(colors.surface).clickable(enabled = false) {}
                .padding(TimaSpacing.about4).testTag("chat:choice"),
        ) {
            Name(title)
            if (items.isEmpty()) Tertiary(empty)
            Column(Modifier.verticalScroll(rememberScrollState())) {
                items.forEachIndexed { i, item ->
                    Column(Modifier.fillMaxWidth().clickable { onPick(i) }.padding(vertical = 8.dp).testTag("chat:choice.$i")) {
                        Caption(item.title, fontSize = TimaType.sz4, lineOne = true)
                        if (item.about.isNotBlank()) Tertiary(item.about, lineOne = true)
                    }
                }
            }
        }
    }
}

/**
 * Выбор нескольких (подкладка, ADR-0050 §1): отметить сообщения, «Списком» — да или нет,
 * «Вставить». Не больше [max], «Списком» — не больше [maxList]. [head] — отдельное действие над
 * списком (новое продолжение своего уровня в группе).
 */
@Composable
fun ManyChoiceSheet(
    title: String,
    items: List<Choice>,
    empty: String,
    max: Int,
    maxList: Int,
    asList: String,
    insert: String,
    picked: (n: Int, max: Int) -> String,
    head: Choice? = null,
    onHead: () -> Unit = {},
    onPick: (indices: List<Int>, asList: Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    val colors = Tima.colors
    val chosen = remember { mutableStateListOf<Int>() }
    var list by remember { mutableStateOf(false) }
    val limit = if (list) maxList else max
    Box(
        Modifier.fillMaxSize().background(colors.text.copy(alpha = 0.45f)).clickable(onClick = onCancel),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 520.dp).background(colors.surface).clickable(enabled = false) {}
                .padding(TimaSpacing.about4).testTag("chat:many"),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Name(title)
            head?.let { h ->
                Column(Modifier.fillMaxWidth().clickable(onClick = onHead).padding(vertical = 8.dp).testTag("chat:many.head")) {
                    Caption(h.title, fontSize = TimaType.sz4, weight = FontWeight.Bold, lineOne = true)
                    if (h.about.isNotBlank()) Tertiary(h.about, lineOne = true)
                }
            }
            if (items.isEmpty()) Tertiary(empty)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                items.forEachIndexed { i, item ->
                    val on = i in chosen
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            if (on) chosen.remove(i) else if (chosen.size < limit) chosen.add(i)
                        }.padding(vertical = 8.dp).testTag("chat:many.$i"),
                        horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
                    ) {
                        Caption(if (on) "☑" else "☐", fontSize = TimaType.sz4, color = if (on) colors.navigation else colors.text3)
                        Column(Modifier.weight(1f)) {
                            Caption(item.title, fontSize = TimaType.sz4, lineOne = true)
                            if (item.about.isNotBlank()) Tertiary(item.about, lineOne = true)
                        }
                    }
                }
            }
            if (items.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2)) {
                    // Пусто — выбора «Списком» нет (подборка: несколько и так встают списком).
                    if (asList.isNotBlank()) Chip(asList, kind = if (list) ChipKind.Selected else ChipKind.Neutral, onClick = {
                        list = !list
                        // Без «Списком» — не больше [max]: лишние отметки снимаются с конца.
                        while (!list && chosen.size > max) chosen.removeAt(chosen.lastIndex)
                    }, modifier = Modifier.testTag("chat:many.list"))
                    Chip(insert, kind = ChipKind.Selected, onClick = { if (chosen.isNotEmpty()) onPick(chosen.toList(), list) },
                        modifier = Modifier.testTag("chat:many.ok"))
                }
                Tertiary(picked(chosen.size, limit), lineOne = true)
            }
        }
    }
}
