package io.tima.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.tima.core.ui.Avatar
import io.tima.core.ui.InCenter
import io.tima.core.ui.Secondary
import io.tima.core.ui.Name
import io.tima.core.ui.IconButton
import io.tima.core.ui.Field
import io.tima.core.ui.ControlRow
import io.tima.core.ui.ListLine
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.Tertiary
import io.tima.domain.chat.Contact

/**
 * Вкладка «Книга» окна «Телефон» — люди, с которыми есть переписка.
 *
 * ── ЧЕГО ЗДЕСЬ НЕТ И ПОЧЕМУ ─────────────────────────────────────────────────
 *
 * **Присутствия нет.** Макет рисует «в сети» и «была 5 минут назад», но сервер
 * присутствие не отдаёт. Нарисовать его — значит показать выдуманное состояние
 * живого человека: хуже, чем не показать вовсе.
 *
 * **Звонка и видео из строки нет.** В макете это два самых частых действия с
 * контактом, и они стоят прямо в строке. Звонки — К7: LiveKit-клиента на стороне
 * приложения ещё нет, и кнопка, которая ничего не делает, обещает больше, чем есть.
 *
 * **Разделов нет.** Книга по макету разложена по тем же разделам, что и чаты;
 * разделы — сквозная механика (`§2`), и её нет ни в одном окне.
 *
 * Поиск постоянной строкой, а не сворачивающейся полосой: так в макете — «Поиск по
 * книге…» стоит внутри содержимого и не прячется (`§1`).
 */
@Composable
fun BookScreen(
    state: BookState,
    onSearch: (String) -> Unit,
    onOpen: (Contact) -> Unit,
    modifier: Modifier = Modifier,
    /** «＋» у строки поиска: завести контакт. Экрана нет — кнопки тоже. */
    onAdd: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2),
            horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Field(
                value = state.search,
                onChange = onSearch,
                hint = "Поиск по книге…",
                modifier = Modifier.weight(1f),
            )
            if (onAdd != null) {
                ControlRow { IconButton(glyph = "＋", onClick = onAdd, live = true) }
            }
        }

        when {
            state.notFoundNothing -> InCenter(Modifier.fillMaxSize()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Name("Никого не нашлось")
                    Secondary("По «${state.search}» в книге совпадений нет")
                }
            }

            state.all.isEmpty() -> InCenter(Modifier.fillMaxSize()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
                    modifier = Modifier.padding(TimaSpacing.about5),
                ) {
                    Name("В книге пока никого")
                    // Не «нет контактов», а откуда они берутся: иначе человек будет
                    // искать, где их добавить, а добавлять их пока неоткуда.
                    Secondary(
                        "Здесь появляются люди, с которыми уже начата переписка. " +
                            "Адресную книгу телефона приложение не читает.",
                    )
                }
            }

            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(state.visible, key = { it.chatId }) { person ->
                    ListLine(
                        onClick = { onOpen(person) },
                        left = { Avatar(letters = letters(person)) },
                        middle = {
                            Column {
                                Name(person.name ?: "Без имени")
                                // Идентификатор второй строкой: у человека без имени
                                // это единственное, чем строка отличается от соседней.
                                Tertiary(person.userId, lineOne = true)
                            }
                        },
                    )
                }
            }
        }
    }
}

/** Буквы аватара: первая имени, иначе первая идентификатора. */
private fun letters(person: Contact): String =
    (person.name ?: person.userId).take(1).uppercase()
