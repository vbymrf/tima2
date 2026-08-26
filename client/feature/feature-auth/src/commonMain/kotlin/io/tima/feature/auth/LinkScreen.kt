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
    when (state) {
        is LinkState.Ask -> Ask(state, onTrust, onCancel)

        is LinkState.Done -> {
            Caption("Устройство подключено", weight = FontWeight.ExtraBold)
            Secondary(
                "Новые сообщения будут приходить и на него. Прежняя переписка туда не " +
                    "переедет: ключи старых сообщений оборачивались на другие устройства.",
            )
            Button("Готово", onClick = onCancel, modifier = Modifier.fillMaxWidth())
        }

        LinkState.NotOurCode -> {
            Caption("Это не код подключения", weight = FontWeight.ExtraBold)
            Secondary(
                "Отсканирован другой код. Откройте на компьютере «Подключить к аккаунту» " +
                    "и наведите камеру на код оттуда.",
            )
            Button("Закрыть", onClick = onCancel, kind = ButtonKind.Quiet, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun Ask(
    state: LinkState.Ask,
    onTrust: () -> Unit,
    onCancel: () -> Unit,
) {
    Caption("Подтвердить подключение?", weight = FontWeight.ExtraBold)

    // Имя устройства — то, что человек видел минуту назад на своём компьютере. Если его
    // в коде не было, так и говорим: подставленное имя он примет за настоящее.
    Secondary(state.name?.let { "Устройство: $it" } ?: "Устройство себя не назвало")

    Tertiary(
        "Подключённое устройство сможет читать новые сообщения этого аккаунта и писать " +
            "от вашего имени. Отключить его можно в списке устройств.",
    )

    state.trouble?.let { Trouble(it) }

    Button(
        label = if (state.expect) "Подключаем…" else "Доверить",
        onClick = onTrust,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        label = "Отклонить",
        onClick = onCancel,
        kind = ButtonKind.Dangerous,
        modifier = Modifier.fillMaxWidth(),
    )
}
