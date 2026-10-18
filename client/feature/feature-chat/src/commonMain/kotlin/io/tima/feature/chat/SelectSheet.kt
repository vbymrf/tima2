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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.tima.core.ui.Caption
import io.tima.core.ui.Chip
import io.tima.core.ui.ChipKind
import io.tima.core.ui.Tertiary
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.words

/**
 * Долгое нажатие на сообщение — выделить часть и скопировать (Решения §11.1). Выделение — системное:
 * на телефоне ручки и «Копировать», на ПК — мышью и Ctrl+C. «Скопировать всё» — весь текст разом.
 */
@Composable
fun SelectSheet(text: String, onCopyAll: () -> Unit, onClose: () -> Unit) {
    val colors = Tima.colors
    val w = Tima.words.chat
    Box(
        Modifier.fillMaxSize().background(colors.text.copy(alpha = 0.45f)).clickable(onClick = onClose),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 520.dp).background(colors.surface).clickable(enabled = false) {}
                .padding(TimaSpacing.about4).testTag(SELECT_SHEET_TAG),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Tertiary(w.selectHint)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                SelectionContainer {
                    Caption(text, fontSize = TimaType.sz4, modifier = Modifier.testTag(SELECT_TEXT_TAG))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(w.menuCopy, kind = ChipKind.Selected, onClick = onCopyAll, modifier = Modifier.testTag(SELECT_COPY_TAG))
                Chip(w.cancel, kind = ChipKind.Quiet, onClick = onClose)
            }
        }
    }
}

/** Метки листа выделения (СЦЕНАРИИ-ПО-ОКНАМ). */
const val SELECT_SHEET_TAG = "msg:select"
const val SELECT_TEXT_TAG = "msg:select.text"
const val SELECT_COPY_TAG = "msg:select.copy"
