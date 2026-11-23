package io.tima.feature.group

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.tima.core.ui.Avatar
import io.tima.core.ui.AvatarSize
import io.tima.core.ui.Button
import io.tima.core.ui.ButtonKind
import io.tima.core.ui.Caption
import io.tima.core.ui.CheckMark
import io.tima.core.ui.Counter
import io.tima.core.ui.ExpandMark
import io.tima.core.ui.ListLine
import io.tima.core.ui.Secondary
import io.tima.core.ui.SubwindowHeader
import io.tima.core.ui.Tab
import io.tima.core.ui.Tertiary
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.words
import io.tima.domain.chat.EntityType
import io.tima.domain.chat.GroupRole

/**
 * Страница сущности — вариант В (doc_mig/ПЛАН-(СЦ)-СОЦИУМА.md §3, СЦ4–СЦ6): вкладки «О сущности · Участники ·
 * Управление». Читатель видит одну «О сущности» — тогда полосы вкладок нет вовсе. Вкладки ведут в свои подокна, у
 * каждого сверху та же полоса.
 */
enum class PageTab(val tag: String) {
    About("page:tab:about"),
    Members("page:tab:members"),
    Content("page:tab:content"),
    Manage("page:tab:manage"),
}

/**
 * Какие вкладки видит человек с этой ролью (§3): «Участники» («Подписчики» у канала и сообщества) — команде всегда,
 * участнику — если у сущности включено «Показывать участников» (СЦ19); «Содержимое» — кто видит сообщения, у группы и
 * канала (СЦ20); «Управление» — владельцу, админу, модератору. Одна «О сущности» — полосы нет.
 */
fun pageTabs(role: GroupRole?, hasMembers: Boolean, showMembers: Boolean = false, content: Boolean = false): List<PageTab> {
    val staff = role == GroupRole.Owner || role == GroupRole.Admin || role == GroupRole.Moderator
    val tabs = listOfNotNull(
        PageTab.About,
        PageTab.Members.takeIf { hasMembers && (staff || showMembers) },
        PageTab.Content.takeIf { content },
        PageTab.Manage.takeIf { staff },
    )
    return if (tabs.size > 1) tabs else emptyList()
}

/** Полоса вкладок страницы; пусто — полосы нет. */
@Composable
fun EntityTabsBar(
    tabs: List<PageTab>,
    current: PageTab,
    onTab: (PageTab) -> Unit,
    badge: Int = 0,
    /** Канал и сообщество: вкладка «Участники» зовётся «Подписчики» (ПЛАН-(ВС) 16, СЦ19). */
    subscribers: Boolean = false,
) {
    if (tabs.isEmpty()) return
    val w = Tima.words.socium
    Row(Modifier.fillMaxWidth().padding(horizontal = TimaSpacing.about3, vertical = TimaSpacing.about2), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (tab in tabs) {
            val label = when (tab) {
                PageTab.About -> w.tabAbout
                PageTab.Members -> if (subscribers) w.tabSubscribers else w.tabMembers
                PageTab.Content -> w.tabContent
                PageTab.Manage -> w.tabManage
            }
            Tab(label, current = tab == current, onClick = { onTab(tab) }, count = if (tab == PageTab.Manage) badge else 0, modifier = Modifier.testTag(tab.tag))
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Tima.colors.line))
}

/** Состояние уведомлений сущности у меня (§1.2, 6; §1.5, 7). */
enum class NotifyState { On, Off, Blocked }

/** Метки верха страницы — для сценариев. */
object PageTags {
    const val JOIN = "page:join"
    const val NOTIFY = "page:notify"
    const val LEAVE = "page:leave"
    const val WRITE = "page:write"
    const val SHARE = "page:share"
    const val LEVELS = "page:levels"
}

