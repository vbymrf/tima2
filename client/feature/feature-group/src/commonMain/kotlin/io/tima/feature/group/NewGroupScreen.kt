package io.tima.feature.group

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.tima.core.ui.Avatar
import io.tima.core.ui.Trouble
import io.tima.core.ui.Secondary
import io.tima.core.ui.Name
import io.tima.core.ui.Button
import io.tima.core.ui.Field
import io.tima.core.ui.Caption
import io.tima.core.ui.ListLine
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.Tima
import io.tima.core.ui.SubwindowHeader

/**
 * Новая группа — подокно.
 *
 * Подокно, а не окно: человек пришёл из списка переписок и вернётся туда же, поэтому есть
 * «назад» и нет плашки.
 *
 * **Непозванные номера показаны отдельно от беды и после создания.** Группа создана, и
 * красный текст про сбой здесь означал бы, что дело не сделано. Дело сделано — просто не
 * всех удалось позвать, и это разные вещи.
 *
 * Чистый рендер [НоваяГруппаState]. Решения — в [НоваяГруппаStore].
 */
@Composable
fun NewGroupScreen(
    state: NewGroupState,
    onTitle: (String) -> Unit,
    onNumber: (String) -> Unit,
    onAddNumber: () -> Unit,
    onRemoveNumber: (String) -> Unit,
    onCreate: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Tima.colors
    Column(modifier.fillMaxSize().background(colors.surface)) {
        SubwindowHeader(title = "Новая группа", onBack = onBack)

        Box(
            modifier = Modifier.fillMaxSize().padding(TimaSpacing.about5),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                // Тот же предел ширины, что у остальных подокон: поле во всю ширину ПК
                // читается как поиск, а не как ввод.
                modifier = Modifier.widthIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(TimaSpacing.about4),
            ) {
                Caption("Как назовём", fontSize = TimaType.sz2, weight = FontWeight.ExtraBold)

                Field(
                    value = state.title,
                    onChange = onTitle,
                    hint = "Название группы",
                )

                Secondary("Кого позвать")

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) {
                        Field(
                            value = state.number,
                            onChange = onNumber,
                            hint = "+7…",
                            numeric = true,
                        )
                    }
                    Button(label = "Добавить", onClick = onAddNumber)
                }

                for (number in state.numbers) {
                    ListLine(
                        left = { Avatar(letters = "№") },
                        right = { Secondary("убрать", Modifier.padding(start = TimaSpacing.about2)) },
                        onClick = { onRemoveNumber(number) },
                        middle = { Name(number) },
                    )
                }

                state.trouble?.let { Trouble(it) }

                Button(
                    label = if (state.expect) "Создаём…" else "Создать группу",
                    onClick = onCreate,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Появляется только после создания: до него говорить о непозванных нечего.
                if (state.notInvited.isNotEmpty()) {
                    Secondary("Группа создана. Этих номеров в TIMA нет — позовите людей:")
                    for (number in state.notInvited) {
                        Name(number)
                    }
                }
            }
        }
    }
}
