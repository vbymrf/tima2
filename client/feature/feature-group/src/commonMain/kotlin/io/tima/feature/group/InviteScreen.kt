package io.tima.feature.group

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import io.tima.core.ui.Button
import io.tima.core.ui.ButtonKind
import io.tima.core.ui.Caption
import io.tima.core.ui.Secondary
import io.tima.core.ui.SubwindowHeader
import io.tima.core.ui.Tima
import io.tima.core.ui.words
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.domain.chat.EntityInvite
import io.tima.domain.chat.EntityInvites
import io.tima.domain.chat.InviteStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Приглашение в сущность (doc_mig/ПЛАН-(СЦ)-СОЦИУМА.md, СЦ2): пришло карточкой в личную переписку с пригласившим;
 * нажатие — сюда. «Принять» — человек внутри; «Отказаться» — пригласившему придёт ответ.
 */
class InviteStore(private val invites: EntityInvites, private val scope: CoroutineScope, private val inviteId: String) {
    private val _state = MutableStateFlow(InviteState())
    val state: StateFlow<InviteState> = _state.asStateFlow()

    fun refresh() {
        scope.launch {
            val list = invites.mine()
            _state.value = when (list) {
                null -> _state.value.copy(loading = false, offline = true)
                else -> _state.value.copy(loading = false, offline = false, invite = list.firstOrNull { it.inviteId == inviteId })
            }
        }
    }

    fun answer(accept: Boolean) {
        if (_state.value.busy || _state.value.answered != null) return
        _state.value = _state.value.copy(busy = true)
        scope.launch {
            val step = invites.answer(inviteId, accept)
            _state.value = when (step) {
                InviteStep.Answered -> _state.value.copy(busy = false, answered = accept)
                InviteStep.Offline -> _state.value.copy(busy = false, offline = true)
                else -> _state.value.copy(busy = false, invite = null)
            }
        }
    }
}

data class InviteState(
    val loading: Boolean = true,
    val offline: Boolean = false,
    /** `null` после загрузки — приглашения нет: срок прошёл, ответили, отозвали. */
    val invite: EntityInvite? = null,
    val busy: Boolean = false,
    /** Ответ дан: `true` — принял, `false` — отказался. */
    val answered: Boolean? = null,
)

/** Метки подокна приглашения — для сценариев. */
object InviteTags {
    const val ACCEPT = "invite:accept"
    const val DECLINE = "invite:decline"
    const val OPEN = "invite:open"
}

@Composable
fun InviteScreen(
    state: InviteState,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onOpen: (EntityInvite) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val words = Tima.words.socium
    Column(modifier.fillMaxSize().background(Tima.colors.surface)) {
        SubwindowHeader(title = words.invitesTitle, onBack = onBack)
        Column(Modifier.padding(TimaSpacing.about5), verticalArrangement = Arrangement.spacedBy(TimaSpacing.about4)) {
            val invite = state.invite
            when {
                state.loading -> Secondary("…")
                state.answered == true && invite != null -> {
                    Caption(invite.title, fontSize = TimaType.sz3, weight = FontWeight.ExtraBold)
                    Secondary(words.inviteAccepted)
                    Button(words.tabAbout, onClick = { onOpen(invite) }, modifier = Modifier.testTag(InviteTags.OPEN))
                }
                state.answered == false -> Secondary(words.inviteDeclined)
                invite == null -> Secondary(if (state.offline) Tima.words.trouble.offline else words.inviteGone)
                else -> {
                    Caption(invite.title, fontSize = TimaType.sz3, weight = FontWeight.ExtraBold)
                    Secondary(invite.type().words(words))
                    Row(horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about3)) {
                        Button(words.inviteAccept, onClick = onAccept, modifier = Modifier.testTag(InviteTags.ACCEPT), enabled = !state.busy)
                        Button(words.inviteDecline, onClick = onDecline, kind = ButtonKind.Quiet, modifier = Modifier.testTag(InviteTags.DECLINE), enabled = !state.busy)
                    }
                }
            }
        }
    }
}

/** Тип сущности приглашения. */
fun EntityInvite.type(): io.tima.domain.chat.EntityType = io.tima.domain.chat.EntityType(
    kind,
    if (personal) io.tima.domain.chat.EntityReach.Personal else io.tima.domain.chat.EntityReach.Public,
    secret,
)

/** Тип словами (СЦ2): на странице и для чтения с экрана; в строке — значком. */
fun io.tima.domain.chat.EntityType.words(w: io.tima.core.words.SociumWords): String = when (kind) {
    io.tima.domain.chat.EntityKind.Group -> when {
        secret -> w.secretGroup
        reach == io.tima.domain.chat.EntityReach.Personal -> w.personalGroup
        else -> w.publicGroup
    }
    io.tima.domain.chat.EntityKind.Channel -> when {
        secret -> w.secretChannel
        reach == io.tima.domain.chat.EntityReach.Public -> w.publicChannel
        else -> w.channel
    }
    io.tima.domain.chat.EntityKind.Community -> if (secret) w.secretCommunity else w.community
    io.tima.domain.chat.EntityKind.Voice -> w.voiceChat
}