/**
 * Верх вкладки «О сущности» (СЦ4): аватар, название, тип словами; пузыри подписки и уведомлений; кнопки; галочка
 * «Показывать уровень сообщений» — у участника (§1.7, 2). Суперсообщение и уровень 0 — ниже, своим окном.
 */
@Composable
fun EntityAboutTop(
    title: String,
    type: EntityType,
    role: GroupRole?,
    /** Подписан (сообщество, канал) или участник (группа). */
    inside: Boolean,
    group: Boolean,
    notify: NotifyState,
    onJoin: (() -> Unit)?,
    onNotify: (() -> Unit)?,
    onLeave: (() -> Unit)?,
    onWrite: (() -> Unit)?,
    onShare: (() -> Unit)?,
    showLevels: Boolean?,
    onShowLevels: (Boolean) -> Unit,
    avatarPicture: String? = null,
    /** «участников: N» или «подписчиков: N» под типом (§4б п. 9); `null` — не знаем. */
    count: String? = null,
    /** Под кнопками — у сообщества звуковые чаты (СЦ7); `null` — ничего. */
    extra: (@Composable () -> Unit)? = null,
    /** Переписка с сущностью заблокирована мной (СЦ16): к пузырю уведомлений дописывается «сообщения заблокированы». */
    threadBlocked: Boolean = false,
) {
    val w = Tima.words.socium
    val colors = Tima.colors
    Column(verticalArrangement = Arrangement.spacedBy(TimaSpacing.about3)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about4)) {
            Avatar(letters = title.take(2).uppercase(), size = AvatarSize.Big, picture = avatarPicture)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                val own = role == GroupRole.Owner || role == GroupRole.Admin || role == GroupRole.Moderator
                Caption(title, fontSize = TimaType.sz3, weight = FontWeight.Bold, color = if (own) colors.confirmed else colors.text, maxLines = 2)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    TypeIcon(type, 16.dp)
                    Secondary(type.words(w))
                }
                count?.let { Tertiary(it) }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill(
                if (group) (if (inside) w.member else w.notMember) else (if (inside) w.subscribed else w.notSubscribed),
                if (inside) NotifyState.On else NotifyState.Off,
            )
            if (inside) {
                val state = when (notify) {
                    NotifyState.On -> w.notifyOn
                    NotifyState.Off -> w.notifyOff
                    NotifyState.Blocked -> w.notifyBlocked
                }
                // Состояние сущности; заблокирована переписка — дописывается (СЦ16, §1.10, 2).
                Pill(if (threadBlocked) state + " · " + w.messagesBlockedMark else state, notify)
            } else if (threadBlocked) {
                Pill(w.messagesBlockedMark, NotifyState.Blocked)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            onJoin?.let { Button(Tima.words.showcase.let { s -> if (group) s.join else s.subscribe }, onClick = it, modifier = Modifier.testTag(PageTags.JOIN)) }
            onWrite?.let { Button(w.write, onClick = it, kind = if (onJoin != null) ButtonKind.Quiet else ButtonKind.Action, modifier = Modifier.testTag(PageTags.WRITE)) }
            onNotify?.let { Button(Tima.words.socium.notifyTitle, onClick = it, kind = ButtonKind.Quiet, modifier = Modifier.testTag(PageTags.NOTIFY)) }
            onShare?.let { Button(w.share, onClick = it, kind = ButtonKind.Quiet, modifier = Modifier.testTag(PageTags.SHARE)) }
            onLeave?.let { Button(w.leave, onClick = it, kind = ButtonKind.Quiet, modifier = Modifier.testTag(PageTags.LEAVE)) }
        }
        if (showLevels != null) {
            Row(
                Modifier.fillMaxWidth().clickable { onShowLevels(!showLevels) }.testTag(PageTags.LEVELS),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
            ) {
                CheckMark(showLevels)
                Secondary(w.showLevels)
            }
        }
        extra?.invoke()
    }
}

