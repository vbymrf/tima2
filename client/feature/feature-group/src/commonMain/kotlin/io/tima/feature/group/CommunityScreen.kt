package io.tima.feature.group

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.tima.core.ui.Avatar
import io.tima.core.ui.Button
import io.tima.core.ui.ButtonKind
import io.tima.core.ui.Caption
import io.tima.core.ui.ListLine
import io.tima.core.ui.Name
import io.tima.core.ui.Secondary
import io.tima.core.ui.SubwindowHeader
import io.tima.core.ui.Tertiary
import io.tima.core.ui.Tima
import io.tima.core.ui.words
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.Trouble
import io.tima.domain.chat.CommunityItem
import io.tima.domain.chat.CommunityKinds

/**
 * Страница сообщества (ПЛАН-СООБЩЕСТВ С6).
 *
 * **Состав, а не содержимое.** На странице перечислено, что связано с сообществом, — и
 * ничего из того, что лежит внутри элементов: ни сообщений, ни участников. Показать здесь
 * переписку значило бы сказать, что сообщество ею распоряжается, а оно не распоряжается.
 *
 * **Личные группы в списке не показываются никому, кроме тех, кто распоряжается** — то же
 * правило, что и в поиске: личная группа не ищется никогда (ADR-0018 п. 5). Отсев делает
 * сервер; экран не знает даже, что скрывать.
 *
 * **Описание — сообщения уровня 0** (ADR-0019 §4), а не поле сообщества: то же решение,
 * что у группы.
 */
@Composable
fun CommunityScreen(
    state: CommunityState,
    onBack: () -> Unit,
    onSubscribe: (Boolean) -> Unit,
    onOpenItem: (CommunityItem) -> Unit,
    modifier: Modifier = Modifier,
    /** Внести своё в это сообщество (С7). `null` — мы не владелец: вносить нечем. */
    onLink: ((CommunityItem) -> Unit)? = null,
    /** Вынуть обратно: элемент снова становится отдельным. */
    onUnlink: ((CommunityItem) -> Unit)? = null,
) {
    val colors = Tima.colors
    val words = Tima.words
    Column(modifier.fillMaxSize().background(colors.surface)) {
        SubwindowHeader(
            title = state.title.ifBlank { words.communities.community },
            onBack = onBack,
            caption = caption(state),
        )

        Column(
            modifier = Modifier.fillMaxWidth().padding(TimaSpacing.about4),
            verticalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
        ) {
            state.trouble?.let { Trouble(it) }

            if (!state.loaded) {
                Secondary(words.communities.opening)
                return@Column
            }

            // Описание идёт первым и целиком: это то, ради чего страницу открывают
            // впервые. Пусто — так и говорим, а не оставляем молчание.
            if (state.description.isEmpty()) {
                Tertiary(words.communities.noDescription, lineOne = true)
            } else {
                for (line in state.description) Caption(line, fontSize = TimaType.sz4)
            }

            // Подписка — одно действие на весь контейнер: подписался на сообщество,
            // читаешь его каналы. Членства в личных группах она не даёт (ADR-0018).
            if (!state.owner) {
                Button(
                    label = if (state.subscribed) words.communities.unsubscribe else words.communities.subscribe,
                    onClick = { onSubscribe(!state.subscribed) },
                    kind = if (state.subscribed) ButtonKind.Quiet else ButtonKind.Action,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (state.loaded && state.items.isEmpty()) {
            Secondary(
                words.communities.emptyInside,
                modifier = Modifier.padding(horizontal = TimaSpacing.about4),
            )
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(state.items, key = { it.kind + it.id }) { item ->
                ListLine(
                    onClick = { onOpenItem(item) },
                    left = { Avatar(letters = item.title.take(2).uppercase()) },
                    middle = {
                        Column {
                            Name(item.title)
                            Secondary(kindWord(item), lineOne = true)
                        }
                    },
                    right = if (onUnlink != null) {
                        { Button(label = words.communities.takeOut, onClick = { onUnlink(item) }, kind = ButtonKind.Quiet) }
                    } else {
                        null
                    },
                )
            }

            // «Внести своё» — только у владельца и в конце списка: сначала то, что уже
            // связано, потом то, что можно связать. Строкой сказано главное: переписка и
            // участники не меняются.
            if (onLink != null && state.linkable.isNotEmpty()) {
                item {
                    Column(Modifier.padding(TimaSpacing.about4)) {
                        Name(words.communities.bringOwn)
                        Tertiary(words.communities.bringingKeepsEverything)
                    }
                }
                items(state.linkable, key = { "free:" + it.kind + it.id }) { item ->
                    ListLine(
                        left = { Avatar(letters = item.title.take(2).uppercase()) },
                        middle = {
                            Column {
                                Name(item.title)
                                Secondary(kindWord(item), lineOne = true)
                            }
                        },
                        right = { Button(label = words.communities.bring, onClick = { onLink(item) }) },
                    )
                }
            }
        }
    }
}

/** Как назвать элемент состава одним словом. */
@Composable
private fun kindWord(item: CommunityItem): String = when {
    item.kind == CommunityKinds.CHANNEL -> Tima.words.communities.channel
    item.personal -> Tima.words.communities.personalGroup
    else -> Tima.words.communities.group
}

/** Подпись под названием: сколько внутри и чем человек здесь является. */
@Composable
private fun caption(state: CommunityState): String {
    if (!state.loaded) return ""
    val words = Tima.words.communities
    val role = when {
        state.owner -> words.youOwner
        state.admin -> words.youAdmin
        state.subscribed -> words.youSubscribed
        else -> words.youNotSubscribed
    }
    return "${state.items.size} внутри · $role"
}
