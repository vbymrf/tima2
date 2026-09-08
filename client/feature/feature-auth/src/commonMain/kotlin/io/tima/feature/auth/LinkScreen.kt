package io.tima.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import io.tima.core.ui.Trouble
import io.tima.core.ui.Secondary
import io.tima.core.ui.ButtonKind
import io.tima.core.ui.Button
import io.tima.core.ui.Caption
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.Tima
import io.tima.core.ui.words
import io.tima.core.ui.Tertiary

/**
 * «Подтвердить подключение?» — экран на телефоне после скана.
 *
 * Отдельный экран, а не всплывающее сообщение поверх переписки: человек только что навёл
 * камеру на код и должен понять, **что именно** он сейчас разрешает. Всплывающее закрывают
 * не читая.
 *
 * Цена названа прямо: подключённое устройство получит доступ к новым сообщениям аккаунта.
 * Без этой строки «Доверить» читается как «ок».
 */
@Composable
fun LinkScreen(
    state: LinkState,
    onTrust: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) = Column(
    modifier = modifier
        .fillMaxSize()
        .background(Tima.colors.surface)
        .padding(TimaSpacing.about4),
    verticalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
) {
    val words = Tima.words.auth
    when (state) {
        is LinkState.Ask -> Ask(state, onTrust, onCancel)

        is LinkState.Done -> {
            Caption(words.deviceConnected, weight = FontWeight.ExtraBold)
            Secondary(words.deviceConnectedAbout)
            Button(
                Tima.words.common.ready,
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        LinkState.NotOurCode -> {
            Caption(words.notConnectionCode, weight = FontWeight.ExtraBold)
            Secondary(words.notConnectionCodeAbout)
            Button(
                Tima.words.common.hide,
                onClick = onCancel,
                kind = ButtonKind.Quiet,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun Ask(
    state: LinkState.Ask,
    onTrust: () -> Unit,
    onCancel: () -> Unit,
) {
    val words = Tima.words.auth
    Caption(words.confirmConnection, weight = FontWeight.ExtraBold)

    // Имя устройства — то, что человек видел минуту назад на своём компьютере. Если его
    // в коде не было, так и говорим: подставленное имя он примет за настоящее.
    Secondary(state.name?.let { words.deviceNamed(it) } ?: words.deviceUnnamed)

    Tertiary(words.connectedDeviceCan)

    state.trouble?.let { Trouble(it) }

    Button(
        label = if (state.expect) words.connecting else words.trust,
        onClick = onTrust,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        label = words.reject,
        onClick = onCancel,
        kind = ButtonKind.Dangerous,
        modifier = Modifier.fillMaxWidth(),
    )
}
