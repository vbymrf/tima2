package io.tima.feature.group

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import io.tima.core.ui.Button
import io.tima.core.ui.ButtonKind
import io.tima.core.ui.Caption
import io.tima.core.ui.Field
import io.tima.core.ui.RadioMark
import io.tima.core.ui.Secondary
import io.tima.core.ui.SubwindowHeader
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.words
import io.tima.domain.chat.EntityKind
import io.tima.domain.chat.EntityOwnership
import io.tima.domain.chat.OwnershipOffer
import io.tima.domain.chat.OwnershipStep
import io.tima.domain.chat.TransferOwnership
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Передача владения (doc_mig/ПЛАН-(СЦ)-СОЦИУМА.md §6.4, СЦ11). Два вида одного подокна: владелец выбирает
 * принимающего и подписывает акт фразой; принимающий видит предложение и подписывает его своей фразой.
 *
 * **Фраза в состоянии не держится дольше подписи:** поле очищается сразу после попытки, удачной или нет —
 * укравшему устройство не должна достаться набранная фраза (ADR-0010).
 */
class OwnershipStore(
    private val ownership: EntityOwnership,
    private val transfer: TransferOwnership,
    private val scope: CoroutineScope,
    private val kind: EntityKind,
    private val entityId: String,
    private val me: String,
    /** Кому можно передать: в группе — модераторы и выше, в канале — модераторы, в сообществе — админы. */
    private val candidates: suspend () -> List<String>,
) {
    private val _state = MutableStateFlow(OwnershipState())
    val state: StateFlow<OwnershipState> = _state.asStateFlow()

    fun refresh() {
        scope.launch {
            val view = ownership.chain(kind, entityId)
            val offers = ownership.offersToMe()
            val incoming = offers?.firstOrNull { it.kind == kind && it.entityId == entityId }
            _state.value = _state.value.copy(
                loading = false,
                canTransfer = view?.canTransfer ?: false,
                nextLink = view?.nextLink ?: 0,
                owner = view?.links?.lastOrNull()?.ownerId == me,
                pending = view?.offer,
                incoming = incoming,
                candidates = if (view?.canTransfer == true) candidates().filter { it != me } else emptyList(),
                offline = view == null && incoming == null && offers == null,
            )
        }
    }

    fun choose(userId: String) {
        _state.value = _state.value.copy(chosen = userId, outcome = null)
    }

    fun phrase(text: String) {
        _state.value = _state.value.copy(phrase = text, outcome = null)
    }

    private fun words(): List<String> = _state.value.phrase.split(' ', '\n', '\t').filter { it.isNotBlank() }

    fun give() {
        val s = _state.value
        val to = s.chosen ?: return
        if (s.busy) return
        _state.value = s.copy(busy = true)
        scope.launch {
            val step = transfer.offer(kind, entityId, me, to, s.nextLink, words())
            _state.value = _state.value.copy(busy = false, phrase = "", outcome = step)
            if (step == OwnershipStep.Done) refresh()
        }
    }

    fun take() {
        val offer = _state.value.incoming ?: return
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true)
        scope.launch {
            val step = transfer.accept(offer, words())
            _state.value = _state.value.copy(busy = false, phrase = "", outcome = step, accepted = step == OwnershipStep.Done)
            if (step is OwnershipStep.BadPhrase && step.dropped) _state.value = _state.value.copy(incoming = null)
        }
    }

    fun decline() {
        scope.launch {
            ownership.drop(kind, entityId)
            _state.value = _state.value.copy(incoming = null, pending = null, outcome = OwnershipStep.Gone)
        }
    }
}

data class OwnershipState(
    val loading: Boolean = true,
    val offline: Boolean = false,
    val owner: Boolean = false,
    val canTransfer: Boolean = false,
    val nextLink: Int = 0,
    val candidates: List<String> = emptyList(),
    val chosen: String? = null,
    val pending: OwnershipOffer? = null,
    val incoming: OwnershipOffer? = null,
    val phrase: String = "",
    val busy: Boolean = false,
    val outcome: OwnershipStep? = null,
    val accepted: Boolean = false,
)

