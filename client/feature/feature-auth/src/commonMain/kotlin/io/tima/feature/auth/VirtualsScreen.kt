package io.tima.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.tima.core.ui.Avatar
import io.tima.core.ui.Button
import io.tima.core.ui.ButtonKind
import io.tima.core.ui.Caption
import io.tima.core.ui.ExpandMark
import io.tima.core.ui.ListLine
import io.tima.core.ui.Name
import io.tima.core.ui.PillTone
import io.tima.core.ui.SectionTitle
import io.tima.core.ui.Secondary
import io.tima.core.ui.StatusPill
import io.tima.core.ui.Tertiary
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.Trouble
import io.tima.core.ui.words
import io.tima.domain.account.AccountTitle

/**
 * Аккаунт в настройках «Аккаунты»: из списка устройства и из списка виртуальных с сервера.
 *
 * @param canGive виртуальный, которым распоряжается текущий аккаунт, — у него «Передать»
 */
data class AccountRow(
    val userId: String,
    val name: String,
    val nickname: String,
    val phone: String,
    val virtual: Boolean,
    val current: Boolean,
    val canGive: Boolean,
)

/**
 * Настройки → «Аккаунты» (было «Виртуальные аккаунты»; ПЛАН-(Д)-КОНТАКТОВ.md, Д10…Д12; заказчик
 * 2026-10-07, пробы `пробы-окно-переходов.html`, Д и З).
 *
 * Все аккаунты устройства одной подписью ([AccountTitle] и [accountDetail]), разделитель
 * «Основной» / «Виртуальные». У текущего — пин-код значком с подписью «Пин» под ним (перенесён сюда
 * из «Секретная фраза и устройства»); у виртуальных, которыми распоряжается текущий, — «Передать».
 * Ниже прежние действия: завести, принять.
 *
 * **Про анонимность сказано честно и здесь тоже.** Виртуальный аккаунт прячет связь с
 * основным от собеседников, а не от нас: привязка лежит на сервере открыто, иначе он не
 * смог бы её проверять.
 */
@Composable
fun VirtualsScreen(
    state: VirtualsState,
    onCreate: () -> Unit,
    /** Передать этот аккаунт другому человеку. */
    onGive: (String) -> Unit,
    onTake: () -> Unit,
    modifier: Modifier = Modifier,
    /** Аккаунты для списка; пусто — список строится из одних виртуальных с сервера, как раньше. */
    rows: List<AccountRow> = emptyList(),
    /** Пин-код текущего аккаунта: включён ли; `null` — значка нет. */
    pinOn: Boolean? = null,
    /** Включить, сменить, убрать, забыли — открывает экран пин-кода. */
    onPin: ((PinMode) -> Unit)? = null,
    /** Ответ после экрана пин-кода — «Пин-код включён» и т. п. */
    pinNotice: String? = null,
) {
    val colors = Tima.colors
    val words = Tima.words.auth
    val list = Tima.words.accountList
    val shown = rows.ifEmpty {
        state.accounts.map { AccountRow(it.userId, "", it.nickname, "", virtual = true, current = false, canGive = true) }
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.surface)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
    ) {
        Column(modifier = Modifier.padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about3)) {
            Secondary(words.virtualAboutShort)
        }

        state.trouble?.let {
            Column(modifier = Modifier.padding(horizontal = TimaSpacing.about4)) { Trouble(it) }
        }

        val main = shown.filterNot { it.virtual }
        if (main.isNotEmpty()) {
            SectionTitle(list.main)
            main.forEach { AccountLine(it, pinOn, onPin, pinNotice, onGive) }
        }

        SectionTitle(list.virtuals)
        val virtuals = shown.filter { it.virtual }
        if (virtuals.isNotEmpty()) {
            virtuals.forEach { AccountLine(it, pinOn, onPin, pinNotice, onGive) }
        } else if (state.asked && !state.working) {
            // Пусто после ответа сервера — «пока нет»; до ответа пустота ничего не значит.
            Column(modifier = Modifier.padding(horizontal = TimaSpacing.about4)) {
                Secondary(words.noVirtualsYet)
            }
        }

        SectionTitle(words.actions)
        ListLine(
            onClick = onCreate,
            left = { Avatar(letters = "＋") },
            middle = {
                Column {
                    Name(words.createVirtual)
                    Tertiary(words.fiveAtMostShort, lineOne = true)
                }
            },
        )
        ListLine(
            onClick = onTake,
            left = { Avatar(letters = "↓") },
            middle = {
                Column {
                    Name(words.takeAccount)
                    Tertiary(words.takeNeedsCodeAndPhrase, lineOne = true)
                }
            },
        )

        Column(modifier = Modifier.padding(TimaSpacing.about4)) {
            Tertiary(words.linkNotHiddenLong)
        }
    }
}

