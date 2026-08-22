package io.tima.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.tima.core.ui.ВидОтметки
import io.tima.core.ui.LocalРаскладка
import io.tima.core.ui.КругКнопка
import io.tima.core.ui.Аватар
import io.tima.core.ui.Второстепенное
import io.tima.core.ui.Имя
import io.tima.core.ui.Отметка
import io.tima.core.ui.ПустаяОбласть
import io.tima.core.ui.РядУправления
import io.tima.core.ui.СГроздью
import io.tima.core.ui.Сторона
import io.tima.core.ui.Стрелка
import io.tima.core.ui.СтрокаСписка
import io.tima.core.ui.Счётчик
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.Третьестепенное
import io.tima.core.ui.Тима
import io.tima.core.ui.ШапкаОкна
import io.tima.domain.chat.ChatSummary
import io.tima.domain.chat.MessageDisplay

/**
 * Окно переписок — «окно 1» из канона, К5.2.
 *
 * Тот же принцип, что у [ЭкранЧата]: **чистый рендер состояния**. Порядок строк, превью и
 * счётчики считает запрос к базе, здесь только вид.
 *
 * Три решения макета, которые здесь важнее вида:
 *
 * 1. **Строка списка — линия, а не карточка.** Между строками линия по нижней границе
 *    самой строки: разделитель отдельным элементом пришлось бы вставлять вызывающему, и
 *    он однажды поставил бы лишний в конце.
 * 2. **Гроздь создания живёт в [СГроздью]** и сама знает, где ей стоять: на телефоне
 *    висит над списком, на широком формате опускается в полосу у нижнего края колонки.
 *    Экран про формат не спрашивает.
 * 3. **Переписка без имени всё равно показывается.** Имя приезжает с профилем, и его
 *    может не быть; спрятать строку значило бы спрятать сообщение.
 */
@Composable
fun ЭкранПереписок(
    состояние: ChatsState,
    onОткрыть: (ChatSummary) -> Unit,
    modifier: Modifier = Modifier,
    onПереключитьОкна: () -> Unit = {},
    onПоиск: () -> Unit = {},
    onНастройки: () -> Unit = {},
    onНовая: () -> Unit = {},
) {
    val цвета = Тима.цвета
    Column(modifier.fillMaxSize().background(цвета.поверхность)) {
        ШапкаОкна(
            название = "Переписки",
            // Логотип есть только на телефоне. На широком формате он живёт в рейке, и
            // второй раз ему в шапке колонки делать нечего — правило `широкий.css`.
            логотип = if (LocalРаскладка.current.телефон) "Т" else null,
            onПереключитьОкна = onПереключитьОкна,
            справа = {
                РядУправления {
                    КругКнопка(onClick = onПоиск) { Стрелка(Сторона.Вправо, цвет = цвета.текст2) }
                    КругКнопка(onClick = onНастройки) { Стрелка(Сторона.Вверх, цвет = цвета.текст2) }
                }
            },
        )

        СГроздью(
            гроздь = {
                КругКнопка(onClick = onНовая, живая = true) {
                    Стрелка(Сторона.Вправо, цвет = цвета.наАкценте)
                }
            },
            подпись = "Написать",
            modifier = Modifier.weight(1f),
        ) {
            when {
                // Пустой список до первого ответа базы — это не «переписок нет».
                // Показывать «переписок нет» в первую секунду после запуска значит врать.
                !состояние.прочитано -> Box(Modifier.fillMaxSize())

                состояние.chats.isEmpty() -> ПустаяОбласть(
                    заголовок = "Переписок пока нет",
                    пояснение = "Напишите первому собеседнику",
                )

                else -> Список(состояние.chats, onОткрыть)
            }
        }
    }
}

@Composable
private fun Список(переписки: List<ChatSummary>, onОткрыть: (ChatSummary) -> Unit) = LazyColumn(
    modifier = Modifier.fillMaxSize(),
) {
    items(переписки, key = { it.chatId }) { переписка ->
        СтрокаПереписки(переписка) { onОткрыть(переписка) }
    }
}

@Composable
private fun СтрокаПереписки(переписка: ChatSummary, onClick: () -> Unit) = СтрокаСписка(
    onClick = onClick,
    слева = { Аватар(буквы(переписка)) },
    справа = {
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(TimaSpacing.о1),
        ) {
            Третьестепенное(время(переписка.atMs))
            // Без fillMaxWidth: в ряду строки этот столбец не взвешен, и растянутый на
            // всю ширину он съедал середину — имя и превью получали нулевую ширину и
            // просто не рисовались. Поймал снимок.
            Box(contentAlignment = Alignment.CenterEnd) {
                // Счётчик и отметка не спорят за место: счётчик — про чужие сообщения,
                // отметка — про своё последнее. Одновременно они бывают редко, и тогда
                // важнее непрочитанное.
                if (переписка.unread > 0) {
                    Счётчик(переписка.unread)
                } else if (переписка.lastOutgoing) {
                    отметка(переписка.lastDisplay)?.let { Отметка(it) }
                }
            }
        }
    },
    середина = {
        // Имени может не быть: профиль не приезжал. Строку это не отменяет — сообщение
        // есть, и человек должен его видеть.
        Имя(переписка.title ?: "Без имени")
        Второстепенное(превью(переписка))
    },
)

/**
 * Превью строки.
 *
 * У неразобранного или нечитаемого входящего текста нет, и вместо него — слова, а не
 * пустота: пустая строка выглядит как поломка списка, а не как состояние сообщения.
 */
private fun превью(переписка: ChatSummary): String = переписка.preview
    ?: when (переписка.lastDisplay) {
        MessageDisplay.UNREADABLE -> "сообщение не читается"
        else -> "новое сообщение"
    }

/**
 * Буквы аватара.
 *
 * Из имени, если оно есть. Без имени — вопросительный знак: он честнее первых букв
 * идентификатора, которые выглядят как имя и им не являются.
 */
private fun буквы(переписка: ChatSummary): String =
    переписка.title?.trim()?.takeIf { it.isNotEmpty() }
        ?.split(" ")
        ?.take(2)
        ?.mapNotNull { it.firstOrNull()?.uppercase() }
        ?.joinToString("")
        ?: "?"

/** Отметка о судьбе последнего своего сообщения. У чужого отметки не бывает. */
private fun отметка(вид: MessageDisplay): ВидОтметки? = when (вид) {
    MessageDisplay.PENDING -> ВидОтметки.Ждёт
    MessageDisplay.SENT -> ВидОтметки.Ушло
    MessageDisplay.FAILED -> ВидОтметки.НеУшло
    MessageDisplay.RECEIVED, MessageDisplay.UNREADABLE -> null
}
