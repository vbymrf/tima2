package io.tima.feature.group

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.tima.core.ui.Avatar
import io.tima.core.ui.Button
import io.tima.core.ui.Caption
import io.tima.core.ui.ListLine
import io.tima.core.ui.Name
import io.tima.core.ui.Secondary
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.Trouble
import io.tima.domain.chat.GroupCard
import io.tima.domain.chat.GroupInfo
import io.tima.domain.chat.GroupKind

/**
 * Вкладка «Каталог»: группы, где я состою.
 *
 * Личная группа появляется здесь **после вступления** — до того она живёт во вкладке
 * «Друзья» карточкой (ADR-0018 п. 4).
 */
@Composable
fun CatalogTab(
    state: SocialState,
    onOpen: (GroupInfo) -> Unit,
    onNew: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        state.trouble?.let { Trouble(it, Modifier.padding(TimaSpacing.about4)) }

        if (state.mine.isEmpty()) {
            // «Пусто» и «ещё не знаем» — разные вещи, и человек не должен их путать.
            EmptyTab(
                title = if (state.loaded) "Групп пока нет" else "Смотрим, какие есть группы…",
                about = if (state.loaded) {
                    "Создайте первую: плюс в правом нижнем углу."
                } else {
                    "Если список не появится, значит не дошли до сервера — тогда здесь будет сказано."
                },
            )
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(state.mine, key = { it.groupId }) { group ->
                    ListLine(
                        onClick = { onOpen(group) },
                        left = { Avatar(letters = group.title.take(2).uppercase()) },
                        middle = {
                            Column {
                                Name(group.title)
                                Secondary(roleWord(group))
                            }
                        },
                    )
                }
            }
        }

        // Плюс — вход в мастер создания. Стоит в каталоге, как и решено: прежний вход в
        // окне 1 был придуманным и убран.
        Box(Modifier.fillMaxWidth().padding(TimaSpacing.about4), contentAlignment = Alignment.CenterEnd) {
            Button(label = "＋ Создать", onClick = onNew)
        }
    }
}

/**
 * Вкладка «Друзья»: карточки, которые открыли контакты.
 *
 * Единственное действие с чужой личной группой — попроситься. Отсюда одна кнопка в
 * строке и никаких «войти».
 */
@Composable
fun FriendsTab(
    state: SocialState,
    onAsk: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        state.trouble?.let { Trouble(it, Modifier.padding(TimaSpacing.about4)) }

        if (state.cards.isEmpty()) {
            EmptyTab(
                title = if (state.loaded) "Карточек пока нет" else "Смотрим, что открыли друзья…",
                about = "Здесь появляются группы, которые люди из вашей книги положили себе на страницу.",
            )
            return@Column
        }

        LazyColumn(Modifier.weight(1f)) {
            items(state.cards, key = { it.groupId }) { card ->
                CardLine(
                    card = card,
                    asked = card.groupId in state.asked,
                    asking = card.groupId in state.asking,
                    onAsk = { onAsk(card.groupId) },
                )
            }
        }
    }
}

@Composable
private fun CardLine(card: GroupCard, asked: Boolean, asking: Boolean, onAsk: () -> Unit) {
    ListLine(
        left = { Avatar(letters = card.title.take(2).uppercase()) },
        right = {
            when {
                // Сказано словами, а не отсутствием кнопки: человек должен понимать, что
                // просьба ушла, иначе будет жать снова.
                asked -> Secondary("просьба ушла")
                asking -> Secondary("просим…")
                else -> Button(label = "Попроситься", onClick = onAsk)
            }
        },
        middle = {
            Column {
                Name(card.title)
                Secondary(
                    card.description.ifBlank {
                        if (card.kind == GroupKind.Personal) "Личная группа" else "Публичная группа"
                    },
                )
            }
        },
    )
}

/** Пустая вкладка: что здесь бывает и чего ждать. Молчащий экран неотличим от поломки. */
@Composable
private fun EmptyTab(title: String, about: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(TimaSpacing.about5),
        verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
    ) {
        Caption(title)
        Secondary(about)
    }
}

private fun roleWord(group: GroupInfo): String = when (group.myRole.name.lowercase()) {
    "owner" -> "вы владелец"
    "admin" -> "вы админ"
    "moderator" -> "вы модератор"
    else -> "вы участник"
}
