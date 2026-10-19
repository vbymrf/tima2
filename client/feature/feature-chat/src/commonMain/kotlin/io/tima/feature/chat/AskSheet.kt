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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import io.tima.core.ui.Caption
import io.tima.core.ui.Chip
import io.tima.core.ui.ChipKind
import io.tima.core.ui.Name
import io.tima.core.ui.Tertiary
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType

/**
 * Короткий вопрос над перепиской: ссылка, место руками, контакт руками (ВЫБОР-РЕАЛИЗАЦИИ §11.2а:
 * на ПК книги и места нет; в группе файл — ссылкой на облако). Одно или два поля, «Готово» и
 * «Отмена».
 *
 * @param fields подписи полей; ответ — значения в том же порядке.
 * @param options выбор вида (ключ, подпись) — ссылка текстом или кнопкой, вид файла в облаке;
 *   есть выбор — ключ выбранного идёт в ответ последним.
 */
@Composable
fun AskSheet(
    title: String,
    fields: List<String>,
    ok: String,
    cancel: String,
    onDone: (List<String>) -> Unit,
    onCancel: () -> Unit,
    /** Ошибка ответа словами; `null` — ошибки нет. */
    trouble: String? = null,
    options: List<Pair<String, String>> = emptyList(),
    /** Что ещё спросить под полями — например, цвет ссылки (2026-10-19). */
    extra: (@Composable () -> Unit)? = null,
) {
    val colors = Tima.colors
    var values by remember(fields) { mutableStateOf(fields.map { "" }) }
    var chosen by remember(options) { mutableStateOf(options.firstOrNull()?.first) }
    Box(
        Modifier.fillMaxSize().background(colors.text.copy(alpha = 0.45f)).clickable(onClick = onCancel),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.fillMaxWidth().background(colors.surface).clickable(enabled = false) {}
                .padding(TimaSpacing.about4).testTag("chat:ask"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Name(title)
            fields.forEachIndexed { i, label ->
                Tertiary(label, lineOne = true)
                BasicTextField(
                    value = values[i],
                    onValueChange = { v -> values = values.toMutableList().also { it[i] = v } },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = TimaType.sz4, color = colors.text),
                    cursorBrush = SolidColor(colors.navigation),
                    modifier = Modifier.fillMaxWidth().background(colors.functional, RoundedCornerShape(8.dp))
                        .padding(10.dp).testTag("chat:ask.$i"),
                )
            }
            if (options.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((key, label) in options) {
                        Chip(
                            label,
                            kind = if (key == chosen) ChipKind.Selected else ChipKind.Quiet,
                            onClick = { chosen = key },
                            modifier = Modifier.testTag("chat:ask.option.$key"),
                        )
                    }
                }
            }
            extra?.invoke()
            if (trouble != null) Caption(trouble, fontSize = TimaType.sz5, color = colors.alarm)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(
                    ok,
                    kind = ChipKind.Selected,
                    onClick = { onDone(values.map { it.trim() } + listOfNotNull(chosen)) },
                    modifier = Modifier.testTag("chat:ask.ok"),
                )
                Chip(cancel, kind = ChipKind.Quiet, onClick = onCancel)
            }
        }
    }
}
