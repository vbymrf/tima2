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
    Manage("page:tab:manage"),
}

/** Какие вкладки видит человек с этой ролью (§3): участники — у группы, управление — у владельца, админа, модератора. */
fun pageTabs(role: GroupRole?, hasMembers: Boolean): List<PageTab> {
    val staff = role == GroupRole.Owner || role == GroupRole.Admin || role == GroupRole.Moderator
    if (!staff) return emptyList()
    return listOfNotNull(PageTab.About, PageTab.Members.takeIf { hasMembers }, PageTab.Manage)
}

/** Полоса вкладок страницы; пусто — полосы нет. */
@Composable
fun EntityTabsBar(tabs: List<PageTab>, current: PageTab, onTab: (PageTab) -> Unit, badge: Int = 0) {
    if (tabs.isEmpty()) return
    val w = Tima.words.socium
    Row(Modifier.fillMaxWidth().padding(horizontal = TimaSpacing.about3, vertical = TimaSpacing.about2), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (tab in tabs) {
            val label = when (tab) {
                PageTab.About -> w.tabAbout
                PageTab.Members -> w.tabMembers
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
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill(
                if (group) (if (inside) w.member else w.notMember) else (if (inside) w.subscribed else w.notSubscribed),
                if (inside) NotifyState.On else NotifyState.Off,
            )
            if (inside) {
                Pill(
                    when (notify) {
                        NotifyState.On -> w.notifyOn
                        NotifyState.Off -> w.notifyOff
                        NotifyState.Blocked -> w.notifyBlocked
                    },
                    notify,
                )
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
        ManageSection.Roles.takeIf { admin },
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
    const val TITLE = "manage:main:title"
    const val SECRET = "manage:main:secret"
    const val SAVE = "manage:main:save"
}

/** «Управление → Основное» (СЦ5): название (у группы) и «секретная». Аватар — когда будет ручка сервера. */
@Composable
fun ManageMainScreen(
    title: String,
    secret: Boolean,
    renamable: Boolean,
    saved: Boolean?,
    onTitle: (String) -> Unit,
    onSecret: (Boolean) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    val w = Tima.words.socium
    Column(Modifier.fillMaxSize().background(Tima.colors.surface)) {
        SubwindowHeader(title = w.manageMain, onBack = onBack, caption = title)
        Column(Modifier.padding(TimaSpacing.about5), verticalArrangement = Arrangement.spacedBy(TimaSpacing.about4)) {
            if (renamable) io.tima.core.ui.Field(title, onTitle, modifier = Modifier.testTag(ManageMainTags.TITLE), hint = w.titleHint, sentences = true)
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
            Button(w.save, onClick = onSave, modifier = Modifier.testTag(ManageMainTags.SAVE))
            when (saved) {
                true -> Secondary(w.saved)
                false -> Secondary(Tima.words.trouble.offline)
                null -> Unit
            }
        }
    }
}
