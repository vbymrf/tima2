package io.tima.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import io.tima.core.ui.Trouble
import io.tima.core.ui.Field
import io.tima.core.ui.ButtonKind
import io.tima.core.ui.Secondary
import io.tima.core.ui.Button
import io.tima.core.ui.Caption
import io.tima.core.ui.EmptyArea
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.Tima
import io.tima.core.ui.words
import io.tima.core.ui.Tertiary
import io.tima.core.ui.SettingLine
import io.tima.core.ui.PillTone
import io.tima.core.ui.ConfirmPlate
import io.tima.core.ui.HelpSheet
import io.tima.core.ui.HelpLabels
import io.tima.core.ui.HelpMark
import io.tima.core.ui.ListLine
import io.tima.core.ui.SectionTitle
import io.tima.core.words.HelpTopic
import androidx.compose.foundation.layout.Box
import io.tima.domain.account.AccountDevice

/**
 * «Секретная фраза и устройства» — вкладка подокна «Настройки».
 *
 * **По группам, как «Уведомления»** (ПЛАН-(ПН)-ПИН-КОДА §4, пробы `пробы-пин-код.html`, приняты
 * заказчиком 2026-10-07): «Вход» — выход из аккаунта и пин-код — сверху; дальше «Это устройство»,
 * «Устройства», «История»; красный «Аккаунт» — заявки спора, смена номера, перерегистрация и
 * запрет «Начать заново без фразы» — последним. У каждого пункта «?» с подокном «что делает и
 * что получится»; нажатие на пункт «Аккаунта» — красная плашка с подтверждением. Состояние —
 * пузырём под описанием, а не справа: крупный шрифт из «Шрифты и размеры» его не ужмёт.
 *
 * **Своё устройство помечено.** Строки похожи — «Телефон» и «Телефон», — и без пометки
 * человек однажды отключит то, с которого смотрит. Отключение спрашивает подтверждение и
 * называет цену: вернуть отозванное нельзя.
 */
@Composable
fun DeviceScreen(
    state: DevicesState,
    onAsk: (String) -> Unit,
    onConfirm: () -> Unit,
    onChangedMind: () -> Unit,
    modifier: Modifier = Modifier,
    /** Номер сборки — мелко внизу; пусто — строки нет (проверки, снимки). */
    buildVersion: String = "",
    /** Выйти из аккаунта на этом устройстве (А4). `null` — строки нет. */
    onSignOut: (() -> Unit)? = null,
    /** Список не пришёл — запросить снова (А7). */
    onRetry: (() -> Unit)? = null,
    /** Сканировать код подключения — только телефон (1б). `null` — строки нет. */
    onScan: (() -> Unit)? = null,
    /** Ключа служебной группы нет — копия контактов не придёт. `null` — панели нет. */
    onRequestKey: ((String) -> Unit)? = null,
    keyNotice: String? = null,
    keySending: Boolean = false,
    /** Подтвердить это устройство фразой (ДУ5); `null` — действия нет. */
    onConfirmWithPhrase: ((String) -> Unit)? = null,
    /** Заверить другое своё устройство ключом этого телефона; `null` — этот телефон ключа не держит. */
    onCertify: ((String) -> Unit)? = null,
    /** Отменить новую личность фразой (ДУ6); `null` — отменять нечем. */
    onCancelNewIdentity: ((String) -> Unit)? = null,
    /** Показать код заверения этого устройства (Р32); `null` — кнопки нет. */
    onShowCertifyCode: (() -> Unit)? = null,
    /** Запрет «Начать заново» (ДУ10): отправить SMS; `null` — строки нет. */
    onSendBanCode: (() -> Unit)? = null,
    onBanStartAnew: ((String, String) -> Unit)? = null,
    /** Сменить ключ копии фразой — после отключения своего устройства (М5). */
    onRotateCopy: ((String) -> Unit)? = null,
    /** Завести копию ключей фразой (Р44). */
    onStartCopy: ((String) -> Unit)? = null,
    /** Перерегистрация (ДУ9): что показать; `null` — строки нет. */
    rereg: ReregView? = null,
    onStartRereg: ((String) -> Unit)? = null,
    onSendReregCode: (() -> Unit)? = null,
    onRereg: ((Boolean, String, String?, String) -> Unit)? = null,
    /** Смена номера (ДУ9): что показать; `null` — строки нет. */
    phoneChange: PhoneChangeView? = null,
    onSendPhoneCode: ((Boolean) -> Unit)? = null,
    onPhoneChange: ((String?, String, String) -> Unit)? = null,
) {
    var help by remember { mutableStateOf<HelpTopic?>(null) }
    Box(modifier.fillMaxSize().background(Tima.colors.surface)) {
        // Экран прокручивается целиком (живая проверка 2026-10-06): панели не помещались на
        // телефон, и ответ под ними уходил за край.
        Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            if (state.ask != null) {
                Question(
                    name = state.devices.firstOrNull { it.deviceId == state.ask }?.name.orEmpty(),
                    onConfirm = onConfirm,
                    onChangedMind = onChangedMind,
                )
                return@Column
            }
            state.trouble?.let { Column(Modifier.padding(TimaSpacing.about4)) { Trouble(it) } }
            EntryGroup(state, onSignOut, onScan) { help = it }
            ThisDeviceGroup(state, onConfirmWithPhrase, onShowCertifyCode) { help = it }
            DevicesGroup(state, onAsk, onRetry) { help = it }
            HistoryGroup(state, onStartCopy, onRotateCopy, onRequestKey, keyNotice, keySending) { help = it }
            AccountGroup(
                state, onCancelNewIdentity, onSendBanCode, onBanStartAnew, rereg, onStartRereg, onSendReregCode, onRereg,
                phoneChange, onSendPhoneCode, onPhoneChange,
            ) { help = it }
            // Ответ без панели — на прежнем месте; с панелью — под ней (отчёт DGAR).
            if (state.noticeAt == null) TrustAnswer(state)
            if (buildVersion.isNotBlank()) {
                Tertiary(Tima.words.auth.build(buildVersion), Modifier.padding(TimaSpacing.about4))
            }
        }
        help?.let { topic ->
            val pw = Tima.words.pin
            val text = pw.help(topic)
            HelpSheet(
                title = text.title,
                does = text.does,
                result = text.result,
                labels = HelpLabels(pw.helpDoes, pw.helpResult, pw.helpOk),
                danger = topic in DANGER,
                onClose = { help = null },
            )
        }
    }
}