/** Пузырь состояния (§1.2, 6): зелёный — да, серый — нет, красный — заблокировано. */
@Composable
private fun Pill(text: String, tone: NotifyState) {
    val colors = Tima.colors
    val fill = when (tone) {
        NotifyState.On -> colors.confirmed
        NotifyState.Off -> colors.quiet
        NotifyState.Blocked -> colors.alarm
    }
    Caption(
        text,
        Modifier.background(fill, CircleShape).padding(horizontal = 12.dp, vertical = 4.dp),
        fontSize = TimaType.sz6,
        weight = FontWeight.Bold,
        color = if (tone == NotifyState.Off) colors.text2 else colors.onAccent,
    )
}

/** Раздел «Управления» (§3): у каждого — кому он открыт; закрытые не показываются вовсе. */
enum class ManageSection(val tag: String) {
    Main("manage:main"),
    Roles("manage:roles"),
    Requests("manage:requests"),
    Access("manage:access"),
    Messages("manage:messages"),
    Voice("manage:voice"),
    Appeals("manage:appeals"),
    Danger("manage:danger"),
}

/**
 * Разделы «Управления» по роли (§3): основное, заявки и доступ — владелец и админ; роли — владелец (админ —
 * модераторов); сообщения — и модератор; звуковые чаты — у сообщества; обращения — все, кто распоряжается; опасное —
 * владелец.
 */
fun manageSections(role: GroupRole?, kind: io.tima.domain.chat.EntityKind): List<ManageSection> {
    val owner = role == GroupRole.Owner
    val admin = owner || role == GroupRole.Admin
    val mod = admin || role == GroupRole.Moderator
    if (!mod) return emptyList()
    return listOfNotNull(
        ManageSection.Main.takeIf { admin },
        // Роли: у группы и канала — владелец и админ (админ — модераторов, СЦ21); у сообщества раздаёт владелец.
        ManageSection.Roles.takeIf { if (kind == io.tima.domain.chat.EntityKind.Community) owner else admin },
        ManageSection.Requests.takeIf { admin && kind == io.tima.domain.chat.EntityKind.Group },
        ManageSection.Access.takeIf { admin && kind == io.tima.domain.chat.EntityKind.Group },
        ManageSection.Messages,
        ManageSection.Voice.takeIf { admin && kind == io.tima.domain.chat.EntityKind.Community },
        ManageSection.Appeals,
        ManageSection.Danger.takeIf { owner },
    )
}

/** Вкладка «Управление» (СЦ5): разделы строками, нажатие — подокно раздела. */
@Composable
fun ManageScreen(
    title: String,
    tabs: @Composable () -> Unit,
    sections: List<ManageSection>,
    counts: (ManageSection) -> Int,
    onSection: (ManageSection) -> Unit,
    onBack: () -> Unit,
) {
    val w = Tima.words.socium
    Column(Modifier.fillMaxSize().background(Tima.colors.surface)) {
        SubwindowHeader(title = title, onBack = onBack, caption = w.tabManage, avatar = title.take(2).uppercase())
        tabs()
        for (s in sections) {
            val (name, about) = when (s) {
                ManageSection.Main -> w.manageMain to w.manageMainAbout
                ManageSection.Roles -> w.manageRoles to w.manageRolesAbout
                ManageSection.Requests -> w.manageRequests to w.manageRequestsAbout
                ManageSection.Access -> w.manageAccess to w.manageAccessAbout
                ManageSection.Messages -> w.manageMessages to w.manageMessagesAbout
                ManageSection.Voice -> w.manageVoice to w.manageVoiceAbout
                ManageSection.Appeals -> w.manageAppeals to w.manageAppealsAbout
                ManageSection.Danger -> w.manageDanger to w.manageDangerAbout
            }
            ListLine(
                modifier = Modifier.testTag(s.tag),
                onClick = { onSection(s) },
                right = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Counter(counts(s))
                        ExpandMark()
                    }
                },
                middle = {
                    Caption(name, fontSize = TimaType.sz4, weight = FontWeight.Bold, color = if (s == ManageSection.Danger) Tima.colors.alarm else Tima.colors.text, lineOne = true)
                    Tertiary(about)
                },
            )
        }
    }
}

