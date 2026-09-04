package io.tima.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.tima.core.ui.Caption
import io.tima.core.ui.Chip
import io.tima.core.ui.ChipKind
import io.tima.core.ui.Name
import io.tima.core.ui.Secondary
import io.tima.core.ui.Tertiary
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.domain.chat.MessageCircle
import io.tima.domain.chat.PageEntry

/**
 * Страница: своё и принесённое вперемешку (макет `уровень-сообщения.html`, раздел 6).
 *
 * **Принесённое показано от лица источника.** Под записью стоит «группа · принесено вами»,
 * а не имя хозяина страницы: запись не становится своей от того, что её принесли. Отклики
 * и счётчики по замыслу идут оригиналу — это ADR-0019 §7, и показ обязан этому не
 * противоречить.
 *
 * **Пустая страница и «ещё не знаем» различаются.** До первого ответа сервера экран не
 * говорит «здесь ничего нет»: молчание неотличимо от поломки.
 */
@Composable
fun PageScreen(
    state: PageState,
    modifier: Modifier = Modifier,
    /** Убрать запись со своей страницы. `null` — страница чужая. */
    onRemove: ((Long) -> Unit)? = null,
    onCloseTrouble: () -> Unit = {},
) = Column(modifier.fillMaxSize()) {
    state.trouble?.let { text ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(TimaSpacing.about3),
            horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Caption(text, fontSize = TimaType.sz5, modifier = Modifier.weight(1f))
            Chip("Скрыть", kind = ChipKind.Quiet, onClick = onCloseTrouble)
        }
    }

    if (state.entries.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(TimaSpacing.about5),
            verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (state.loaded) {
                Name("Здесь пока пусто")
                Secondary(
                    if (state.mine) {
                        "Записи, которые вы принесёте к себе, появятся тут"
                    } else {
                        "Этот человек ещё ничего не показывает"
                    },
                )
            } else {
                Secondary("Загружаем…")
            }
        }
        return@Column
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(TimaSpacing.about3),
        verticalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
    ) {
        items(state.entries, key = { it.postId }) { entry ->
            PageRow(entry, onRemove.takeIf { state.mine })
        }
    }
}

@Composable
private fun PageRow(entry: PageEntry, onRemove: ((Long) -> Unit)?) = Column(
    verticalArrangement = Arrangement.spacedBy(TimaSpacing.about1),
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Name(entry.sourceTitle.ifBlank { "Ваша запись" })
        // Метка круга — та же, что в переписке: человек читает одно и то же слово в обоих
        // местах, и объяснять их различие не приходится.
        Chip(MessageCircle.of(entry.level).title, kind = ChipKind.Quiet)
    }
    // Строка происхождения: «группа · принесено вами». Без неё принесённая запись выглядит
    // написанной хозяином страницы.
    if (entry.carriedBy.isNotBlank()) {
        Tertiary("принесено вами", lineOne = true)
    }
    Caption(
        entry.text ?: "Запись недоступна",
        fontSize = TimaType.sz4,
    )
    if (onRemove != null) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Chip("Убрать", kind = ChipKind.Quiet, onClick = { onRemove(entry.postId) })
        }
    }
}