/** Пункты красного раздела «Аккаунт» — их подокно «?» с красным заголовком. */
private val DANGER = setOf(HelpTopic.PhoneChange, HelpTopic.Rereg, HelpTopic.Ban, HelpTopic.Disputes)

/**
 * «Вход»: выход из аккаунта и сканер кода (заказчик 2026-10-07: «Сканировать код» — ниже «Выйти из
 * аккаунта»). Пин-код отсюда ушёл в Настройки → «Аккаунты» (заказчик 2026-10-07).
 */
@Composable
private fun EntryGroup(
    state: DevicesState,
    onSignOut: (() -> Unit)?,
    onScan: (() -> Unit)?,
    onHelp: (HelpTopic) -> Unit,
) {
    if (onSignOut == null && onScan == null) return
    val pw = Tima.words.pin
    val words = Tima.words.auth
    SectionTitle(pw.groupEntry)
    if (onSignOut != null) {
        var asking by rememberSaveable { mutableStateOf(false) }
        // Выход — до всего остального: он нужен и тогда, когда список устройств не пришёл
        // (ПК 2026-09-30 — «Смотрим…» без конца, а выйти было неоткуда).
        SettingLine("🚪", words.signOut, pw.signOutShort, onClick = { asking = !asking }, onHelp = { onHelp(HelpTopic.SignOut) }, open = asking)
        if (asking) {
            val last = state.devices.size == 1
            ConfirmPlate(
                title = pw.signOutAsk,
                text = if (last) words.signOutAbout + " " + words.signOutLast else words.signOutAbout,
                cancel = pw.cancel,
                action = words.signOutYes,
                danger = false,
                onCancel = { asking = false },
                onAction = {
                    asking = false
                    onSignOut()
                },
            )
        }
    }
    if (onScan != null) {
        // Сканер — вход другого своего устройства в аккаунт, поэтому он здесь, а не в «Устройствах».
        SettingLine("📷", words.scanCode, words.scanCodeAbout, onClick = onScan, onHelp = { onHelp(HelpTopic.Scan) })
    }
    NoticeUnder(state, TrustPanel.Scan)
}

