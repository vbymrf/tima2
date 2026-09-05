package io.tima.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.tima.core.ui.Avatar
import io.tima.core.ui.Button
import io.tima.core.ui.ButtonKind
import io.tima.core.ui.Caption
import io.tima.core.ui.Field
import io.tima.core.ui.Secondary
import io.tima.core.ui.SubwindowHeader
import io.tima.core.ui.Tertiary
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.Trouble

/**
 * Экран профиля — ПЛАН-КОНТАКТОВ.md, Д8.
 *
 * **Сюда ведут две ссылки в одно место**: «Изменить» в переключении окон и вкладка
 * «Профиль» в настройках. Второй вход не дубль: настройки — то место, где человек ищет
 * «где это поменять», не помня, откуда он туда попал.
 *
 * **Телефон показан, но не правится**: по нему заведён аккаунт, и сменить его — не
 * правка профиля, а другая работа.
 *
 * **Аватара как картинки нет**: буквы имени — то же, что в списках. Загрузка изображений
 * — отдельная работа с медиа, и рисовать «＋» там, где грузить нечем, значит обещать.
 */
@Composable
fun ProfileScreen(
    state: ProfileState,
    onName: (String) -> Unit,
    onNickname: (String) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Tima.colors
    Column(modifier.fillMaxSize().background(colors.surface)) {
        SubwindowHeader(title = "Профиль", onBack = onBack)

        Box(
            modifier = Modifier.fillMaxSize().padding(TimaSpacing.about5),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(TimaSpacing.about4),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(letters = state.name.take(1).ifBlank { "＋" }.uppercase())
                    Column {
                        Caption(
                            text = state.name.ifBlank { "Без имени" },
                            fontSize = TimaType.sz3,
                            weight = FontWeight.ExtraBold,
                        )
                        // Номер показывается, только если он известен: сессия его не
                        // хранит (в ней userId, deviceId и токен), и выдумывать строку
                        // на его месте нельзя.
                        if (state.phone.isNotBlank()) Tertiary(state.phone, lineOne = true)
                    }
                }

                if (state.nameless) {
                    // Пустое имя не прячется: пока его нет, собеседники видят номер, и
                    // человек должен узнать об этом здесь, а не от собеседника.
                    Secondary("Пока имя не задано, собеседники видят ваш номер.")
                }

                Caption("Имя — как вас показывать другим", fontSize = TimaType.sz5, weight = FontWeight.Bold)
                Field(value = state.name, onChange = onName, hint = "Пётр Смирнов")

                Caption("Ник — по нему вас найдут", fontSize = TimaType.sz5, weight = FontWeight.Bold)
                Field(value = state.nickname, onChange = onNickname, hint = "petr_smirnov")
                // Занятость сказана до нажатия: узнать о ней после отправки формы значит
                // потерять уже введённое.
                state.aboutNick?.let { Secondary(it) }
                state.trouble?.let { Trouble(it) }
                if (state.saved) Secondary("Сохранено")

                Button(
                    label = "Сохранить",
                    onClick = { if (state.canSave) onSave() },
                    kind = if (state.canSave) ButtonKind.Action else ButtonKind.Quiet,
                )

                // Сказано прямо, а не умолчанием: ник, однажды занятый, остаётся за
                // человеком — иначе старые упоминания начали бы указывать на другого.
                Tertiary("Занятый ник не освобождается: сменив его, вы не отдадите прежний.")
            }
        }
    }
}
