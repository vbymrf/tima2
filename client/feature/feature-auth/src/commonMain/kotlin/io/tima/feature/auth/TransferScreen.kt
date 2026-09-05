package io.tima.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.tima.core.ui.Button
import io.tima.core.ui.ButtonKind
import io.tima.core.ui.Caption
import io.tima.core.ui.Field
import io.tima.core.ui.QrCodeImage
import io.tima.core.ui.Secondary
import io.tima.core.ui.SubwindowHeader
import io.tima.core.ui.Tertiary
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.Trouble

/**
 * Передача виртуального аккаунта — ПЛАН-КОНТАКТОВ.md, Д12.
 *
 * **Два предупреждения на этом экране обязательны, и оба сказаны словами, а не мелким
 * шрифтом.**
 *
 * Первое — про то, чего передача не может: она **отрезает будущее, а не прошлое**. Всё,
 * что прежний владелец уже скачал и расшифровал, остаётся у него на устройстве, и отобрать
 * это нечем. Человек, отдающий аккаунт, обязан это знать до, а не после.
 *
 * Второе — про каналы: код и фраза идут **разными путями**. Посланные одним сообщением,
 * они и есть главный способ потерять аккаунт: перехвативший переписку получает оба.
 */
@Composable
fun TransferScreen(
    state: TransferState,
    onGiveCode: () -> Unit,
    onCancel: () -> Unit,
    onCode: (String) -> Unit,
    onPhrase: (String) -> Unit,
    onTake: () -> Unit,
    onBack: () -> Unit,
    /** «Готово» после перехода аккаунта: дальше вход в него. */
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Tima.colors
    Column(modifier.fillMaxSize().background(colors.surface)) {
        SubwindowHeader(
            title = if (state.side == TransferSide.Giving) "Передать аккаунт" else "Принять аккаунт",
            onBack = onBack,
        )

        Box(
            modifier = Modifier.fillMaxSize().padding(TimaSpacing.about5),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(TimaSpacing.about4),
            ) {
                when {
                    state.taken != null -> Taken(state, onDone)
                    state.side == TransferSide.Taking -> Taking(state, onCode, onPhrase, onTake)
                    state.code != null -> Code(state, onCancel)
                    else -> Warning(state, onGiveCode)
                }
            }
        }
    }
}

/**
 * Шаг первый передающего: цена, а потом кнопка.
 *
 * Порядок здесь и есть содержание экрана. Кнопка «выдать код» над предупреждением
 * означала бы, что предупреждение читают уже после решения.
 */