/** Строка аккаунта; у текущего — значок пин-кода, под строкой раскрываются его действия. */
@Composable
private fun AccountLine(
    row: AccountRow,
    pinOn: Boolean?,
    onPin: ((PinMode) -> Unit)?,
    pinNotice: String?,
    onGive: (String) -> Unit,
) {
    val list = Tima.words.accountList
    val words = Tima.words.auth
    val title = AccountTitle.of(row.name, row.nickname, row.userId)
    val withPin = row.current && pinOn != null && onPin != null
    var open by rememberSaveable(row.userId) { mutableStateOf(false) }
    ListLine(
        left = { Avatar(letters = title.trimStart('@').take(1).uppercase().ifBlank { "?" }) },
        middle = {
            Column {
                Name(title)
                accountDetail(row.name, row.nickname, row.phone, row.virtual, list.virtualMark)
                    .takeIf { it.isNotBlank() }?.let { Tertiary(it, lineOne = true) }
                if (row.current) StatusPill(list.current, PillTone.Good)
            }
        },
        right = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2)) {
                if (withPin) PinButton(on = pinOn == true, open = open) { open = !open }
                if (row.virtual && row.canGive) {
                    Button(label = words.give, onClick = { onGive(row.userId) }, kind = ButtonKind.Quiet)
                }
            }
        },
    )
    if (withPin && open && onPin != null) PinOptions(pinOn == true, onPin)
    if (withPin) {
        pinNotice?.let {
            io.tima.core.ui.Answer(it, io.tima.core.ui.AnswerTone.Done, Modifier.padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2))
        }
    }
}

/**
 * «Пин» — значок замка сверху, подпись снизу (заказчик 2026-10-07: «пин текстом снизу, сверху
 * иконка»). Включён — зелёная рамка и подложка, выключен — серая рамка и бледный замок.
 */
@Composable
private fun PinButton(on: Boolean, open: Boolean, onClick: () -> Unit) {
    val colors = Tima.colors
    Column(
        Modifier.testTag("pin-button").clickable(onClick = onClick).padding(horizontal = TimaSpacing.about1),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(PIN_SIDE)
                .border(if (on || open) 2.dp else 1.5.dp, if (on || open) colors.navigation else colors.text3, CircleShape)
                .background(if (on) colors.softAccent else colors.surface, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Caption("🔒", fontSize = TimaType.sz5, modifier = if (on) Modifier else Modifier.alpha(0.35f))
        }
        Caption(Tima.words.accountList.pin, fontSize = TimaType.sz6, weight = FontWeight.Bold)
    }
}

/** Действия пин-кода — те же, что были в «Вход»: выключен — «Включить»; включён — сменить, убрать, забыли. */
@Composable
internal fun PinOptions(pinOn: Boolean, onPin: (PinMode) -> Unit) {
    val pw = Tima.words.pin
    Column(Modifier.fillMaxWidth().background(Tima.colors.softAccent)) {
        if (!pinOn) {
            PinChoice(pw.offChoice, pw.offChoiceAbout, selected = true) {}
            PinChoice(pw.enable, pw.enableAbout, selected = false) { onPin(PinMode.Enable) }
        } else {
            PinAction(pw.change, pw.changeAbout) { onPin(PinMode.Change) }
            PinAction(pw.remove, pw.removeAbout) { onPin(PinMode.Remove) }
            PinAction(pw.forgot, pw.forgotAbout) { onPin(PinMode.Forgot) }
        }
    }
}

@Composable
private fun PinChoice(title: String, about: String, selected: Boolean, onClick: () -> Unit) = ListLine(
    modifier = Modifier.padding(start = TimaSpacing.about5),
    onClick = onClick,
    left = { io.tima.core.ui.CheckMark(selected) },
    middle = {
        Caption(title, fontSize = TimaType.sz4, weight = if (selected) FontWeight.Bold else FontWeight.Normal)
        Tertiary(about)
    },
)

@Composable
private fun PinAction(title: String, about: String, onClick: () -> Unit) = ListLine(
    modifier = Modifier.padding(start = TimaSpacing.about5),
    onClick = onClick,
    middle = {
        Caption(title, fontSize = TimaType.sz4, weight = FontWeight.Bold)
        Tertiary(about)
    },
    right = { ExpandMark() },
)

/** Сторона значка пин-кода — как у «?» в настройках. */
private val PIN_SIDE = 30.dp
