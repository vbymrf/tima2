package io.tima.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.tima.core.ui.Answer
import io.tima.core.ui.AnswerTone
import io.tima.core.ui.Button
import io.tima.core.ui.ButtonKind
import io.tima.core.ui.Caption
import io.tima.core.ui.Field
import io.tima.core.ui.Secondary
import io.tima.core.ui.Tertiary
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.words
import io.tima.domain.account.PhraseFault
import io.tima.domain.account.PinRules
import kotlinx.coroutines.delay

/** Чей пин спрашивается — шапка замка (пробы «ж», «и»). */
data class PinWho(val name: String, val detail: String, val temporary: Boolean)

/**
 * Экран пин-кода (ПЛАН-(ПН), пробы «е»–«и»): точки, клавиатура, фраза, выбор после фразы.
 *
 * @param who чей пин — на замке; `null` — экран из настроек, шапку рисует подокно.
 * @param onCancel «Отмена» — на замке после перехода на аккаунт (вернуться к прежнему) и в
 *   настройках; `null` — кнопки нет.
 * @param onOther «Другой аккаунт» на замке; `null` — ссылки нет.
 * @param now часы устройства (Р15) — для отсчёта паузы.
 */
@Composable
fun PinScreen(
    state: PinFlowState,
    onDigit: (Char) -> Unit,
    onErase: () -> Unit,
    onForgot: () -> Unit,
    onPhrase: (String) -> Unit,
    onChoose: (Boolean) -> Unit,
    onTick: () -> Unit,
    now: () -> Long,
    modifier: Modifier = Modifier,
    who: PinWho? = null,
    onCancel: (() -> Unit)? = null,
    onOther: (() -> Unit)? = null,
    /** Временный аккаунт — подходит и фраза владельца (Р3). */
    temporary: Boolean = who?.temporary == true,
) {
    val w = Tima.words.pin
    Column(
        modifier.fillMaxSize().background(Tima.colors.surface).verticalScroll(rememberScrollState())
            .padding(horizontal = TimaSpacing.about5, vertical = TimaSpacing.about5),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
    ) {
        who?.let {
            Caption(it.name, fontSize = TimaType.sz4, weight = FontWeight.ExtraBold)
            Tertiary(if (it.temporary) w.temporary else it.detail)
        }
        when (state.step) {
            PinStep.Phrase -> PhraseStep(state, temporary, onPhrase)
            PinStep.Choice -> ChoiceStep(onChoose)
            PinStep.Done -> Unit
            else -> DigitsStep(state, who, onDigit, onErase, onTick, now)
        }
        Spacer(Modifier.height(TimaSpacing.about2))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val canForget = state.step == PinStep.Current
            if (canForget) Link(w.forgot, onForgot) else Spacer(Modifier.size(1.dp))
            when {
                onOther != null -> Link(w.otherAccount, onOther)
                onCancel != null -> Link(w.cancel, onCancel)
            }
        }
    }
}

@Composable
private fun DigitsStep(
    state: PinFlowState,
    who: PinWho?,
    onDigit: (Char) -> Unit,
    onErase: () -> Unit,
    onTick: () -> Unit,
    now: () -> Long,
) {
    val w = Tima.words.pin
    val ask = when (state.step) {
        PinStep.Create -> w.create
        PinStep.Repeat -> w.repeat
        else -> if (who != null && state.mode == PinMode.Unlock) w.enter else w.enterCurrent
    }
    Secondary(ask)
    // Отсчёт паузы — раз в секунду, пока она идёт.
    var clock by remember { mutableLongStateOf(now()) }
    val paused = state.pausedUntil > clock
    LaunchedEffect(state.pausedUntil) {
        while (state.pausedUntil > now()) {
            clock = now()
            delay(1_000)
        }
        clock = now()
        onTick()
    }
    Dots(state.typed, error = state.message is PinMessage.Wrong || state.message == PinMessage.Mismatch)
    val message = when {
        paused -> w.paused((state.pausedUntil - clock + 999) / 1000)
        state.message is PinMessage.Wrong -> w.wrong((state.message as PinMessage.Wrong).leftBeforePause)
        state.message == PinMessage.Mismatch -> w.mismatch
        state.step == PinStep.Create -> w.createNote
        else -> ""
    }
    Caption(
        message,
        fontSize = TimaType.sz5,
        weight = FontWeight.Bold,
        color = when {
            paused -> Tima.colors.text
            state.message != null -> Tima.colors.alarm
            else -> Tima.colors.text3
        },
    )
    Keypad(enabled = !paused, onDigit = onDigit, onErase = onErase)
}

