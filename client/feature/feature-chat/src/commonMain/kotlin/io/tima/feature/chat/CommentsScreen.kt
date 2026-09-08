package io.tima.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.tima.core.ui.Avatar
import io.tima.core.ui.AvatarSize
import io.tima.core.ui.Chip
import io.tima.core.ui.ChipKind
import io.tima.core.ui.EmptyArea
import io.tima.core.ui.Field
import io.tima.core.ui.IconButton
import io.tima.core.ui.ListLine
import io.tima.core.ui.Name
import io.tima.core.ui.Secondary
import io.tima.core.ui.SubwindowHeader
import io.tima.core.ui.Tertiary
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.Trouble
import io.tima.domain.chat.CommentEntry
import io.tima.domain.chat.MessageCircle

/**
 * Подокно «Комментарии» (макет `подокна/комментарии.html`, ADR-0024, ПЛАН-КАНАЛОВ К5).
 *
 * **Круг сказан один раз строкой и не предлагается к смене.** У комментария своего круга
 * нет: он берётся у корня. Показать здесь выбор круга значило бы предложить действие,
 * которого не существует.
 *
 * **Закрытое обсуждение сказано словами.** Пустой список вместо ответа читается как
 * поломка приложения; «обсуждение закрыто, написанное раньше осталось» — как решение
 * владельца, чем оно и является.
 *
 * **Предупреждения «уйдёт в канал» здесь нет** — решение заказчика 2026-09-08: человек и
 * так видит, что комментирует чужую запись.
 *
 * **Экран не решает, можно ли писать.** Право совпадает с видимостью (ADR-0024 §8), и
 * видимость уже случилась — запись показана. Поле ввода прячется только там, где писать
 * действительно некуда: обсуждение закрыто или записи больше нет.
 */
@Composable
fun CommentsScreen(
    state: CommentsState,
    /** Имя по идентификатору автора. Книга живёт у клиента, экран её не читает сам. */
    nameOf: (String) -> String,
    onBack: () -> Unit,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onReply: (String) -> Unit,
    modifier: Modifier = Modifier,
    onCloseTrouble: () -> Unit = {},
) {
    val colors = Tima.colors
    Column(modifier.fillMaxSize().background(colors.surface)) {
        SubwindowHeader(
            title = "Комментарии",
            onBack = onBack,
            caption = caption(state),
        )

        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
        ) {
            state.trouble?.let {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(TimaSpacing.about4),
                    horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Trouble(it)
                    Chip("Скрыть", kind = ChipKind.Quiet, onClick = onCloseTrouble)
                }
            }

            when {
                state.gone -> EmptyArea(title = "Записи больше нет", explanation = "Разговор ушёл вместе с ней")
                state.entries.isEmpty() && state.loaded && state.closed ->
                    EmptyArea(title = "Обсуждение закрыто")

                state.entries.isEmpty() && state.loaded -> EmptyArea(title = "Здесь ещё никто не написал")
                state.entries.isEmpty() -> EmptyArea(title = "Загружаем разговор…")
                else -> state.entries.forEach { entry ->
                    CommentLine(entry, nameOf(entry.authorId), onReply)
                }
            }

            // Закрытое обсуждение с уже написанным: старые видны, и это сказано словом, а
            // не пустотой поля ввода.
            if (state.closed && state.entries.isNotEmpty()) {
                Tertiary(
                    "Обсуждение закрыто. Написанное раньше осталось",
                    modifier = Modifier.padding(TimaSpacing.about4),
                )
            }
        }

        if (!state.closed && !state.gone) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(TimaSpacing.about4),
                horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Field(
                    value = state.draft,
                    onChange = onDraft,
                    hint = "Написать комментарий…",
                    modifier = Modifier.weight(1f),
                )
                IconButton(glyph = "➤", onClick = onSend, live = true)
            }
        }
    }
}

/**
 * Подпись под названием: сколько ответов и в каком кругу.
 *
 * Круг назван словом, а не числом, — то же правило, что в чате: человек видит «Всем» и
 * «Своим», а не 1 и 2.
 */
private fun caption(state: CommentsState): String {
    val circle = MessageCircle.of(state.level).title
    if (!state.loaded) return circle
    return "${state.entries.size} · $circle"
}

@Composable
private fun CommentLine(entry: CommentEntry, name: String, onReply: (String) -> Unit) {
    ListLine(
        left = { Avatar(letters = initials(name), size = AvatarSize.Small) },
        middle = {
            Column(verticalArrangement = Arrangement.spacedBy(TimaSpacing.about1)) {
                Name(name)
                Secondary(entry.text)
                // «Ответить» подставляет «@имя» в поле ввода: третьего уровня вложения
                // нет, ответ цепляется к тому же корню.
                Chip("Ответить", kind = ChipKind.Quiet, onClick = { onReply(name) })
            }
        },
    )
}

/** Одна-две буквы для аватара: картинок у нас пока нет. */
private fun initials(name: String): String =
    name.trim().split(" ").filter { it.isNotBlank() }.take(2)
        .joinToString("") { it.first().uppercase() }
        .ifEmpty { "?" }
