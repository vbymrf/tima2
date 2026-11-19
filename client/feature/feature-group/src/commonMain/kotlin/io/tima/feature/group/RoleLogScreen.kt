package io.tima.feature.group

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
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
    const val MEMBERS = "roles:members"
    const val ROW = "roles:row"
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
) {
    val w = Tima.words.socium
    Column(Modifier.fillMaxSize().background(Tima.colors.surface)) {
        SubwindowHeader(title = w.manageRoles, onBack = onBack, caption = title, avatar = title.take(2).uppercase())
        Column(Modifier.padding(TimaSpacing.about4)) {
            Secondary(w.roleLogHint)
            onMembers?.let { Button(w.tabMembers, onClick = it, kind = ButtonKind.Quiet, modifier = Modifier.testTag(RoleLogTags.MEMBERS)) }
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
                            Name(nameOf(e.actorId) + " " + w.roleLogAction(e.action, e.detail) + " " + nameOf(e.targetId))
                            Tertiary(time(e.atMs))
                        }
                    },
                )
            }
        }
    }
}
