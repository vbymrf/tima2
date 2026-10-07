package io.tima.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.tima.core.ui.ListLine
import io.tima.core.ui.Name
import io.tima.core.ui.SectionTitle
import io.tima.core.ui.Secondary
import io.tima.core.ui.Tima
import io.tima.core.ui.words
import io.tima.feature.auth.PinFlowStore
import io.tima.feature.auth.PinMode
import io.tima.feature.auth.PinScreen
import io.tima.feature.auth.PinStep
import io.tima.feature.auth.PinWho

/** Другой аккаунт этого устройства — для «Другой аккаунт» на замке. */
data class LockAccount(val userId: String, val name: String)

/**
 * Замок пин-кода поверх приложения (ПЛАН-(ПН), пробы «ж», «и»).
 *
 * Перекрывает экран целиком и глотает нажатия. Под ним работает всё — канал, приём, звонки:
 * входящий звонок слой не закрывает (Р11), его не рисуют, пока звонок идёт.
 *
 * @param onCancel «Отмена» — после перехода на аккаунт с пином: вернуться к прежнему.
 * @param others другие аккаунты устройства — «Другой аккаунт».
 */
@Composable
fun PinLockLayer(
    pin: PinHost,
    who: PinWho,
    onUnlocked: () -> Unit,
    onCancel: (() -> Unit)?,
    others: List<LockAccount>,
    onSwitch: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val store = remember(pin) {
        PinFlowStore(PinMode.Unlock, pin.lock, pin.verify, scope, { msNow() }, ::notePin)
    }
    val state by store.state.collectAsState()
    var choosing by remember { mutableStateOf(false) }
    LaunchedEffect(state.step) { if (state.step == PinStep.Done) onUnlocked() }
    Column(
        Modifier.fillMaxSize().background(Tima.colors.surface)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        if (choosing) {
            SectionTitle(Tima.words.pin.otherAccount)
            others.forEach { other ->
                ListLine(onClick = { onSwitch(other.userId) }, left = { Name("👤") }, middle = { Secondary(other.name) })
            }
            ListLine(onClick = { choosing = false }, middle = { Secondary(Tima.words.pin.cancel) })
        } else {
            PinScreen(
                state = state,
                onDigit = store::digit,
                onErase = store::erase,
                onForgot = store::forgot,
                onPhrase = store::submitPhrase,
                onChoose = store::choose,
                onTick = store::tick,
                now = { msNow() },
                who = who,
                onCancel = onCancel,
                onOther = if (onCancel == null && others.isNotEmpty()) ({ choosing = true }) else null,
            )
        }
    }
}
