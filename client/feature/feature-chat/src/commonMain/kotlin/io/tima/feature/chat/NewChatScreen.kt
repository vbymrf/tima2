package io.tima.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.tima.core.ui.Trouble
import io.tima.core.ui.Secondary
import io.tima.core.ui.Button
import io.tima.core.ui.Field
import io.tima.core.ui.Caption
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.Tima
import io.tima.core.ui.words
import io.tima.core.ui.SubwindowHeader

/**
 * Новая переписка — подокно.
 *
 * **Подокно, а не окно**, поэтому плашки нет и есть «назад»: человек пришёл сюда из списка
 * и вернётся туда же. Это то самое правило макета, из которого следует вся раскладка: у
 * подокна вопрос «в каком я окне» не стоит.
 *
 * Чистый рендер [НоваяПерепискаState]. Решения — в [НоваяПерепискаStore].
 */
@Composable
fun NewChatScreen(
    state: NewChatState,
    onNumber: (String) -> Unit,
    onFind: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Tima.colors
    val words = Tima.words.chat
    Column(modifier.fillMaxSize().background(colors.surface)) {
        SubwindowHeader(title = words.newChat, onBack = onBack)

        Box(
            modifier = Modifier.fillMaxSize().padding(TimaSpacing.about5),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                // Тот же предел, что у входа: поле во всю ширину ПК читается как поиск.
                modifier = Modifier.widthIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(TimaSpacing.about4),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Caption(words.whomToWrite, fontSize = TimaType.sz2, weight = FontWeight.ExtraBold)
                Secondary(words.phoneInTima)

                Field(
                    value = state.number,
                    onChange = onNumber,
                    hint = "+7…",
                    numeric = true,
                )

                state.trouble?.let { Trouble(it) }

                // Не беда, а предложение: человека, которого нет в TIMA, надо позвать.
                if (state.invite) {
                    Trouble(words.noSuchNumber)
                }

                Button(
                    label = if (state.expect) words.searching else words.find,
                    onClick = onFind,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