@Composable
private fun Dots(count: Int, error: Boolean) {
    val colors = Tima.colors
    val ink = if (error) colors.alarm else colors.text
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(vertical = TimaSpacing.about2)) {
        repeat(PinRules.LENGTH) { i ->
            Box(
                Modifier.size(14.dp).border(2.dp, ink, CircleShape)
                    .then(if (i < count || (error && count == 0)) Modifier.background(ink, CircleShape) else Modifier),
            )
        }
    }
}

@Composable
private fun Keypad(enabled: Boolean, onDigit: (Char) -> Unit, onErase: () -> Unit) {
    val rows = listOf("123", "456", "789")
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) { row.forEach { Key(it.toString(), enabled) { onDigit(it) } } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(26.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(64.dp))
            Key("0", enabled) { onDigit('0') }
            Box(Modifier.size(64.dp).clickable(enabled = enabled, onClick = onErase), contentAlignment = Alignment.Center) {
                Secondary(Tima.words.pin.erase)
            }
        }
    }
}

@Composable
private fun Key(label: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = Tima.colors
    Box(
        Modifier.size(64.dp).background(colors.line, CircleShape).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Caption(label, fontSize = TimaType.sz2, weight = FontWeight.SemiBold, color = if (enabled) colors.text else colors.text3)
    }
}

@Composable
private fun PhraseStep(state: PinFlowState, temporary: Boolean, onPhrase: (String) -> Unit) {
    val w = Tima.words.pin
    var phrase by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(TimaSpacing.about3)) {
        if (state.phraseOnly) {
            Caption(w.phraseOnlyTitle, fontSize = TimaType.sz3, weight = FontWeight.ExtraBold)
            Secondary(w.phraseOnlyAbout)
        } else {
            Caption(w.forgotTitle, fontSize = TimaType.sz3, weight = FontWeight.ExtraBold)
            Secondary(if (temporary) w.forgotTemporary else w.forgotOwn)
        }
        Field(value = phrase, onChange = { phrase = it }, hint = Tima.words.auth.phraseHint, phrase = true, modifier = Modifier.fillMaxWidth())
        Button(
            label = if (state.phraseOnly) w.phraseEnter else w.checkPhrase,
            onClick = {
                if (!state.checking && phrase.isNotBlank()) {
                    onPhrase(phrase)
                    phrase = ""
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        when {
            state.checking -> Answer(null, AnswerTone.Waiting)
            state.message == PinMessage.Offline -> Answer(w.offline + ". " + w.offlineAbout, AnswerTone.Trouble)
            state.message == PinMessage.WrongPhrase -> Answer(w.wrongPhrase, AnswerTone.Trouble)
            state.message is PinMessage.Fault -> Answer(faultText((state.message as PinMessage.Fault).fault), AnswerTone.Trouble)
        }
        Tertiary(w.noSms)
    }
}

@Composable
private fun faultText(fault: PhraseFault): String {
    val a = Tima.words.auth
    return when (fault) {
        is PhraseFault.Count -> a.phraseCount(fault.got, fault.need)
        is PhraseFault.Unknown -> a.phraseUnknown(fault.words.joinToString(", ") { (n, word) -> "№$n «$word»" })
        PhraseFault.Checksum -> a.phraseChecksum
    }
}

@Composable
private fun ChoiceStep(onChoose: (Boolean) -> Unit) {
    val w = Tima.words.pin
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(TimaSpacing.about3)) {
        Answer(w.phraseOkAbout, AnswerTone.Done)
        Button(label = w.setNew, onClick = { onChoose(true) }, modifier = Modifier.fillMaxWidth())
        Button(label = w.remove, onClick = { onChoose(false) }, kind = ButtonKind.Quiet, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun Link(label: String, onClick: () -> Unit) {
    Caption(
        label,
        Modifier.clickable(onClick = onClick).padding(TimaSpacing.about2),
        fontSize = TimaType.sz5,
        weight = FontWeight.Bold,
    )
}
