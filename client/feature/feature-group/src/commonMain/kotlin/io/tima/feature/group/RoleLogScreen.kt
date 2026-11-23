package io.tima.feature.group

import io.tima.core.ui.TextPlace
import io.tima.core.ui.ProvidePlace
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.tima.core.ui.Button
import io.tima.core.ui.ButtonKind
import io.tima.core.ui.ListLine
import io.tima.core.ui.Name
import io.tima.core.ui.Secondary
import io.tima.core.ui.SubwindowHeader
import io.tima.core.ui.Tertiary
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.words
import io.tima.domain.chat.RoleLogEntry

/** Метки «Управление → Роли» (ПЛАН-(СЦ) СЦ6). */
object RoleLogTags {
    const val ADD_ADMIN = "roles:add-admin"
    const val MEMBERS = "roles:members"
    const val ROW = "roles:row"
    const val STAFF = "roles:staff"
    const val REMOVE = "roles:remove"
    const val NICK = "roles:nick"
    const val ADD = "roles:add"
}

/**
 * «Управление → Роли» (ПЛАН-(СЦ) §3, СЦ6): роль назначается нажатием на участника во вкладке «Участники» —
 * отсюда туда кнопка; ниже журнал «кто, кому, что, когда», новые первыми. `entries == null` — сервер не ответил.
 */
@Composable
fun RoleLogScreen(
    title: String,
    entries: List<RoleLogEntry>?,
    nameOf: (String) -> String,
    time: (Long) -> String,
    onMembers: (() -> Unit)?,
    onBack: () -> Unit,
    /** Канал и сообщество (СЦ6): модераторы или админы списком; `null` — у группы роли во «Участниках». */
    staff: List<String>? = null,
    staffTitle: String = "",
    onRemoveStaff: (String) -> Unit = {},
    onAddNick: (String) -> Unit = {},
    trouble: String? = null,
    /** Мой номер: свои записи журнала — «Вы добавили», а не «Вы добавил(а)». */
    meId: String = "",
    /** Канал (СЦ21): кто из команды админ — подпись «админ» или «модератор» у строки. */
    admins: Set<String>? = null,
    /** Канал (СЦ21): назначить админом — владельцу; `null` — кнопки нет. */
    onAddAdmin: ((String) -> Unit)? = null,
    /** Можно ли снять этого (СЦ21: админ — только модераторов). */
    removable: (String) -> Boolean = { true },
) {
    // «Меню и настройки» «Шрифтов и размеров» (заказчик 2026-11-23); шапка и вкладки ставят своё.
    ProvidePlace(TextPlace.MENU) {
    val w = Tima.words.socium
    Column(Modifier.fillMaxSize().background(Tima.colors.surface)) {
        SubwindowHeader(title = w.manageRoles, onBack = onBack, caption = title, avatar = title.take(2).uppercase())
        Column(Modifier.padding(TimaSpacing.about4)) {
            // Подсказка про «Участники» — только там, где эта вкладка есть (у канала её нет; живой взгляд 2026-11-19).
            if (onMembers != null) Secondary(w.roleLogHint)
            onMembers?.let { Button(w.tabMembers, onClick = it, kind = ButtonKind.Quiet, modifier = Modifier.testTag(RoleLogTags.MEMBERS)) }
            if (staff != null) {
                Name(staffTitle)
                if (staff.isEmpty()) Tertiary(w.staffNobody)
                for (uid in staff) {
                    ListLine(
                        modifier = Modifier.testTag(RoleLogTags.STAFF),
                        middle = {
                            Name(nameOf(uid))
                            if (admins != null) Tertiary(if (uid in admins) w.roleAdminShort else w.roleModeratorShort, lineOne = true)
                        },
                        right = if (removable(uid)) {
                            { Button(w.staffRemove, onClick = { onRemoveStaff(uid) }, kind = ButtonKind.Quiet, modifier = Modifier.testTag(RoleLogTags.REMOVE)) }
                        } else {
                            null
                        },
                    )
                }
                var nick by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
                androidx.compose.foundation.layout.Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(TimaSpacing.about2),
                ) {
                    io.tima.core.ui.Field(value = nick, onChange = { nick = it }, hint = "@" + Tima.words.social.nickField.lowercase(), modifier = Modifier.weight(1f).testTag(RoleLogTags.NICK))
                    Button(w.staffAdd, onClick = { if (nick.isNotBlank()) { onAddNick(nick.trim().removePrefix("@")); nick = "" } }, modifier = Modifier.testTag(RoleLogTags.ADD))
                }
                onAddAdmin?.let { add ->
                    Button(w.makeAdmin, onClick = { if (nick.isNotBlank()) { add(nick.trim().removePrefix("@")); nick = "" } }, kind = ButtonKind.Quiet, modifier = Modifier.testTag(RoleLogTags.ADD_ADMIN))
                }
                trouble?.let { Secondary(it) }
            }
            Name(w.roleLogTitle)
            when {
                entries == null -> Secondary(Tima.words.trouble.offline)
                entries.isEmpty() -> Tertiary(w.roleLogEmpty)
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(entries.orEmpty()) { e ->
                ListLine(
                    modifier = Modifier.testTag(RoleLogTags.ROW),
                    middle = {
                        Column {
                            Name(nameOf(e.actorId) + " " + w.roleLogAction(e.action, e.detail, mine = e.actorId == meId) + " " + nameOf(e.targetId))
                            Tertiary(time(e.atMs))
                        }
                    },
                )
            }
        }
    }
    }
}
