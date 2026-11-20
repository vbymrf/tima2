package io.tima.feature.group

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import io.tima.core.ui.TextPlace
import io.tima.core.ui.ProvidePlace
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
    /**
     * Раскладка групп по разделам — снаружи: разделы набора сообществ и их вид («Вид»
     * каталога) знает `shared`, а здесь только строка группы. `null` — простой список.
     * Получает группы и то, как рисовать одну.
     */
    layout: (@Composable (groups: List<GroupInfo>, line: @Composable (GroupInfo) -> Unit) -> Unit)? = null,
    /**
     * Число новых у группы — из журнала уведомлений, того же, что у «Страницы» и вкладок
     * (ЖУ2). До 2026-10-05 у строк каталога числа не было вовсе: новое в группе было видно на
     * «Странице», а в каталоге — нет (заказчик, Г).
     */
    countOf: (String) -> Int = { 0 },
    /**
     * Нажатие на аватар — окно «Описание» (ПЛАН-(ЗО) ЗО4): `(вид, номер, название)`, вид — `group`
     * или `community`. `null` — аватар не нажимается, строка ведёт туда же, что и прежде.
     */
    onShowcase: ((kind: String, id: String, title: String) -> Unit)? = null,
    /**
     * Что строка показывает сверх названия (ПЛАН-(СЦ) СЦ3): последнее сообщение, время, выключены ли уведомления.
     * `(вид, номер)`; вид — `group` или `community`. По времени последнего строки и идут, новые сверху (§1.5, 1).
     */
    lookOf: (kind: String, id: String) -> RowLook = { _, _ -> RowLook() },
    /** Цвет названия у своей роли (настройка «Вид → Социум»); `null` — зелёный темы. */
    ownColor: androidx.compose.ui.graphics.Color? = null,
    /** Открыть канал (ПЛАН-(СЦ) СЦ3). `null` — каналов в «Каталоге» нет. */
    onOpenChannel: ((io.tima.domain.chat.ChannelInfo) -> Unit)? = null,
    /** Строки и отступы списка — «Вид разделов и списка» Социума (заказчик 2026-11-20). */
    list: io.tima.core.ui.ListLook = io.tima.core.ui.ListLook.DEFAULT,
) {
    val words = Tima.words.social
    androidx.compose.runtime.CompositionLocalProvider(io.tima.core.ui.LocalListLook provides list) {
    ProvidePlace(TextPlace.LISTS) {
    // Пауза порядка после прокрутки (ПЛАН-(ВС) ВС3): листаете — строки стоят, новое не поднимает сущность наверх.
    val calm = io.tima.core.ui.rememberCalmOrder()
    Column(modifier.fillMaxSize().nestedScroll(calm.connection)) {
        state.trouble?.let { Trouble(it, Modifier.padding(TimaSpacing.about4)) }

        // Сообщества — первыми и одной группой строк: контейнер стоит выше того, что в
        // нём лежит, иначе состав читается раньше, чем то, чему он принадлежит.
        if (onOpenCommunity != null && state.communities.isNotEmpty()) {
            Caption(Tima.words.communities.communities, modifier = Modifier.padding(TimaSpacing.about4))
            for (community in state.communities.sortedByDescending { lookOf("community", it.communityId).atMs ?: 0L }) {
                EntityRow(
                    title = community.title,
                    type = io.tima.domain.chat.EntityType(io.tima.domain.chat.EntityKind.Community, io.tima.domain.chat.EntityReach.Public),
                    role = when {
                        community.owner -> io.tima.domain.chat.GroupRole.Owner
                        community.admin -> io.tima.domain.chat.GroupRole.Admin
                        else -> io.tima.domain.chat.GroupRole.Member
                    },
                    look = lookOf("community", community.communityId),
                    onClick = { onOpenCommunity(community.communityId) },
                    ownColor = ownColor,
                    avatar = {
                        Avatar(
                            letters = community.title.take(2).uppercase(),
                            picture = lookOf("community", community.communityId).picture,
                            size = io.tima.core.ui.LocalListLook.current.avatar,
                            modifier = showcaseClick(onShowcase) { it("community", community.communityId, community.title) },
                        )
                    },
                )
            }
        }

        // Каналы — своей группой строк, как сообщества: они тоже вещают, а не переписываются. Внутри сообщества
        // канал живёт в его списке, а не здесь.
        if (onOpenChannel != null) {
            val channels = state.channels.filter { it.communityId == null }.sortedByDescending { lookOf("channel", it.channelId).atMs ?: 0L }
            for (channel in channels) {
                EntityRow(
                    title = channel.title,
                    type = channel.type,
                    role = channel.role,
                    look = lookOf("channel", channel.channelId),
                    onClick = { onOpenChannel(channel) },
                    ownColor = ownColor,
                    avatar = {
                        Avatar(
                            letters = channel.title.take(2).uppercase(),
                            picture = lookOf("channel", channel.channelId).picture,
                            size = io.tima.core.ui.LocalListLook.current.avatar,
                            modifier = showcaseClick(onShowcase) { it("channel", channel.channelId, channel.title) },
                        )
                    },
                )
            }
        }

        val mine = calm.order(state.mine.sortedByDescending { lookOf("group", it.groupId).atMs ?: 0L }) { it.groupId }
        val line: @Composable (GroupInfo) -> Unit = { group ->
            GroupLine(group, onOpen, countOf(group.groupId), onShowcase, look = lookOf("group", group.groupId), ownColor = ownColor)
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
        } else if (layout != null) {
            Box(Modifier.weight(1f)) { layout(mine) { group -> line(group) } }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(mine, key = { it.groupId }) { group -> line(group) }
            }
        }

        // Плюс — вход в мастер создания. Стоит в каталоге, как и решено: прежний вход в
        // окне 1 был придуманным и убран.
        Box(Modifier.fillMaxWidth().padding(TimaSpacing.about4), contentAlignment = Alignment.CenterEnd) {
            // Кнопка окна — по «Вкладкам окон» (заказчик 2026-10-24).
            ProvidePlace(TextPlace.TABS) { Button(label = words.create, onClick = onNew, modifier = Modifier.testTag(SOCIAL_CREATE_TAG)) }
        }
    }
    }
    }
}