@Composable
private fun Warning(state: TransferState, onGiveCode: () -> Unit) {
    Caption("Что произойдёт", fontSize = TimaType.sz2, weight = FontWeight.ExtraBold)
    Secondary(
        "Аккаунт уйдёт целиком: переписка, группы, каналы, роли и владение. Ваши " +
            "устройства в нём будут отключены, и войти в него вы больше не сможете — " +
            "код на вход приходит владельцу, а владельцем станет другой человек.",
    )
    // Это единственное, чего передача не может, и молчать об этом нельзя: человек,
    // который отдаёт аккаунт «чтобы там ничего не осталось», должен узнать правду здесь.
    Secondary(
        "Передача отрезает будущее, а не прошлое: всё, что вы уже прочитали, осталось " +
            "на вашем телефоне, и передача этого не стирает.",
    )
    Secondary(
        "Собеседники ничего не заметят: у аккаунта нет телефона, и они с самого начала " +
            "разговаривают с ником, а не с номером.",
    )

    state.trouble?.let { Trouble(it) }

    Button(
        label = if (state.working) "Готовим код…" else "Выдать код передачи",
        onClick = { if (!state.working) onGiveCode() },
        kind = if (state.working) ButtonKind.Quiet else ButtonKind.Dangerous,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Шаг второй передающего: код.
 *
 * Показывается **один раз**: в базе лежит его хэш, и второй раз показать его нечем —
 * можно только выдать новый, погасив этот.
 */
@Composable
private fun Code(state: TransferState, onCancel: () -> Unit) {
    Caption("Код передачи", fontSize = TimaType.sz2, weight = FontWeight.ExtraBold)
    Secondary(
        "Покажите его тому, кому передаёте: он наведёт камеру. Код живёт " +
            "${state.minutesLeft} минут и годится один раз.",
    )

    state.payload?.let { QrCodeImage(data = it) }

    // Тот же код строкой: камера есть не всегда и не у всех, а код диктуют и переписывают.
    state.code?.let { Tertiary(it) }

    // Главное предупреждение экрана. Код без фразы не передаёт ничего — и в этом весь
    // расчёт; посланные вместе, они его отменяют.
    Secondary(
        "Фразу аккаунта передайте ОТДЕЛЬНО и другим путём — не тем сообщением, что код. " +
            "Вместе они и есть аккаунт: перехвативший одну переписку получит оба.",
    )
    Tertiary(
        "Неверная фраза тратит попытку: после третьей код сгорит, и придётся выдать новый.",
    )

    state.trouble?.let { Trouble(it) }
    if (state.cancelled) Secondary("Передача отменена — код больше не действует")

    Button(
        label = "Отменить передачу",
        onClick = onCancel,
        kind = ButtonKind.Quiet,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Сторона принимающего: код и фраза.
 *
 * Своей камеры у приложения нет и не задумано: код читает системная, и она приводит сюда
 * ссылкой. Поле для кода при этом остаётся — код диктуют, пересылают текстом и
 * переписывают с бумаги.
 */
@Composable
private fun Taking(
    state: TransferState,
    onCode: (String) -> Unit,
    onPhrase: (String) -> Unit,
    onTake: () -> Unit,
) {
    Caption("Принять аккаунт", fontSize = TimaType.sz2, weight = FontWeight.ExtraBold)
    Secondary(
        "Нужны две вещи, и обе от того, кто передаёт: код и секретная фраза аккаунта. " +
            "Одного кода мало — он ничего не открывает без фразы.",
    )

    Caption("Код передачи", fontSize = TimaType.sz5, weight = FontWeight.Bold)
    Field(value = state.brought, onChange = onCode, hint = "наведите камеру или вставьте код")

    Caption("Секретная фраза аккаунта", fontSize = TimaType.sz5, weight = FontWeight.Bold)
    Field(value = state.phrase, onChange = onPhrase, hint = "слово слово слово…")

    state.trouble?.let { Trouble(it) }

    Button(
        label = if (state.working) "Проверяем…" else "Принять аккаунт",
        onClick = { if (!state.working) onTake() },
        kind = if (state.working) ButtonKind.Quiet else ButtonKind.Action,
        modifier = Modifier.fillMaxWidth(),
    )

    Tertiary(
        "Дальше входить в этот аккаунт вы будете со своего номера: своего телефона у " +
            "него нет, и код придёт вам.",
    )
}

/** Аккаунт перешёл. */
@Composable
private fun Taken(state: TransferState, onDone: () -> Unit) {
    Caption("Аккаунт ваш", fontSize = TimaType.sz2, weight = FontWeight.ExtraBold)
    Secondary(
        "Устройства прежнего владельца отключены, и вход в аккаунт теперь ваш. " +
            "Фразу смените: прежнюю знает тот, кто вам её дал.",
    )

    // Ротацию сервер сделать не может: групповые ключи выпускают участники (ADR-0017).
    // Пока она не прошла, прежний владелец продолжает читать группы, где аккаунт состоит,
    // — и молчать об этом нельзя, человек считает передачу законченной.
    if (state.taken?.rotateNeeded == true) {
        Secondary(
            "В группах этого аккаунта нужно сменить ключ: до этого прежний владелец " +
                "продолжит читать в них новое. Откройте состав группы и смените ключ.",
        )
    }

    Button(label = "Войти в аккаунт", onClick = onDone, modifier = Modifier.fillMaxWidth())
}
