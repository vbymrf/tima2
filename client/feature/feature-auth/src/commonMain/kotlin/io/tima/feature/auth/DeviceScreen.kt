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
import io.tima.domain.account.AccountDevice

/**
 * Свои устройства: чем читаю и что можно отключить. Вкладка подокна «Настройки».
 *
 * **Своё устройство помечено.** Строки похожи — «Телефон» и «Телефон», — и без пометки
 * человек однажды отключит то, с которого смотрит. Отключение при этом спрашивает
 * подтверждение и называет цену: вернуть отозванное нельзя, на нём придётся заводиться
 * заново.
 */
@Composable
fun DeviceScreen(
    state: DevicesState,
    onAsk: (String) -> Unit,
    onConfirm: () -> Unit,
    onChangedMind: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Номер сборки. Здесь он нужен уже после входа: экран входа человек видит один раз,
     * а «какая версия стоит» спрашивают, когда что-то пошло не так, — то есть изнутри
     * приложения. Пусто — версия не передана (проверки, снимки), строки нет.
     */
    buildVersion: String = "",
    /**
     * Выйти из аккаунта на этом устройстве (ПЛАН-(А)-ВЫХОДА-ИЗ-АККАУНТА.md, А4). `null` — кнопки
     * нет (проверки, снимки).
     */
    onSignOut: (() -> Unit)? = null,
    /** Список не пришёл — запросить снова (А7). */
    onRetry: (() -> Unit)? = null,
    /**
     * Сканировать код подключения нового устройства (заказчик 2026-09-30, 1б). `null` —
     * кнопки нет: на ПК подтверждать подключение сервер не даёт — только телефону
     * (`not_a_phone`), и сканер там незачем.
     */
    onScan: (() -> Unit)? = null,
    /**
     * Ключа служебной группы нет — копия контактов не придёт (заказчик 2026-09-30).
     * `null` — кнопки нет: ключ есть или просить нечем.
     */
    onRequestKey: ((String) -> Unit)? = null,
    /** Что сказать под кнопкой о просьбе; `null` — ещё не просили. */
    keyNotice: String? = null,
    /** Просьба в пути — кнопку не нажать второй раз. */
    keySending: Boolean = false,
    /** Подтвердить это устройство фразой (ДУ5); `null` — действия нет. */
    onConfirmWithPhrase: ((String) -> Unit)? = null,
    /** Заверить другое своё устройство ключом этого телефона; `null` — этот телефон ключа не держит. */
    onCertify: ((String) -> Unit)? = null,
    /** Отменить новую личность фразой (ДУ6); `null` — отменять нечем. */
    onCancelNewIdentity: ((String) -> Unit)? = null,
    /** Показать код заверения этого устройства (Р32); `null` — кнопки нет. */
    onShowCertifyCode: (() -> Unit)? = null,
    /** Запрет «Начать заново» (ДУ10): отправить SMS; `null` — панели нет. */
    onSendBanCode: (() -> Unit)? = null,
    /** Запрет «Начать заново»: фраза и код из SMS. */
    onBanStartAnew: ((String, String) -> Unit)? = null,
    /** Сменить ключ копии фразой — после отключения своего устройства (М5). */
    onRotateCopy: ((String) -> Unit)? = null,
    /** Завести копию ключей фразой (Р44). */
    onStartCopy: ((String) -> Unit)? = null,
    /** Перерегистрация (ДУ9): что показать; `null` — панели нет. */
    rereg: ReregView? = null,
    /** Начать перерегистрацию прежней фразой. */
    onStartRereg: ((String) -> Unit)? = null,
    /** Код из SMS для заявки и подтверждения. */
    onSendReregCode: (() -> Unit)? = null,
    /** Заявка (`true`) или подтверждение: фраза, прежняя фраза (у Н), код. */
    onRereg: ((Boolean, String, String?, String) -> Unit)? = null,
    /** Смена номера (ДУ9): что показать; `null` — панели нет. */
    phoneChange: PhoneChangeView? = null,
    /** Код из SMS: `false` — на прежний номер (заявка), `true` — на новый (подтверждение). */
    onSendPhoneCode: ((Boolean) -> Unit)? = null,
    /** Заявка (новый номер задан) или подтверждение: номер, фраза, код. */
    onPhoneChange: ((String?, String, String) -> Unit)? = null,
) = Column(
    // Экран прокручивается целиком (живая проверка 2026-10-06): панели доверия, копии, запрета,
    // смены номера и перерегистрации не помещались на телефон, и сообщение под ними — код стенда,
    // отказ — уходило за край, а список устройств не был виден вовсе.
    modifier.fillMaxSize().background(Tima.colors.surface).verticalScroll(androidx.compose.foundation.rememberScrollState()),
) {
    val words = Tima.words.auth
    var signingOut by rememberSaveable { mutableStateOf(false) }
    // Фон заливается явно. Экран без своего фона показывает то, что под ним, — на телефоне
    // это выглядело как тёмный экран внутри светлой темы, и найдено это было только глазами
    // на устройстве: снимки видят компонент, а не окно.
    // ── ШАПКИ ЗДЕСЬ БОЛЬШЕ НЕТ ───────────────────────────────────────────────
    //
    // Экран стал вкладкой подокна «Настройки», а шапку с «назад» рисует подокно:
    // одна на все вкладки. Пока экран открывался сам по себе, шапка жила в нём, и
    // это было верно ровно до второй вкладки — две шапки одна под другой.

    // Сразу сверху, а не в конце списка: у экрана есть ранние выходы — вопрос об
    // отключении и пустой список, — и строка, поставленная после них, в этих состояниях
    // не показалась бы вовсе. А спрашивают версию как раз тогда, когда что-то не так.
    if (buildVersion.isNotBlank()) {
        Tertiary(
            Tima.words.auth.build(buildVersion),
            Modifier.padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2),
        )
    }

    state.trouble?.let {
        Column(Modifier.padding(TimaSpacing.about4)) { Trouble(it) }
    }

    // Ключ копии — до ранних выходов: его просят и тогда, когда список устройств не пришёл.
    if (onRequestKey != null && !signingOut) {
        KeyRequest(onRequestKey, keyNotice, keySending)
    } else if (keyNotice != null) {
        Secondary(keyNotice, Modifier.padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2))
    }

    // Перерегистрация (ДУ9) — первым делом: спор за аккаунт важнее всего на экране.
    if (rereg != null && rereg.text != null && onSendReregCode != null && onRereg != null && !signingOut) {
        ReregPanel(rereg, codeSent = state.reregCode != null, onSendReregCode, onRereg, state.trusting)
    }
    // С номера начали заново (ДУ6) — первым делом: это важнее всего остального на экране.
    // Во время перерегистрации отмены нет: оспаривается только заявкой (ДУ9).
    if (state.replaced && rereg?.text == null && onCancelNewIdentity != null && !signingOut) {
        CancelNewIdentity(onCancelNewIdentity, state.trusting)
    }

    // Доверие к этому устройству (ДУ5): не заверено — предложить фразу. До ранних выходов:
    // подтверждать себя можно и тогда, когда список не пришёл целиком.
    val self = state.devices.firstOrNull { it.current }
    if (onConfirmWithPhrase != null && !signingOut && self != null && !self.certified) {
        TrustByPhrase(onConfirmWithPhrase, state.trusting)
    }
    // Или по QR своим телефоном (Р32): единственный путь заверить уже подключённое без фразы.
    if (onShowCertifyCode != null && !signingOut && self != null && !self.certified) {
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
    // Отключённое устройство могло унести ключ копии (М5) — сменить его фразой, первым делом.
    if (state.copyRotationDue && onRotateCopy != null && !signingOut) {
        RotateCopy(onRotateCopy, state.trusting)
    }
    // Копии ещё нет (Р44) — завести её фразой: это устройство фразу больше не вводит.
    if (state.copyMissing && onStartCopy != null && !signingOut) {
        RotateCopy(onStartCopy, state.trusting, start = true)
    }
    // Запрет «Начать заново» (ДУ10, Р41): до запрета — кнопка, после — что закрыто навсегда.
    val banned = state.startAnewBanned
    if (banned != null && onSendBanCode != null && onBanStartAnew != null && !signingOut) {
        StartAnewBan(banned, codeSent = state.banCode != null, onSendBanCode, onBanStartAnew, state.trusting)
    }
    // Смена номера (ДУ9): заявка, её состояние и подтверждение в окне.
    if (phoneChange != null && onSendPhoneCode != null && onPhoneChange != null && !signingOut &&
        (phoneChange.text != null || phoneChange.canStart)
    ) {
        PhoneChangePanel(phoneChange, codeSent = state.phoneCode != null, onSendPhoneCode, onPhoneChange, state.trusting)
    }
    // Запустить перерегистрацию (ДУ9, Р34) — прежней фразой; дальше вход тем же номером.
    if (rereg?.canStart == true && onStartRereg != null && !signingOut) {
        StartRereg(onStartRereg, state.trusting)
    }
    state.trustNotice?.let {
        Secondary(it, Modifier.padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2))
    }

    // Сканер — тоже до ранних выходов: подключить новое устройство можно и тогда, когда
    // список не пришёл.
    if (onScan != null && !signingOut) {
        Button(
            label = words.scanCode,
            onClick = onScan,
            modifier = Modifier.fillMaxWidth().padding(horizontal = TimaSpacing.about4),
        )
        Tertiary(
            words.scanCodeAbout,
            Modifier.padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about1),
        )
    }

    // Выход — до ранних выходов экрана: он нужен и тогда, когда список не пришёл. Так было
    // на ПК 2026-09-30 — «Смотрим…» без конца, а выйти и войти заново было неоткуда.
    if (onSignOut != null) {
        if (signingOut) {
            SignOutQuestion(
                last = state.devices.size == 1,
                onConfirm = { signingOut = false; onSignOut() },
                onChangedMind = { signingOut = false },
            )
            return@Column
        }
        Button(
            label = words.signOut,
            onClick = { signingOut = true },
            kind = ButtonKind.Quiet,
            modifier = Modifier.fillMaxWidth().padding(horizontal = TimaSpacing.about4),
        )
    }

    val ask = state.ask
    if (ask != null) {
        Question(
            name = state.devices.firstOrNull { it.deviceId == ask }?.name.orEmpty(),
            onConfirm = onConfirm,
            onChangedMind = onChangedMind,
        )
        return@Column
    }

    if (state.devices.isEmpty()) {
        // **Пустой список и неудавшийся запрос — разные вещи, и путать их дороже всего.**
        //
        // Найдено 2026-08-26 по жалобе «Устройств нет — такого не бывает на ПК». Так и
        // есть: сервер пустой список отдать не может. `requireActiveDevice` пропускает
        // запрос, только убедившись, что строка устройства существует и не отозвана, а
        // `listMyDevices` выбирает ровно такие строки — своё устройство обязано быть в
        // ответе. Значит пусто здесь означает не «нет устройств», а «списка нет».
        //
        // Экран же рисовал беду баннером и тут же добавлял «Устройств нет»: два
        // сообщения об одном, причём второе — неправда, и именно оно бросается в глаза.
        EmptyArea(
            title = when {
                state.expect -> words.watching
                state.trouble != null -> words.listNotCame
                else -> words.noDevices
            },
            explanation = when {
                state.expect -> null
                // Причина уже сказана баннером выше; повторять её здесь — шуметь.
                state.trouble != null -> words.reasonAbove
                // Сюда попасть можно только при 200 с пустым списком, а такого ответа
                // сервер не строит. Остаётся клиент: не тот адрес, не тот разбор.
                else -> words.emptyListIsOurs
            },
        )
        if (!state.expect && onRetry != null) {
            Button(
                label = words.retryList,
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth().padding(horizontal = TimaSpacing.about4),
            )
        }
        return@Column
    }

    // Обычная колонка, а не ленивый список: экран прокручивается целиком, а устройств у
    // человека единицы.
    Column(
        modifier = Modifier.fillMaxWidth().padding(TimaSpacing.about4),
        verticalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
    ) {
        state.devices.forEach { device ->
            androidx.compose.runtime.key(device.deviceId) { Line(device, onAsk, onCertify.takeIf { !state.trusting }) }
        }
    }
}