/** «Это устройство»: заверено ли; не заверено — фраза или код заверения прямо здесь. */
@Composable
private fun ThisDeviceGroup(
    state: DevicesState,
    onConfirmWithPhrase: ((String) -> Unit)?,
    onShowCertifyCode: (() -> Unit)?,
    onHelp: (HelpTopic) -> Unit,
) {
    val self = state.devices.firstOrNull { it.current } ?: return
    val words = Tima.words.auth
    SectionTitle(Tima.words.pin.groupThisDevice)
    SettingLine(
        "📱",
        self.name.ifEmpty { words.nameless },
        listOfNotNull(words.thisDevice, self.createdAt).filter { it.isNotBlank() }.joinToString(" · "),
        onClick = null,
        pill = if (self.certified) words.deviceCertified else words.deviceUncertified,
        pillTone = if (self.certified) PillTone.Good else PillTone.Waiting,
        onHelp = { onHelp(HelpTopic.ThisDevice) },
    )
    if (self.certified) return
    // Не заверено (ДУ5): фраза — или код заверения своим телефоном (Р32).
    if (onConfirmWithPhrase != null) TrustByPhrase(onConfirmWithPhrase, state.trusting)
    if (onShowCertifyCode != null) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2),
            verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
        ) {
            val code = state.certifyCode
            if (code == null) {
                Button(label = words.showCertifyCode, onClick = onShowCertifyCode, kind = ButtonKind.Quiet, modifier = Modifier.fillMaxWidth())
            } else {
                Secondary(words.certifyCodeAbout)
                io.tima.core.ui.QrCodeImage(code, Modifier.fillMaxWidth().padding(TimaSpacing.about4))
            }
        }
    }
    NoticeUnder(state, TrustPanel.Confirm)
}

/** «Устройства»: остальные устройства аккаунта, отключение. Сканер — в «Вход». */
@Composable
private fun DevicesGroup(
    state: DevicesState,
    onAsk: (String) -> Unit,
    onRetry: (() -> Unit)?,
    onHelp: (HelpTopic) -> Unit,
) {
    val words = Tima.words.auth
    SectionTitle(Tima.words.pin.groupDevices)
    if (state.devices.isEmpty()) {
        // **Пустой список и неудавшийся запрос — разные вещи** (2026-08-26): сервер пустой список
        // отдать не может — своё устройство обязано быть в ответе. Пусто здесь — «списка нет».
        EmptyArea(
            title = when {
                state.expect -> words.watching
                state.trouble != null -> words.listNotCame
                else -> words.noDevices
            },
            explanation = when {
                state.expect -> null
                state.trouble != null -> words.reasonAbove
                else -> words.emptyListIsOurs
            },
        )
        if (!state.expect && onRetry != null) {
            Button(label = words.retryList, onClick = onRetry, modifier = Modifier.fillMaxWidth().padding(horizontal = TimaSpacing.about4))
        }
    }
    state.devices.filterNot { it.current }.forEach { device ->
        androidx.compose.runtime.key(device.deviceId) { OtherDevice(device, onAsk, onHelp) }
    }
    NoticeUnder(state, TrustPanel.Certify)
}

/**
 * Другое своё устройство. «Заверить» по строке списка убрано (Р32): список приходит от сервера, и
 * по нему заверялось бы и устройство вора — заверяют по QR. Отключить — раскрытием строки.
 */
@Composable
private fun OtherDevice(device: AccountDevice, onAsk: (String) -> Unit, onHelp: (HelpTopic) -> Unit) {
    val words = Tima.words.auth
    var open by rememberSaveable { mutableStateOf(false) }
    SettingLine(
        "📱",
        device.name.ifEmpty { words.nameless },
        device.createdAt?.ifEmpty { null },
        onClick = { open = !open },
        pill = if (device.certified) words.deviceCertified else words.deviceUncertified,
        pillTone = if (device.certified) PillTone.Good else PillTone.Waiting,
        onHelp = { onHelp(HelpTopic.Devices) },
        open = open,
    )
    if (open) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2),
            horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(label = words.disconnect, onClick = { onAsk(device.deviceId) }, kind = ButtonKind.Dangerous, modifier = Modifier.weight(1f))
            HelpMark { onHelp(HelpTopic.Disconnect) }
        }
    }
}