/** Метки подокна передачи — для сценариев. */
object OwnershipTags {
    const val GIVE = "ownership:give"
    const val TAKE = "ownership:take"
    const val DECLINE = "ownership:decline"
    const val PHRASE = "ownership:phrase"
    const val CANDIDATE = "ownership:candidate"
}

@Composable
fun OwnershipScreen(
    state: OwnershipState,
    title: String,
    nameOf: (String) -> String,
    onChoose: (String) -> Unit,
    onPhrase: (String) -> Unit,
    onGive: () -> Unit,
    onTake: () -> Unit,
    onDecline: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val w = Tima.words.socium
    Column(modifier.fillMaxSize().background(Tima.colors.surface)) {
        SubwindowHeader(title = w.transferTitle, onBack = onBack, caption = title)
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(TimaSpacing.about5),
            verticalArrangement = Arrangement.spacedBy(TimaSpacing.about4),
        ) {
            val incoming = state.incoming
            when {
                state.loading -> Secondary("…")
                state.accepted -> Secondary(w.ownershipAccepted)
                incoming != null -> {
                    Caption(w.ownershipOffered(incoming.title, nameOf(incoming.fromId)), fontSize = TimaType.sz4, weight = FontWeight.Bold)
                    Secondary(w.phraseAbout)
                    Field(state.phrase, onPhrase, modifier = Modifier.testTag(OwnershipTags.PHRASE), hint = w.phraseHint)
                    Outcome(state.outcome)
                    Row(horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about3)) {
                        Button(w.inviteAccept, onClick = onTake, enabled = !state.busy && state.phrase.isNotBlank(), modifier = Modifier.testTag(OwnershipTags.TAKE))
                        Button(w.inviteDecline, onClick = onDecline, kind = ButtonKind.Quiet, modifier = Modifier.testTag(OwnershipTags.DECLINE))
                    }
                }
                !state.owner -> Secondary(if (state.offline) Tima.words.trouble.offline else w.inviteGone)
                !state.canTransfer -> {
                    Button(w.transferTitle, onClick = {}, enabled = false)
                    // §6.4: административная смена личности — передать нельзя, распоряжаться можно.
                    Secondary(w.transferNeedsOldKey)
                }
                else -> {
                    state.pending?.let { Secondary(w.transferPending(nameOf(it.toId))) }
                    Secondary(w.transferAbout)
                    if (state.candidates.isEmpty()) Secondary(w.transferNoCandidates)
                    for (id in state.candidates) {
                        Row(
                            Modifier.fillMaxWidth().clickable { onChoose(id) }.testTag(OwnershipTags.CANDIDATE),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
                        ) {
                            RadioMark(on = state.chosen == id)
                            Caption(nameOf(id), fontSize = TimaType.sz4)
                        }
                    }
                    Secondary(w.phraseAbout)
                    Field(state.phrase, onPhrase, modifier = Modifier.testTag(OwnershipTags.PHRASE), hint = w.phraseHint)
                    Outcome(state.outcome)
                    Button(
                        w.transferTitle,
                        onClick = onGive,
                        enabled = !state.busy && state.chosen != null && state.phrase.isNotBlank(),
                        kind = ButtonKind.Dangerous,
                        modifier = Modifier.testTag(OwnershipTags.GIVE),
                    )
                    if (state.pending != null) Button(w.transferWithdraw, onClick = onDecline, kind = ButtonKind.Quiet)
                }
            }
        }
    }
}

@Composable
private fun Outcome(step: OwnershipStep?) {
    val w = Tima.words.socium
    val text = when (step) {
        null -> return
        OwnershipStep.Done -> w.transferSent
        is OwnershipStep.BadPhrase -> if (step.dropped) w.offerDropped else w.badPhrase
        OwnershipStep.ReceiverRole -> w.receiverRole
        OwnershipStep.Gone -> w.inviteGone
        OwnershipStep.NoIdentity -> w.transferNeedsOldKey
        OwnershipStep.Offline -> Tima.words.trouble.offline
        is OwnershipStep.Refused -> step.reason
    }
    Secondary(text)
}