@Composable
private fun Line(device: AccountDevice, onAsk: (String) -> Unit, onCertify: ((String) -> Unit)? = null) {
    val words = Tima.words.auth
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Caption(
                text = device.name.ifEmpty { words.nameless },
                weight = FontWeight.Bold,
                lineOne = true,
            )
            // Пометка своего устройства и дата — в одной строке: это про одно и то же,
            // «что это за железка».
            Tertiary(
                text = listOfNotNull(
                    if (device.current) words.thisDevice else null,
                    device.createdAt,
                ).joinToString(" · ").ifEmpty { "—" },
                lineOne = true,
            )
            // Доверие (ДУ5): незаверенное — красным, иначе его не отличить от своего.
            Caption(
                text = if (device.certified) words.deviceCertified else words.deviceUncertified,
                fontSize = TimaType.sz6,
                color = if (device.certified) Tima.colors.text3 else Tima.colors.alarm,
                maxLines = 2,
            )
        }
        // «Заверить» по строке списка убрано (Р32): список приходит от сервера, и по нему
        // заверялось бы и устройство вора. Заверяют по QR — код показывает само устройство.
        // Своё устройство отключается не отсюда: «выйти» — это другое действие с другими
        // последствиями, и оно живёт в настройках аккаунта.
        if (!device.current) {
            Button(
                label = words.disconnect,
                onClick = { onAsk(device.deviceId) },
                kind = ButtonKind.Quiet,
            )
        }
    }
}

