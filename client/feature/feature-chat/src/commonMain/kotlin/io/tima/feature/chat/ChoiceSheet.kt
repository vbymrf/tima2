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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.tima.core.ui.Caption
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