/** «История»: копия переписки и ключ копии контактов. */
@Composable
private fun HistoryGroup(
    state: DevicesState,
    onStartCopy: ((String) -> Unit)?,
    onRotateCopy: ((String) -> Unit)?,
    onRequestKey: ((String) -> Unit)?,
    keyNotice: String?,
    keySending: Boolean,
    onHelp: (HelpTopic) -> Unit,
) {
    val pw = Tima.words.pin
    val copyKnown = onStartCopy != null || onRotateCopy != null
    if (!copyKnown && onRequestKey == null && keyNotice == null) return
    SectionTitle(pw.groupHistory)
    if (copyKnown) {
        var open by rememberSaveable { mutableStateOf(false) }
        val canStart = state.copyMissing && onStartCopy != null
        SettingLine(
            "🗂", pw.copyTitle, pw.copyShort,
            onClick = if (canStart) {
                { open = !open }
            } else {
                null
            },
            pill = if (state.copyMissing) pw.copyOff else pw.copyOn,
            pillTone = if (state.copyMissing) PillTone.Waiting else PillTone.Good,
            onHelp = { onHelp(HelpTopic.Copy) }, open = open,
        )
        // Отключённое устройство могло унести ключ копии (М5) — сменить его фразой, сразу.
        if (state.copyRotationDue && onRotateCopy != null) RotateCopy(onRotateCopy, state.trusting)
        // Копии ещё нет (Р44) — завести её фразой.
        if (open && canStart) RotateCopy(onStartCopy!!, state.trusting, start = true)
        NoticeUnder(state, TrustPanel.Copy)
    }
    if (onRequestKey != null) {
        KeyRequest(onRequestKey, keyNotice, keySending)
    } else if (keyNotice != null) {
        Secondary(keyNotice, Modifier.padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2))
    }
}

/**
 * «Аккаунт» — красный, последним (заказчик 2026-10-07): заявки спора, пока идут, — первыми;
 * дальше смена номера, перерегистрация, запрет «Начать заново без фразы». Нажатие — красная
 * плашка с подтверждением, и только после неё — прежние шаги с фразой и кодом.
 */
@Composable
private fun AccountGroup(
    state: DevicesState,
    onCancelNewIdentity: ((String) -> Unit)?,
    onSendBanCode: (() -> Unit)?,
    onBanStartAnew: ((String, String) -> Unit)?,
    rereg: ReregView?,
    onStartRereg: ((String) -> Unit)?,
    onSendReregCode: (() -> Unit)?,
    onRereg: ((Boolean, String, String?, String) -> Unit)?,
    phoneChange: PhoneChangeView?,
    onSendPhoneCode: ((Boolean) -> Unit)?,
    onPhoneChange: ((String?, String, String) -> Unit)?,
    onHelp: (HelpTopic) -> Unit,
) {
    val pw = Tima.words.pin
    val words = Tima.words.auth
    val reregGoing = rereg != null && rereg.text != null && onSendReregCode != null && onRereg != null
    val replaced = state.replaced && rereg?.text == null && onCancelNewIdentity != null
    val phoneGoing = phoneChange?.text != null && onSendPhoneCode != null && onPhoneChange != null
    val phoneStart = phoneChange != null && phoneChange.text == null && phoneChange.canStart && onSendPhoneCode != null && onPhoneChange != null
    val reregStart = rereg?.canStart == true && onStartRereg != null
    val banned = state.startAnewBanned
    val ban = banned != null && onSendBanCode != null && onBanStartAnew != null
    if (!reregGoing && !replaced && !phoneGoing && !phoneStart && !reregStart && !ban) return

    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionTitle(pw.groupAccount, Modifier.weight(1f), color = Tima.colors.alarm)
        if (reregGoing || replaced || phoneGoing) {
            Box(Modifier.padding(end = TimaSpacing.about4)) { HelpMark { onHelp(HelpTopic.Disputes) } }
        }
    }
    // Заявки спора — первыми, пока идут: у них сроки.
    if (reregGoing) ReregPanel(rereg!!, codeSent = state.reregCode != null, onSendReregCode!!, onRereg!!, state.trusting)
    NoticeUnder(state, TrustPanel.Rereg)
    if (replaced) CancelNewIdentity(onCancelNewIdentity!!, state.trusting)
    NoticeUnder(state, TrustPanel.CancelIdentity)
    if (phoneGoing) PhoneChangePanel(phoneChange!!, codeSent = state.phoneCode != null, onSendPhoneCode!!, onPhoneChange!!, state.trusting)

    if (phoneStart) {
        Asked(
            glyph = "📲", title = words.phoneChangeTitle, about = pw.phoneShort, topic = HelpTopic.PhoneChange, onHelp = onHelp,
            ask = pw.phoneAsk, askText = pw.phoneAskText, action = pw.next,
        ) {
            PhoneChangePanel(phoneChange!!, codeSent = state.phoneCode != null, onSendPhoneCode!!, onPhoneChange!!, state.trusting, bare = true)
        }
    }
    NoticeUnder(state, TrustPanel.Phone)
    if (reregStart) {
        Asked(
            glyph = "⚠", title = words.reregTitle, about = pw.reregShort, topic = HelpTopic.Rereg, onHelp = onHelp,
            ask = pw.reregAsk, askText = pw.reregAskText, action = words.reregStart,
        ) {
            StartRereg(onStartRereg!!, state.trusting, bare = true)
        }
    }
    NoticeUnder(state, TrustPanel.StartRereg)
    if (ban) {
        if (banned == true) {
            SettingLine("⛔", words.bannedTitle, words.bannedAbout, onClick = null, onHelp = { onHelp(HelpTopic.Ban) })
        } else {
            Asked(
                glyph = "⛔", title = words.banTitle, about = pw.banShort, topic = HelpTopic.Ban, onHelp = onHelp,
                ask = pw.banAsk, askText = pw.banAskText, action = pw.next,
            ) {
                StartAnewBan(false, codeSent = state.banCode != null, onSendBanCode!!, onBanStartAnew!!, state.trusting, bare = true)
            }
        }
    }
    NoticeUnder(state, TrustPanel.Ban)
}