/**
 * Вопрос перед отключением.
 *
 * Занимает весь экран, а не всплывает над списком: отозванное устройство обратно не
 * вернуть, и решение должно выглядеть решением.
 */
/** Вопрос перед выходом из аккаунта: что будет с аккаунтом и как вернуться (А4). */
@Composable
private fun SignOutQuestion(last: Boolean, onConfirm: () -> Unit, onChangedMind: () -> Unit) = Column(
    modifier = Modifier.fillMaxWidth().padding(TimaSpacing.about4),
    verticalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
) {
    val words = Tima.words.auth
    Caption(words.signOut, fontSize = TimaType.sz3, weight = FontWeight.ExtraBold)
    Secondary(words.signOutAbout)
    if (last) Secondary(words.signOutLast)
    Button(label = words.signOutYes, onClick = onConfirm, kind = ButtonKind.Dangerous, modifier = Modifier.fillMaxWidth())
    Button(label = words.keep, onClick = onChangedMind, modifier = Modifier.fillMaxWidth())
}

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
    var open by rememberSaveable { mutableStateOf(false) }
    var phrase by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    Caption(words.banTitle, weight = FontWeight.ExtraBold)
    Secondary(words.banAbout)
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
private fun StartRereg(onStart: (String) -> Unit, busy: Boolean) = Column(
    modifier = Modifier.fillMaxWidth().padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2),
    verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
) {
    val words = Tima.words.auth
    var open by rememberSaveable { mutableStateOf(false) }
    var phrase by remember { mutableStateOf("") }
    Caption(words.reregTitle, weight = FontWeight.ExtraBold)
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
) = Column(
    modifier = Modifier.fillMaxWidth().padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2),
    verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
) {
    val words = Tima.words.auth
    var open by rememberSaveable { mutableStateOf(false) }
    var number by remember { mutableStateOf("") }
    var phrase by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    Caption(words.phoneChangeTitle, weight = FontWeight.ExtraBold)
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
    Secondary(words.phoneChangeAbout)
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
