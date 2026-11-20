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
    layout: (@Composable (entries: List<CatalogEntry>, line: @Composable (CatalogEntry) -> Unit) -> Unit)? = null,
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

        // ── ОДИН СПИСОК (ПЛАН-(ВС) 12–13, заказчик 2026-11-20) ─────────────────────────────────────────────────────
        //
        // Сообщества, каналы и группы — вместе, по времени последнего уведомления, а не блоками: блоки сверху читались
        // закреплёнными. Сообщество — как раздел: его каналы и группы живут внутри него, и если я на сообщество
        // подписан, в общем списке их нет. Подписан только на канал или группу — они здесь сами по себе.
        val mineCommunities = if (onOpenCommunity != null) state.communities.map { it.communityId }.toSet() else emptySet()
        val entries = calm.order(
            buildList {
                if (onOpenCommunity != null) state.communities.forEach { add(CatalogEntry.Community(it, lookOf("community", it.communityId).atMs ?: 0L)) }
                if (onOpenChannel != null) {
                    state.channels.filter { it.communityId == null || it.communityId !in mineCommunities }
                        .forEach { add(CatalogEntry.Channel(it, lookOf("channel", it.channelId).atMs ?: 0L)) }
                }
                state.mine.filter { it.communityId == null || it.communityId !in mineCommunities }
                    .forEach { add(CatalogEntry.Group(it, lookOf("group", it.groupId).atMs ?: 0L)) }
            }.sortedByDescending { it.atMs },
        ) { it.key }
        val line: @Composable (CatalogEntry) -> Unit = { entry ->
            when (entry) {
                is CatalogEntry.Group -> GroupLine(entry.group, onOpen, countOf(entry.group.groupId), onShowcase, look = lookOf("group", entry.group.groupId), ownColor = ownColor)
                is CatalogEntry.Channel -> {
                    val channel = entry.channel
                    EntityRow(
                        title = channel.title,
                        type = channel.type,
                        role = channel.role,
                        look = lookOf("channel", channel.channelId),
                        onClick = { onOpenChannel?.invoke(channel) },
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
                is CatalogEntry.Community -> {
                    val community = entry.community
                    // Число новых сообщества — сумма внутри (ПЛАН-(ВС) 13): его группы по журналу уведомлений.
                    val inside = state.mine.filter { it.communityId == community.communityId }.sumOf { countOf(it.groupId) }
                    val look = lookOf("community", community.communityId)
                    EntityRow(
                        title = community.title,
                        type = io.tima.domain.chat.EntityType(io.tima.domain.chat.EntityKind.Community, io.tima.domain.chat.EntityReach.Public),
                        role = when {
                            community.owner -> io.tima.domain.chat.GroupRole.Owner
                            community.admin -> io.tima.domain.chat.GroupRole.Admin
                            else -> io.tima.domain.chat.GroupRole.Member
                        },
                        look = look.copy(fresh = maxOf(look.fresh, inside)),
                        onClick = { onOpenCommunity?.invoke(community.communityId) },
                        ownColor = ownColor,
                        avatar = {
                            Avatar(
                                letters = community.title.take(2).uppercase(),
                                picture = look.picture,
                                size = io.tima.core.ui.LocalListLook.current.avatar,
                                modifier = showcaseClick(onShowcase) { it("community", community.communityId, community.title) },
                            )
                        },
                    )
                }
            }
        }
        if (entries.isEmpty()) {
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
            Box(Modifier.weight(1f)) { layout(entries) { entry -> line(entry) } }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(entries, key = { it.key }) { entry -> line(entry) }
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

/** Строка каталога — группа, канал или сообщество (ПЛАН-(ВС) 12): один список, ключ — «вид:номер». */
sealed interface CatalogEntry {
    val key: String

    /** Время последнего уведомления — по нему список и идёт (ПЛАН-(ВС) 9). */
    val atMs: Long

    data class Group(val group: GroupInfo, override val atMs: Long) : CatalogEntry {
        override val key: String get() = "group:" + group.groupId
    }

    data class Channel(val channel: io.tima.domain.chat.ChannelInfo, override val atMs: Long) : CatalogEntry {
        override val key: String get() = "channel:" + channel.channelId
    }

    data class Community(val community: io.tima.domain.chat.CommunityPage, override val atMs: Long) : CatalogEntry {
        override val key: String get() = "community:" + community.communityId
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