/** Метки «Основного» — для сценариев. */
object ManageMainTags {
    const val SHOW_MEMBERS = "manage:main:show-members"
    const val TITLE = "manage:main:title"
    const val ABOUT = "manage:main:about"
    const val SECRET = "manage:main:secret"
    const val SAVE = "manage:main:save"
}

/**
 * «Управление → Основное» (СЦ5): аватар, название, описание, «секретная». Название и описание — у всех видов с
 * 2026-11-20 (ПЛАН-(ВС) 10); прежде название правилось только у группы, описания не было.
 */
@Composable
fun ManageMainScreen(
    title: String,
    secret: Boolean,
    renamable: Boolean,
    saved: Boolean?,
    onTitle: (String) -> Unit,
    /** Описание; `null` — поля нет. */
    about: String? = null,
    onAbout: (String) -> Unit = {},
    onSecret: (Boolean) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    /** Аватар (СЦ5): картинка сейчас и кнопки «Сменить» / «Убрать»; `null` — блока нет. */
    avatar: (@Composable () -> Unit)? = null,
    /** «Показывать участников» (СЦ19): `null` — ещё не знаем; меняется сразу, без «Сохранить». */
    showMembers: Boolean? = null,
    onShowMembers: (Boolean) -> Unit = {},
) {
    val w = Tima.words.socium
    Column(Modifier.fillMaxSize().background(Tima.colors.surface)) {
        SubwindowHeader(title = w.manageMain, onBack = onBack, caption = title)
        Column(Modifier.padding(TimaSpacing.about5), verticalArrangement = Arrangement.spacedBy(TimaSpacing.about4)) {
            avatar?.invoke()
            if (renamable) io.tima.core.ui.Field(title, onTitle, modifier = Modifier.testTag(ManageMainTags.TITLE), hint = w.titleHint, sentences = true)
            if (about != null) io.tima.core.ui.Field(about, onAbout, modifier = Modifier.testTag(ManageMainTags.ABOUT), hint = w.aboutHint, sentences = true)
            Row(
                Modifier.fillMaxWidth().clickable { onSecret(!secret) }.testTag(ManageMainTags.SECRET),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
            ) {
                CheckMark(secret)
                Column {
                    Caption(w.secret, fontSize = TimaType.sz5, weight = FontWeight.Bold)
                    Secondary(w.secretAbout)
                }
            }
            if (showMembers != null) {
                Row(
                    Modifier.fillMaxWidth().clickable { onShowMembers(!showMembers) }.testTag(ManageMainTags.SHOW_MEMBERS),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
                ) {
                    CheckMark(showMembers)
                    Column {
                        Caption(w.showMembers, fontSize = TimaType.sz5, weight = FontWeight.Bold)
                        Secondary(w.showMembersHint)
                    }
                }
            }
            Button(w.save, onClick = onSave, modifier = Modifier.testTag(ManageMainTags.SAVE))
            when (saved) {
                true -> Secondary(w.saved)
                false -> Secondary(Tima.words.trouble.offline)
                null -> Unit
            }
        }
    }
}

/** Метки «Подписчиков» — для сценариев. */
object SubscribersTags {
    const val ROW = "subscribers:row"
    const val EXCLUDE = "subscribers:exclude"
    const val BAN = "subscribers:ban"
    const val UNBAN = "subscribers:unban"
}

/**
 * «Подписчики» канала и сообщества, «Участники» участнику (ПЛАН-(ВС) 16; СЦ19, СЦ21, СЦ23). Команде — роли, «Исключить»
 * (может подписаться снова) и «Забанить» (не может, пока не снимут) у тех, кто ниже рангом, и список забаненных со
 * «Снять бан». Участнику — только имена. Опасные кнопки — двумя нажатиями: второе подтверждает.
 */
