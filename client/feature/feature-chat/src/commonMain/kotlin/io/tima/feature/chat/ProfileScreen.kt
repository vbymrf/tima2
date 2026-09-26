package io.tima.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.ImageBitmap
import io.tima.core.media.decodeImage
import io.tima.core.media.encodeJpeg
import io.tima.core.media.rememberImagePicker
import io.tima.core.ui.AvatarSize
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
import io.tima.core.ui.words
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
 * правка профиля, а другая работа. Приходит из `GET /users/me` вместе с именем и
 * ником (0050): до этого экран открывался пустым.
 *
 * **Ник — один раз на личность, имя — сколько угодно** (решение заказчика 2026-09-15).
 * Заперт ник показывается текстом, а не полем: поле, не принимающее правку, выглядит
 * поломкой.
 *
 * **Аватар — картинка с устройства, обрезанная квадратом** (решение заказчика 2026-09-15).
 * Выбор — системным выборщиком, обрезка — [AvatarCropScreen], на сервер уходит JPEG
 * 512×512 через медиа-хранилище по «Сохранить». Нет картинки — буквы имени, как в
 * списках.
 */
@Composable
fun ProfileScreen(
    state: ProfileState,
    onName: (String) -> Unit,
    onNickname: (String) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Обрезанный аватар в JPEG. `null` — выбор аватара на этом экране не предлагается. */
    onAvatar: ((ByteArray) -> Unit)? = null,
    onAvatarRemove: (() -> Unit)? = null,
    /**
     * Рисовать ли свою шапку. Из настроек — `false`: там шапку подокна рисует
     * `SettingsScreen`, «одна на подокно», и вторая с тем же словом «Профиль» стояла
     * под первой. Поймано живьём 2026-09-15 на Samsung — ровно та же поломка, что
     * была у экрана языка неделей раньше. Из переключения окон экран стоит сам и
     * шапка нужна.
     */
    withHeader: Boolean = true,
) {
    val colors = Tima.colors
    val words = Tima.words.chat

    // Выбранная, но ещё не обрезанная картинка: пока она есть, вместо профиля — обрезка.
    var cropping by remember { mutableStateOf<ImageBitmap?>(null) }
    var notImage by remember { mutableStateOf(false) }
    val pick = rememberImagePicker { picked ->
        if (picked == null) return@rememberImagePicker
        val decoded = decodeImage(picked.bytes)
        notImage = decoded == null
        cropping = decoded
    }
    cropping?.let { image ->
        AvatarCropScreen(
            image = image,
            onDone = { cut ->
                onAvatar?.invoke(encodeJpeg(cut))
                cropping = null
            },
            onCancel = { cropping = null },
            modifier = modifier,
        )
        return
    }
    val shown = remember(state.avatarBytes) { state.avatarBytes?.let(::decodeImage) }

    Column(modifier.fillMaxSize().background(colors.surface)) {
        if (withHeader) SubwindowHeader(title = words.profile, onBack = onBack)

        // «СОХРАНЕНО» — сразу под шапкой, в плашке её цвета и заглавными (заказчик
        // 2026-09-26). Внизу, у кнопки, его не видели: после нажатия взгляд уходит вверх,
        // к шапке, а не остаётся на кнопке. Вне прокрутки — чтобы не уехало за край.
        if (state.saved) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .background(colors.functional, RoundedCornerShape(TimaSpacing.about4))
                        .padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2),
                ) {
                    Caption(words.saved.uppercase(), fontSize = TimaType.sz5, weight = FontWeight.Bold)
                }
            }
        }

        // Прокрутка обязательна: аватар, три поля, две подсказки и кнопка на телефоне
        // в 640 точек не помещаются — а без прокрутки низ просто нет (урок экрана языка).
        Box(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(TimaSpacing.about5),
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
                    // Без имени — пусто, а не «+»: плюс читается «добавить» (заказчик 2026-09-26),
                    // а аватар здесь ничего не добавляет.
                    Avatar(
                        letters = state.name.take(1).uppercase(),
                        size = AvatarSize.Big,
                        image = shown,
                    )
                    Column {
                        Caption(
                            text = state.name.ifBlank { words.nameless },
                            fontSize = TimaType.sz3,
                            weight = FontWeight.ExtraBold,
                        )
                        // Номер показывается, только если он известен: сессия его не
                        // хранит (в ней userId, deviceId и токен), и выдумывать строку
                        // на его месте нельзя.
                        if (state.phone.isNotBlank()) Tertiary(state.phone, lineOne = true)
                    }
                }

                if (onAvatar != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2)) {
                        Button(label = words.avatarChange, kind = ButtonKind.Quiet, onClick = pick)
                        // «Убрать» есть, только когда есть что убирать.
                        if (shown != null && onAvatarRemove != null) {
                            Button(label = words.avatarRemove, kind = ButtonKind.Dangerous, onClick = onAvatarRemove)
                        }
                    }
                    if (notImage) Secondary(words.avatarNotImage)
                }

                if (state.nameless) {
                    // Пустое имя не прячется: пока его нет, собеседники видят номер, и
                    // человек должен узнать об этом здесь, а не от собеседника.
                    Secondary(words.nameNotSetYet)
                }

                Caption(words.nameHowShown, fontSize = TimaType.sz5, weight = FontWeight.Bold)
                Field(value = state.name, onChange = onName, hint = words.nameExample)

                Caption(words.nicknameFound, fontSize = TimaType.sz5, weight = FontWeight.Bold)
                if (state.nickEditable) {
                    Field(value = state.nickname, onChange = onNickname, hint = "petr_smirnov")
                    // Занятость сказана до нажатия: узнать о ней после отправки формы значит
                    // потерять уже введённое.
                    state.aboutNick(Tima.words)?.let { Secondary(it) }
                    // И то, что попытка одна, — тоже до нажатия. Узнать об этом после
                    // «Сохранить» значит узнать, что поправить опечатку уже нельзя.
                    Tertiary(words.nicknameOnce)
                } else {
                    // Задан и закреплён за этой фразой: текст, а не поле. Поле, которое
                    // не принимает правку, выглядит поломкой; текст выглядит фактом.
                    Caption("@" + state.nickname, fontSize = TimaType.sz3, weight = FontWeight.Bold)
                    Tertiary(words.nicknameLocked)
                }
                state.trouble?.let { Trouble(it) }

                // Почему кнопка погашена — красным, прямо над ней (заказчик 2026-09-26).
                // Красный здесь по прямой просьбе: серая строка про ник уже стояла у поля, и
                // её не связывали с кнопкой.
                state.whyNoSave(Tima.words)?.let {
                    Caption(it, fontSize = TimaType.sz5, weight = FontWeight.Bold, color = colors.alarm)
                }

                Button(
                    label = words.save,
                    onClick = { if (state.canSave) onSave() },
                    kind = if (state.canSave) ButtonKind.Action else ButtonKind.Quiet,
                )

                // Сказано прямо, а не умолчанием: ник, однажды занятый, остаётся за
                // человеком — иначе старые упоминания начали бы указывать на другого.
                if (state.nickEditable) Tertiary(words.nicknameNeverFreed)
            }
        }
    }
}