/**
 * Пункт «Аккаунта»: строка → красная плашка с подтверждением → прежний шаг ([then]).
 * «Отмена» сворачивает всё обратно.
 */
@Composable
private fun Asked(
    glyph: String,
    title: String,
    about: String,
    topic: HelpTopic,
    onHelp: (HelpTopic) -> Unit,
    ask: String,
    askText: String,
    action: String,
    then: @Composable () -> Unit,
) {
    var step by rememberSaveable { mutableStateOf(0) }
    SettingLine(glyph, title, about, onClick = { step = if (step == 0) 1 else 0 }, danger = true, onHelp = { onHelp(topic) }, open = step != 0)
    when (step) {
        1 -> ConfirmPlate(
            title = ask,
            text = askText,
            cancel = Tima.words.pin.cancel,
            action = action,
            danger = true,
            onCancel = { step = 0 },
            onAction = { step = 2 },
        )
        2 -> then()
    }
}

/**
 * Вопрос перед отключением.
 *
 * Занимает весь экран, а не всплывает над списком: отозванное устройство обратно не
 * вернуть, и решение должно выглядеть решением.
 */
@Composable
private fun Question(name: String, onConfirm: () -> Unit, onChangedMind: () -> Unit) = Column(
    modifier = Modifier.fillMaxSize().padding(TimaSpacing.about4),
    verticalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
) {
    val words = Tima.words.auth
    Caption(words.disconnectDevice, fontSize = TimaType.sz3, weight = FontWeight.ExtraBold)
    Secondary(name.ifEmpty { words.nameless })
    Secondary(words.disconnectAbout)

    Button(
        label = words.disconnect,
        onClick = onConfirm,
        kind = ButtonKind.Dangerous,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        label = words.keep,
        onClick = onChangedMind,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * «Ключ копии контактов не получен» и «Запросить ключ» (заказчик 2026-09-30).
 *
 * Фраза набирается здесь же и никуда не записывается: слова уходят в подпись просьбы и
 * дальше не живут. Поле стирается после отправки.
 */
/** «С вашего номера начали заново» — отменить фразой (ДУ6). */
@Composable
private fun CancelNewIdentity(onCancel: (String) -> Unit, busy: Boolean) = Column(
    modifier = Modifier.fillMaxWidth().padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2),
    verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
) {
    val words = Tima.words.auth
    var open by rememberSaveable { mutableStateOf(false) }
    var phrase by remember { mutableStateOf("") }
    Caption(words.replacedTitle, weight = FontWeight.ExtraBold, color = Tima.colors.alarm)
    Secondary(words.replacedAbout)
    if (!open) {
        Button(label = words.replacedCancel, onClick = { open = true }, kind = ButtonKind.Dangerous, modifier = Modifier.fillMaxWidth())
    } else {
        Field(value = phrase, onChange = { phrase = it }, hint = words.phraseHint, phrase = true, modifier = Modifier.fillMaxWidth())
        Button(
            label = words.replacedCancelSend,
            onClick = {
                if (!busy && phrase.isNotBlank()) {
                    onCancel(phrase)
                    phrase = ""
                }
            },
            kind = ButtonKind.Dangerous,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Запрет «Начать заново» (ДУ10, Р41). Два шага: SMS на номер аккаунта, потом фраза и код.
 * Поставленный запрет не снимается — после него здесь только слова о том, что закрыто.
 */
@Composable
private fun StartAnewBan(
    banned: Boolean,
    codeSent: Boolean,
    onSendCode: () -> Unit,
    onBan: (String, String) -> Unit,
    busy: Boolean,
    /** Из строки «Аккаунта» после подтверждения: без своего заголовка, сразу к коду. */
    bare: Boolean = false,
) = Column(
    modifier = Modifier.fillMaxWidth().padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2),
    verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
) {
    val words = Tima.words.auth
    if (banned) {
        Caption(words.bannedTitle, weight = FontWeight.ExtraBold)
        Secondary(words.bannedAbout)
        return@Column
    }
    var open by rememberSaveable { mutableStateOf(bare) }
    var phrase by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    if (!bare) {
        Caption(words.banTitle, weight = FontWeight.ExtraBold)
        Secondary(words.banAbout)
    }
    when {
        !open -> Button(label = words.banTitle, onClick = { open = true }, kind = ButtonKind.Quiet, modifier = Modifier.fillMaxWidth())
        !codeSent -> Button(label = words.banSendCode, onClick = { if (!busy) onSendCode() }, modifier = Modifier.fillMaxWidth())
        else -> {
            Field(value = phrase, onChange = { phrase = it }, hint = words.phraseHint, phrase = true, modifier = Modifier.fillMaxWidth())
            Field(value = code, onChange = { code = it }, hint = words.banCodeHint, modifier = Modifier.fillMaxWidth())
            Button(
                label = words.banConfirm,
                onClick = {
                    if (!busy && phrase.isNotBlank() && code.isNotBlank()) {
                        onBan(phrase, code)
                        phrase = ""
                        code = ""
                    }
                },
                kind = ButtonKind.Dangerous,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Запуск перерегистрации (ДУ9): объяснение, поле прежней фразы, кнопка. */
@Composable
private fun StartRereg(onStart: (String) -> Unit, busy: Boolean, bare: Boolean = false) = Column(
    modifier = Modifier.fillMaxWidth().padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2),
    verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
) {
    val words = Tima.words.auth
    var open by rememberSaveable { mutableStateOf(bare) }
    var phrase by remember { mutableStateOf("") }
    if (!bare) Caption(words.reregTitle, weight = FontWeight.ExtraBold)
    Secondary(words.reregAbout)
    if (!open) {
        Button(label = words.reregTitle, onClick = { open = true }, kind = ButtonKind.Quiet, modifier = Modifier.fillMaxWidth())
        return@Column
    }
    Field(value = phrase, onChange = { phrase = it }, hint = words.reregOldPhrase, phrase = true, modifier = Modifier.fillMaxWidth())
    Button(
        label = words.reregStart,
        onClick = {
            if (!busy && phrase.isNotBlank()) {
                onStart(phrase)
                phrase = ""
            }
        },
        kind = ButtonKind.Dangerous,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Идущая перерегистрация (ДУ9): текст стороны, заявка «Аккаунт украден» и подтверждение в окне. */
@Composable
private fun ReregPanel(
    view: ReregView,
    codeSent: Boolean,
    onSendCode: () -> Unit,
    onRereg: (Boolean, String, String?, String) -> Unit,
    busy: Boolean,
) = Column(
    modifier = Modifier.fillMaxWidth().padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2),
    verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
) {
    val words = Tima.words.auth
    Caption(words.reregTitle, weight = FontWeight.ExtraBold, color = Tima.colors.alarm)
    view.text?.let { Secondary(it) }
    if (!view.canClaim && !view.canConfirm) return@Column
    var phrase by remember { mutableStateOf("") }
    var oldPhrase by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    if (!codeSent) {
        Button(
            label = if (view.canClaim) words.reregClaim else words.reregConfirm,
            onClick = { if (!busy) onSendCode() },
            kind = if (view.canClaim) ButtonKind.Dangerous else ButtonKind.Action,
            modifier = Modifier.fillMaxWidth(),
        )
        return@Column
    }
    if (view.twoPhrases) {
        Field(value = oldPhrase, onChange = { oldPhrase = it }, hint = words.reregOldPhrase, phrase = true, modifier = Modifier.fillMaxWidth())
        Field(value = phrase, onChange = { phrase = it }, hint = words.reregNewPhrase, phrase = true, modifier = Modifier.fillMaxWidth())
    } else {
        Field(value = phrase, onChange = { phrase = it }, hint = words.phraseHint, phrase = true, modifier = Modifier.fillMaxWidth())
    }
    Field(value = code, onChange = { code = it }, hint = words.banCodeHint, modifier = Modifier.fillMaxWidth())
    Button(
        label = if (view.canClaim) words.reregClaim else words.reregConfirm,
        onClick = {
            val ready = phrase.isNotBlank() && code.isNotBlank() && (!view.twoPhrases || oldPhrase.isNotBlank())
            if (!busy && ready) {
                onRereg(view.canClaim, phrase, oldPhrase.takeIf { view.twoPhrases }, code)
                phrase = ""
                oldPhrase = ""
                code = ""
            }
        },
        kind = if (view.canClaim) ButtonKind.Dangerous else ButtonKind.Action,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Смена номера (ДУ9): без заявки — номер, код на прежний номер, фраза; с заявкой — её состояние,
 * а в окне — код на новый номер и фраза той же личности.
 */
@Composable
private fun PhoneChangePanel(
    view: PhoneChangeView,
    codeSent: Boolean,
    onSendCode: (Boolean) -> Unit,
    onSubmit: (String?, String, String) -> Unit,
    busy: Boolean,
    /** Из строки «Аккаунта» после подтверждения: без своего заголовка, сразу к номеру. */
    bare: Boolean = false,
) = Column(
    modifier = Modifier.fillMaxWidth().padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2),
    verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
) {
    val words = Tima.words.auth
    var open by rememberSaveable { mutableStateOf(bare) }
    var number by remember { mutableStateOf("") }
    var phrase by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    if (!bare) Caption(words.phoneChangeTitle, weight = FontWeight.ExtraBold)
    if (view.text != null) {
        Secondary(view.text)
        if (!view.canConfirm) return@Column
        if (!codeSent) {
            Button(label = words.phoneChangeSendNew, onClick = { if (!busy) onSendCode(true) }, kind = ButtonKind.Action, modifier = Modifier.fillMaxWidth())
            return@Column
        }
        Field(value = phrase, onChange = { phrase = it }, hint = words.phraseHint, phrase = true, modifier = Modifier.fillMaxWidth())
        Field(value = code, onChange = { code = it }, hint = words.banCodeHint, modifier = Modifier.fillMaxWidth())
        Button(
            label = words.phoneChangeConfirm,
            onClick = {
                if (!busy && phrase.isNotBlank() && code.isNotBlank()) {
                    onSubmit(null, phrase, code)
                    phrase = ""
                    code = ""
                }
            },
            kind = ButtonKind.Action,
            modifier = Modifier.fillMaxWidth(),
        )
        return@Column
    }
    if (!bare) Secondary(words.phoneChangeAbout)
    if (!open) {
        Button(label = words.phoneChangeTitle, onClick = { open = true }, kind = ButtonKind.Quiet, modifier = Modifier.fillMaxWidth())
        return@Column
    }
    Field(value = number, onChange = { number = it }, hint = words.phoneChangeNewHint, modifier = Modifier.fillMaxWidth())
    if (!codeSent) {
        Button(label = words.phoneChangeSendOld, onClick = { if (!busy) onSendCode(false) }, kind = ButtonKind.Quiet, modifier = Modifier.fillMaxWidth())
        return@Column
    }
    Field(value = phrase, onChange = { phrase = it }, hint = words.phraseHint, phrase = true, modifier = Modifier.fillMaxWidth())
    Field(value = code, onChange = { code = it }, hint = words.banCodeHint, modifier = Modifier.fillMaxWidth())
    Button(
        label = words.phoneChangeStart,
        onClick = {
            if (!busy && number.isNotBlank() && phrase.isNotBlank() && code.isNotBlank()) {
                onSubmit(number, phrase, code)
                phrase = ""
                code = ""
            }
        },
        kind = ButtonKind.Dangerous,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Сменить ключ копии ключей (модель Matrix, М5): поле фразы, кнопка. */
@Composable
private fun RotateCopy(onRotate: (String) -> Unit, busy: Boolean, start: Boolean = false) = Column(
    modifier = Modifier.fillMaxWidth().padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2),
    verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
) {
    val words = Tima.words.auth
    var phrase by remember { mutableStateOf("") }
    if (start) {
        Caption(words.copyStartTitle, weight = FontWeight.ExtraBold)
        Secondary(words.copyStartAbout)
    } else {
        Caption(words.copyRotateTitle, weight = FontWeight.ExtraBold, color = Tima.colors.alarm)
        Secondary(words.copyRotateAbout)
    }
    Field(value = phrase, onChange = { phrase = it }, hint = words.phraseHint, phrase = true, modifier = Modifier.fillMaxWidth())
    Button(
        label = if (start) words.copyStartSend else words.copyRotateSend,
        onClick = {
            if (!busy && phrase.isNotBlank()) {
                onRotate(phrase)
                phrase = ""
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Подтвердить это устройство фразой (ДУ5). Устроено как просьба ключа: поле, кнопка. */
@Composable
private fun TrustByPhrase(onConfirm: (String) -> Unit, busy: Boolean) = Column(
    modifier = Modifier.fillMaxWidth().padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2),
    verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
) {
    val words = Tima.words.auth
    var open by rememberSaveable { mutableStateOf(false) }
    var phrase by remember { mutableStateOf("") }
    Caption(words.confirmWithPhrase, weight = FontWeight.ExtraBold)
    Secondary(words.confirmWithPhraseAbout)
    if (!open) {
        Button(label = words.confirmWithPhrase, onClick = { open = true }, modifier = Modifier.fillMaxWidth())
    } else {
        Field(value = phrase, onChange = { phrase = it }, hint = words.phraseHint, phrase = true, modifier = Modifier.fillMaxWidth())
        Button(
            label = words.confirmWithPhraseSend,
            onClick = {
                if (!busy && phrase.isNotBlank()) {
                    onConfirm(phrase)
                    phrase = ""
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun KeyRequest(onRequest: (String) -> Unit, notice: String?, sending: Boolean) = Column(
    modifier = Modifier.fillMaxWidth().padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2),
    verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
) {
    val words = Tima.words.auth
    var open by rememberSaveable { mutableStateOf(false) }
    var phrase by remember { mutableStateOf("") }
    Caption(words.keyMissingTitle, weight = FontWeight.ExtraBold)
    Secondary(words.keyMissingAbout)
    if (!open) {
        Button(label = words.requestKey, onClick = { open = true }, modifier = Modifier.fillMaxWidth())
    } else {
        Field(
            value = phrase,
            onChange = { phrase = it },
            hint = words.phraseHint, phrase = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            label = if (sending) words.requestKeySending else words.requestKeySend,
            onClick = {
                if (!sending && phrase.isNotBlank()) {
                    onRequest(phrase)
                    phrase = ""
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    notice?.let { Secondary(it) }
}

/**
 * Ответ под той панелью, что его вызвала (отчёт DGAR, 2026-10-06). Одна строка под всеми
 * панелями уходила за край экрана: после «Подтвердить фразой» человек не видел ни «Фраза не
 * та», ни «Готово» — «ноль реакции».
 */
@Composable
private fun NoticeUnder(state: DevicesState, panel: TrustPanel) {
    if (state.noticeAt != panel) return
    TrustAnswer(state)
}

/**
 * Ответ панели — общей плашкой ([io.tima.core.ui.Answer], вариант 01 проб). Пока ждём
 * сервера — «Подождите…»: нажатие без видимого отклика читается как «ничего не произошло».
 */
@Composable
private fun TrustAnswer(state: DevicesState) {
    val modifier = Modifier.padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2)
    val text = state.trustNotice
    when {
        state.trusting -> io.tima.core.ui.Answer(null, io.tima.core.ui.AnswerTone.Waiting, modifier)
        text != null -> io.tima.core.ui.Answer(text, toneOf(text), modifier)
    }
}

/**
 * Успех или беда — по тексту: ответы панелей собирает [DevicesStore] из словаря, и успешных среди
 * них немного. Код стенда — не итог, а сведения.
 */
@Composable
private fun toneOf(text: String): io.tima.core.ui.AnswerTone {
    val w = Tima.words.auth
    val done = setOf(w.trustDone, w.copyStarted, w.copyRotated, w.banDone, w.replacedCancelled, w.reregClaimed, w.reregConfirmed)
    return when {
        text in done -> io.tima.core.ui.AnswerTone.Done
        text.startsWith(w.standSentCode("").trimEnd()) -> io.tima.core.ui.AnswerTone.Info
        else -> io.tima.core.ui.AnswerTone.Trouble
    }
}
