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
import io.tima.core.ui.Аватар
import io.tima.core.ui.ВЦентре
import io.tima.core.ui.Второстепенное
import io.tima.core.ui.Имя
import io.tima.core.ui.КнопкаИконка
import io.tima.core.ui.Поле
import io.tima.core.ui.РядУправления
import io.tima.core.ui.СтрокаСписка
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.Третьестепенное
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
fun ЭкранКниги(
    состояние: КнигаState,
    onПоиск: (String) -> Unit,
    onОткрыть: (Contact) -> Unit,
    modifier: Modifier = Modifier,
    /** «＋» у строки поиска: завести контакт. Экрана нет — кнопки тоже. */
    onДобавить: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = TimaSpacing.о4, vertical = TimaSpacing.о2),
            horizontalArrangement = Arrangement.spacedBy(TimaSpacing.о2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Поле(
                значение = состояние.поиск,
                onИзменение = onПоиск,
                подсказка = "Поиск по книге…",
                modifier = Modifier.weight(1f),
            )
            if (onДобавить != null) {
                РядУправления { КнопкаИконка(знак = "＋", onClick = onДобавить, живая = true) }
            }
        }

        when {
            состояние.ничегоНеНашлось -> ВЦентре(Modifier.fillMaxSize()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Имя("Никого не нашлось")
                    Второстепенное("По «${состояние.поиск}» в книге совпадений нет")
                }
            }

            состояние.все.isEmpty() -> ВЦентре(Modifier.fillMaxSize()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(TimaSpacing.о2),
                    modifier = Modifier.padding(TimaSpacing.о5),
                ) {
                    Имя("В книге пока никого")
                    // Не «нет контактов», а откуда они берутся: иначе человек будет
                    // искать, где их добавить, а добавлять их пока неоткуда.
                    Второстепенное(
                        "Здесь появляются люди, с которыми уже начата переписка. " +
                            "Адресную книгу телефона приложение не читает.",
                    )
                }
            }

            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(состояние.видимые, key = { it.chatId }) { человек ->
                    СтрокаСписка(
                        onClick = { onОткрыть(человек) },
                        слева = { Аватар(буквы = буквы(человек)) },
                        середина = {
                            Column {
                                Имя(человек.name ?: "Без имени")
                                // Идентификатор второй строкой: у человека без имени
                                // это единственное, чем строка отличается от соседней.
                                Третьестепенное(человек.userId, однойСтрокой = true)
                            }
                        },
                    )
                }
            }
        }
    }
}

/** Буквы аватара: первая имени, иначе первая идентификатора. */
private fun буквы(человек: Contact): String =
    (человек.name ?: человек.userId).take(1).uppercase()
