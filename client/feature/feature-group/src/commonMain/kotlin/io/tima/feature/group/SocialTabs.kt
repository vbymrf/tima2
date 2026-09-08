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
import io.tima.core.ui.SocialWords
import io.tima.core.ui.Avatar
import io.tima.core.ui.Button
import io.tima.core.ui.Caption
import io.tima.core.ui.Tima
import io.tima.core.ui.words
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
    /** Открыть сообщество. `null` — сообществ в этой сборке нет (проверки). */
    onOpenCommunity: ((String) -> Unit)? = null,
) {
    val words = Tima.words.social
    Column(modifier.fillMaxSize()) {
        state.trouble?.let { Trouble(it, Modifier.padding(TimaSpacing.about4)) }

        // Сообщества — первыми и одной группой строк: контейнер стоит выше того, что в
        // нём лежит, иначе состав читается раньше, чем то, чему он принадлежит.
        if (onOpenCommunity != null && state.communities.isNotEmpty()) {
            Caption(Tima.words.communities.communities, modifier = Modifier.padding(TimaSpacing.about4))
            for (community in state.communities) {
                ListLine(
                    onClick = { onOpenCommunity(community.communityId) },
                    left = { Avatar(letters = community.title.take(2).uppercase()) },
                    middle = {
                        Column {
                            Name(community.title)
                            Secondary(
                                if (community.owner) {
                                    Tima.words.communities.yourCommunity
                                } else {
                                    Tima.words.communities.youSubscribed
                                },
                                lineOne = true,
                            )
                        }
                    },
                )
            }
        }

        if (state.mine.isEmpty()) {
            // «Пусто» и «ещё не знаем» — разные вещи, и человек не должен их путать.
            EmptyTab(
                title = if (state.loaded) words.noGroupsYet else words.lookingForGroups,
                about = if (state.loaded) {
                    words.createFirst
                } else {
                    words.ifListNeverComes
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
                                Secondary(roleWord(group, words))
                            }
                        },
                    )
                }
            }
        }

        // Плюс — вход в мастер создания. Стоит в каталоге, как и решено: прежний вход в
        // окне 1 был придуманным и убран.
        Box(Modifier.fillMaxWidth().padding(TimaSpacing.about4), contentAlignment = Alignment.CenterEnd) {
            Button(label = words.create, onClick = onNew)
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
    val words = Tima.words.social
    Column(modifier.fillMaxSize()) {
        state.trouble?.let { Trouble(it, Modifier.padding(TimaSpacing.about4)) }

        if (state.cards.isEmpty()) {
            EmptyTab(
                title = if (state.loaded) words.noCardsYet else words.lookingWhatFriendsOpened,
                about = words.cardsAbout,
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
    val words = Tima.words.social
    ListLine(
        left = { Avatar(letters = card.title.take(2).uppercase()) },
        right = {
            when {
                // Сказано словами, а не отсутствием кнопки: человек должен понимать, что
                // просьба ушла, иначе будет жать снова.
                asked -> Secondary(words.askSent)
                asking -> Secondary(words.asking)
                else -> Button(label = words.askToJoin, onClick = onAsk)
            }
        },
        middle = {
            Column {
                Name(card.title)
                Secondary(
                    card.description.ifBlank {
                        if (card.kind == GroupKind.Personal) words.personalGroup else words.publicGroup
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

private fun roleWord(group: GroupInfo, words: SocialWords): String =
    when (group.myRole.name.lowercase()) {
        "owner" -> words.youOwner
        "admin" -> words.youAdmin
        "moderator" -> words.youModerator
        else -> words.youMember
    }
