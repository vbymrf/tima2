package io.tima.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
     * Выйти из аккаунта на этом устройстве (ПЛАН-ВЫХОДА-ИЗ-АККАУНТА.md, А4). `null` — кнопки
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
) = Column(modifier.fillMaxSize().background(Tima.colors.surface)) {
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

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(TimaSpacing.about4),
        verticalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
    ) {
        items(state.devices, key = { it.deviceId }) { device ->
            Line(device, onAsk)
        }
    }
}

@Composable
private fun Line(device: AccountDevice, onAsk: (String) -> Unit) {
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
        }
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
            hint = words.phraseHint,
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