/**
 * Строка группы каталога (ПЛАН-(СЦ) СЦ3): название цветом роли, последнее сообщение в две строки, справа тип значком,
 * время и число новых. Сообщений ещё нет — второй строкой моя роль, как раньше.
 */
@Composable
fun GroupLine(
    group: GroupInfo,
    onOpen: (GroupInfo) -> Unit,
    howMany: Int = 0,
    onShowcase: ((kind: String, id: String, title: String) -> Unit)? = null,
    look: RowLook = RowLook(),
    ownColor: androidx.compose.ui.graphics.Color? = null,
) {
    EntityRow(
        title = group.title,
        type = group.type,
        role = group.myRole,
        // Положение — второй строкой у самой строки; третья, если выбрана, — только сообщение (2026-11-20).
        look = look.copy(fresh = howMany),
        onClick = { onOpen(group) },
        ownColor = ownColor,
        avatar = {
            Avatar(
                letters = group.title.take(2).uppercase(),
                picture = look.picture,
                size = io.tima.core.ui.LocalListLook.current.avatar,
                modifier = showcaseClick(onShowcase) { it("group", group.groupId, group.title) },
            )
        },
    )
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
    /** Нажатие на аватар карточки — «Описание» группы, ещё не вступив (ПЛАН-(ЗО) ЗО4). */
    onShowcase: ((kind: String, id: String, title: String) -> Unit)? = null,
) {
    val words = Tima.words.social
    ProvidePlace(TextPlace.LISTS) {
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
                    onShowcase = onShowcase?.let { show -> { show("group", card.groupId, card.title) } },
                )
            }
        }
    }
    }
}

@Composable
private fun CardLine(card: GroupCard, asked: Boolean, asking: Boolean, onAsk: () -> Unit, onShowcase: (() -> Unit)? = null) {
    val words = Tima.words.social
    ListLine(
        onClick = onShowcase,
        left = {
            Avatar(
                letters = card.title.take(2).uppercase(),
                modifier = if (onShowcase != null) Modifier.clickable(onClick = onShowcase).testTag(SHOWCASE_AVATAR_TAG) else Modifier,
            )
        },
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

/** Метка аватара, открывающего «Описание» (СЦЕНАРИИ-ПО-ОКНАМ.md). */
const val SHOWCASE_AVATAR_TAG = "social:showcase"

/** «Создать» каталога — вход в мастер создания, для сценариев (2026-10-22). */
const val SOCIAL_CREATE_TAG = "social:create"

/** Аватар открывает «Описание»; нет обработчика — обычный аватар. */
private fun showcaseClick(
    onShowcase: ((kind: String, id: String, title: String) -> Unit)?,
    open: ((String, String, String) -> Unit) -> Unit,
): Modifier = if (onShowcase == null) Modifier else Modifier.clickable { open(onShowcase) }.testTag(SHOWCASE_AVATAR_TAG)