@Composable
fun SubscribersScreen(
    title: String,
    tabs: @Composable () -> Unit,
    /** Строки; `null` — нет связи или прав. */
    people: List<SubscriberLine>?,
    onExclude: (String) -> Unit,
    onBack: () -> Unit,
    onBan: (String) -> Unit = {},
    /** Забаненные (только команде); пусто — раздела нет. */
    banned: List<SubscriberLine> = emptyList(),
    onUnban: (String) -> Unit = {},
    /** Заголовок: «Подписчики» или «Участники». */
    caption: String = Tima.words.socium.tabSubscribers,
) {
    val w = Tima.words.socium
    var asked by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Pair<String, Boolean>?>(null) }
    Column(Modifier.fillMaxSize().background(Tima.colors.surface)) {
        SubwindowHeader(title = title, onBack = onBack, caption = caption, avatar = title.take(2).uppercase())
        tabs()
        when {
            people == null -> Secondary(Tima.words.trouble.offline, Modifier.padding(TimaSpacing.about5))
            people.isEmpty() && banned.isEmpty() -> Tertiary(w.subscribersNone, Modifier.padding(TimaSpacing.about5))
            else -> androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize()) {
                items(people.size, key = { people[it].userId }) { i ->
                    val p = people[i]
                    Column {
                        io.tima.core.ui.ListLine(
                            modifier = Modifier.testTag(SubscribersTags.ROW),
                            left = { io.tima.core.ui.Avatar(letters = p.name.take(1).uppercase()) },
                            middle = {
                                io.tima.core.ui.Name(p.name)
                                if (p.role.isNotEmpty()) Tertiary(p.role, lineOne = true)
                            },
                        )
                        if (p.excludable) {
                            val sure = asked?.takeIf { it.first == p.userId }
                            FlowRow(
                                Modifier.padding(start = TimaSpacing.about5, end = TimaSpacing.about4, bottom = TimaSpacing.about2),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Button(
                                    if (sure?.second == false) w.excludeSure else w.excludeSubscriber,
                                    onClick = { if (sure?.second == false) { asked = null; onExclude(p.userId) } else asked = p.userId to false },
                                    kind = if (sure?.second == false) ButtonKind.Dangerous else ButtonKind.Quiet,
                                    modifier = Modifier.testTag(SubscribersTags.EXCLUDE),
                                )
                                Button(
                                    if (sure?.second == true) w.banSure else w.banSubscriber,
                                    onClick = { if (sure?.second == true) { asked = null; onBan(p.userId) } else asked = p.userId to true },
                                    kind = if (sure?.second == true) ButtonKind.Dangerous else ButtonKind.Quiet,
                                    modifier = Modifier.testTag(SubscribersTags.BAN),
                                )
                            }
                        }
                    }
                }
                if (banned.isNotEmpty()) {
                    item(key = "banned:title") { io.tima.core.ui.Name(w.bannedTitle, Modifier.padding(TimaSpacing.about4)) }
                    items(banned.size, key = { "banned:" + banned[it].userId }) { i ->
                        val p = banned[i]
                        io.tima.core.ui.ListLine(
                            left = { io.tima.core.ui.Avatar(letters = p.name.take(1).uppercase()) },
                            middle = { io.tima.core.ui.Name(p.name) },
                            right = { Button(w.unbanSubscriber, onClick = { onUnban(p.userId) }, kind = ButtonKind.Quiet, modifier = Modifier.testTag(SubscribersTags.UNBAN)) },
                        )
                    }
                }
            }
        }
    }
}

/** Строка «Подписчиков»: кто, как назвать, роль словом (участнику — пусто), можно ли исключить и забанить. */
data class SubscriberLine(val userId: String, val name: String, val role: String, val excludable: Boolean)
