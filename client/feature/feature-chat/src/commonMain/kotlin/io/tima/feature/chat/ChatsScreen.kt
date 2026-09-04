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
import io.tima.core.ui.MarkKind
import io.tima.core.ui.LayoutLocal
import io.tima.core.ui.ButtonCircle
import io.tima.core.ui.Avatar
import io.tima.core.ui.Secondary
import io.tima.core.ui.Name
import io.tima.core.ui.Mark
import io.tima.core.ui.EmptyArea
import io.tima.core.ui.ControlRow
import io.tima.core.ui.WithCluster
import io.tima.core.ui.Side
import io.tima.core.ui.Arrow
import io.tima.core.ui.ListLine
import io.tima.core.ui.Counter
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.Tertiary
import io.tima.core.ui.Tima
import io.tima.core.ui.WindowHeader
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
fun ChatsScreen(
    state: ChatsState,
    onOpen: (ChatSummary) -> Unit,
    modifier: Modifier = Modifier,
    onSettings: () -> Unit = {},
    onNew: () -> Unit = {},
) {
    val colors = Tima.colors
    Column(modifier.fillMaxSize().background(colors.surface)) {
        // ── ШАПКИ ЗДЕСЬ БОЛЬШЕ НЕТ ───────────────────────────────────────────
        //
        // Экран стал содержимым вкладки «Чаты», а шапку окна рисует общий каркас:
        // одна на все пять окон. Пока экран был единственным, шапка жила в нём, и
        // это было верно ровно до второго окна.
        //
        // ── ВХОДА «ГРУППА» ЗДЕСЬ БОЛЬШЕ НЕТ ──────────────────────────────────
        //
        // Внизу списка стоял второй вход — салатовый чип «Группа», открывавший мастер
        // создания группы. Убран 2026-09-03 решением заказчика: **в макете его нет**.
        // `Layout-UI-light/пк/телефон.html`, низ колонки — там ровно один круг с
        // подписью «Написать», и на телефоне тоже один.
        //
        // Появился он тогда, когда графического интерфейса ещё не было и класть вход
        // было некуда. Это его и объясняет: не замысел, а след времени, когда макета
        // не существовало.
        //
        // **Мастер создания группы при этом никуда не делся** — он собран, работает и
        // остался без входа. Так и записано в `doc_mig/ИНТЕРФЕЙС/02-телефон/`, в
        // разделе «нет входа»: построенное, но недостижимое, обязано числиться
        // отдельно от готового. Место входа — каталог окна 2, там же, где остальные
        // группы и каналы.
        WithCluster(
            cluster = {
                ButtonCircle(onClick = onNew, live = true) {
                    Arrow(Side.Right, color = colors.onAccent)
                }
            },
            caption = "Написать",
            modifier = Modifier.weight(1f),
        ) {
            when {
                // Пустой список до первого ответа базы — это не «переписок нет».
                // Показывать «переписок нет» в первую секунду после запуска значит врать.
                !state.read -> Box(Modifier.fillMaxSize())

                state.chats.isEmpty() -> EmptyArea(
                    title = "Переписок пока нет",
                    explanation = "Напишите первому собеседнику",
                )

                else -> List(state.chats, onOpen)
            }
        }
    }
}

@Composable
private fun List(chats: List<ChatSummary>, onOpen: (ChatSummary) -> Unit) = LazyColumn(
    modifier = Modifier.fillMaxSize(),
) {
    items(chats, key = { it.chatId }) { chat ->
        ChatLine(chat) { onOpen(chat) }
    }
}

@Composable
private fun ChatLine(chat: ChatSummary, onClick: () -> Unit) = ListLine(
    onClick = onClick,
    left = { Avatar(letters(chat)) },
    right = {
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(TimaSpacing.about1),
        ) {
            // Времени может не быть вовсе: у пустой переписки нет последнего сообщения.
            // Ставить сюда 1970 год или «—» незачем — пустое место говорит то же самое и
            // не спорит с именем за внимание.
            chat.atMs?.let {
                // Время в строке списка не переносится: строка списка держит высоту.
                Tertiary(time(it), lineOne = true)
            }
            // Без fillMaxWidth: в ряду строки этот столбец не взвешен, и растянутый на
            // всю ширину он съедал середину — имя и превью получали нулевую ширину и
            // просто не рисовались. Поймал снимок.
            Box(contentAlignment = Alignment.CenterEnd) {
                // Счётчик и отметка не спорят за место: счётчик — про чужие сообщения,
                // отметка — про своё последнее. Одновременно они бывают редко, и тогда
                // важнее непрочитанное.
                if (chat.unread > 0) {
                    Counter(chat.unread)
                } else if (chat.lastOutgoing) {
                    chat.lastDisplay?.let { mark(it) }?.let { Mark(it) }
                }
            }
        }
    },
    middle = {
        // Имени может не быть: профиль не приезжал. Строку это не отменяет — сообщение
        // есть, и человек должен его видеть.
        Name(chat.title ?: "Без имени")
        // Превью обрезается: иначе строка списка растёт от чужого длинного сообщения.
        Secondary(preview(chat), lineOne = true)
    },
)

/**
 * Превью строки.
 *
 * У неразобранного или нечитаемого входящего текста нет, и вместо него — слова, а не
 * пустота: пустая строка выглядит как поломка списка, а не как состояние сообщения.
 */
private fun preview(chat: ChatSummary): String = chat.preview
    ?: when (chat.lastDisplay) {
        MessageDisplay.UNREADABLE -> "сообщение не читается"
        else -> "новое сообщение"
    }

/**
 * Буквы аватара.
 *
 * Из имени, если оно есть. Без имени — вопросительный знак: он честнее первых букв
 * идентификатора, которые выглядят как имя и им не являются.
 */
private fun letters(chat: ChatSummary): String =
    chat.title?.trim()?.takeIf { it.isNotEmpty() }
        ?.split(" ")
        ?.take(2)
        ?.mapNotNull { it.firstOrNull()?.uppercase() }
        ?.joinToString("")
        ?: "?"

/** Отметка о судьбе последнего своего сообщения. У чужого отметки не бывает. */
private fun mark(kind: MessageDisplay): MarkKind? = when (kind) {
    MessageDisplay.PENDING -> MarkKind.Waits
    MessageDisplay.SENT -> MarkKind.Left
    MessageDisplay.FAILED -> MarkKind.NotLeft
    // Служебная строка не отправлялась — отмечать у неё нечего.
    MessageDisplay.RECEIVED, MessageDisplay.UNREADABLE, MessageDisplay.SYSTEM -> null
}
