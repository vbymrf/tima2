package io.tima.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.tima.core.ui.Trouble
import io.tima.core.ui.ButtonKind
import io.tima.core.ui.Secondary
import io.tima.core.ui.QrCodeImage
import io.tima.core.ui.Button
import io.tima.core.ui.Field
import io.tima.core.ui.Caption
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.Tertiary
import io.tima.core.ui.Tima
import io.tima.core.ui.words

/**
 * Экран входа — К5.1.
 *
 * Два шага, а не пять: телефон и код. Email, пароль, десять резервных кодов и временный
 * режим описаны в `doc_UI/22-auth-registration.md`, но **на сервере их нет вовсе** —
 * измерено, и вопрос вынесен заказчику (Д1, Д2). Сделано то, что сервер умеет; это
 * подмножество любого из двух решений.
 *
 * Экран — чистый рендер [AuthState]. Все решения — в [AuthStore]: что делать с набранным
 * при отказе, когда кнопка занята, куда возвращаться при просроченном коде.
 *
 * **Плашки окна здесь нет намеренно.** Салатовая плашка отвечает на вопрос «в каком я
 * окне», а на входе этот вопрос не стоит: окно одно, и уйти из него некуда.
 */
@Composable
fun EntryScreen(
    state: AuthState,
    onNumber: (String) -> Unit,
    /** Код страны отдельным полем: см. пояснение у [Телефон]. */
    onCodeCountry: (String) -> Unit,
    onCode: (String) -> Unit,
    onRequest: () -> Unit,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onPhrase: (String) -> Unit = {},
    onEnterByPhrase: () -> Unit = {},
    onStartAnew: () -> Unit = {},
    onPhraseSaved: () -> Unit = {},
    onConnect: (() -> Unit)? = null,
    /**
     * Номер сборки — единственное место, где он виден человеку.
     *
     * Стоит именно на входе: это первый экран после установки, и проверять, обновилось
     * ли приложение, надо ДО того, как заходить в аккаунт. Пусто — версия не передана
     * (проверки, снимки), и строки просто нет.
     */
    buildVersion: String = "",
) {
    val colors = Tima.colors
    Box(
        modifier = modifier.fillMaxSize().background(colors.surface).padding(TimaSpacing.about5),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            // Ширина ограничена: поле ввода на всю ширину ПК выглядит как поле поиска, а
            // не как «введите номер». Тот же предел, что у содержимого переписки.
            modifier = Modifier.widthIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(TimaSpacing.about4),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (state) {
                is AuthState.Phone -> Phone(state, onNumber, onCodeCountry, onRequest, onConnect)
                is AuthState.Code -> Code(state, onCode, onConfirm, onBack)
                is AuthState.Phrase -> Phrase(state, onPhraseSaved)
                is AuthState.PhraseInput -> PhraseInput(state, onPhrase, onEnterByPhrase, onStartAnew, onBack)
                is AuthState.DisplayCode -> DisplayCode(state, onBack, onConnect)
                // Оба конечных состояния экран не рисует: приложение уже ушло дальше.
                // Показывать «готово» было бы лишним шагом на пути, который человек и так
                // прошёл.
                is AuthState.Done, AuthState.CreatedAlready -> Unit
            }

            // Номер сборки — мелко и последним. Он нужен не человеку в обычный день, а
            // тому, кто проверяет, доехало ли обновление: без него «поставил новую
            // версию» проверяется только на слово.
            if (buildVersion.isNotBlank()) {
                Tertiary(Tima.words.auth.build(buildVersion))
            }
        }
    }
}

@Composable
private fun Phone(
    state: AuthState.Phone,
    onNumber: (String) -> Unit,
    onCodeCountry: (String) -> Unit,
    onRequest: () -> Unit,
    onConnect: (() -> Unit)?,
) {
    val words = Tima.words.auth
    Caption(words.welcome, fontSize = TimaType.sz2, weight = FontWeight.ExtraBold)
    Secondary(words.enterPhone)

    // ── ДВА ПОЛЯ, А НЕ ОДНО ──────────────────────────────────────────────────
    //
    // Раньше поле было одно, а «+7» стоял подсказкой. Подсказка выглядит как уже
    // введённое значение: человек видел «+7», набирал номер без кода страны и получал
    // отказ сервера — при том, что на экране всё выглядело правильно.
    //
    // Код страны и номер — разные величины и для человека: код меняется раз в жизни,
    // номер набирают каждый раз. Слитое поле заставляло стирать «+7» вместе с номером.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Плюс нарисован, а не введён: в значении его нет, иначе «+7» и «7» стали бы
        // разными кодами одной страны.
        Caption("+", fontSize = TimaType.sz3, weight = FontWeight.ExtraBold)
        Box(Modifier.width(72.dp)) {
            Field(
                value = state.countryCode,
                onChange = onCodeCountry,
                hint = "7",
                numeric = true,
            )
        }
        Box(Modifier.weight(1f)) {
            Field(
                value = state.number,
                onChange = onNumber,
                hint = "999 000 00 00",
                numeric = true,
            )
        }
    }

    state.trouble?.let { Trouble(it) }

    Button(
        label = if (state.expect) words.sending else words.getCode,
        onClick = onRequest,
        modifier = Modifier.fillMaxWidth(),
    )

    // Второй путь, и он второй намеренно: у кого аккаунта ещё нет, тот идёт по номеру, и
    // таких большинство. Кнопки нет вовсе, если путь недоступен: обещать человеку то,
    // чего нет, дороже, чем не обещать.
    onConnect?.let {
        Tertiary(words.alreadyHaveAccount)
        Button(
            label = words.connectToAccount,
            onClick = it,
            kind = ButtonKind.Quiet,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Код привязки: **всё, что делает это устройство, — показывает код и ждёт**.
 *
 * Решение принимает человек на телефоне, и правильно, что здесь нет ни одной кнопки,
 * кроме «Назад»: нажимать тут нечего, и подсовывать кнопку «Подтвердить» значило бы
 * предложить подтвердить самому себе.
 *
 * Код показан и цифрами тоже. Не для переписывания руками — 258 знаков никто не наберёт, —
 * а потому что его можно скопировать и переслать себе, если камера не берёт экран.
 */
@Composable
private fun DisplayCode(
    state: AuthState.DisplayCode,
    onBack: () -> Unit,
    onNewCode: (() -> Unit)?,
) {
    val words = Tima.words.auth
    Caption(words.connectingDevice, fontSize = TimaType.sz2, weight = FontWeight.ExtraBold)
    Secondary(words.connectingDeviceAbout)

    state.trouble?.let { Trouble(it) }

    val code = state.code
    if (code == null) {
        if (state.trouble == null) Secondary(words.askingCode)
    } else {
        QrCodeImage(code, modifier = Modifier.fillMaxWidth())
    }

    // На привязанном устройстве переписки не будет: ключи прошлых сообщений оборачивались
    // на устройства, которые существовали тогда. Сказать это надо ЗАРАНЕЕ — иначе пустой
    // список человек прочтёт как потерю переписки.
    Tertiary(words.oldChatsWontMove)

    // Код умер — нужен новый, и просить его должно быть чем. Кнопка появляется только
    // тогда, когда просить есть за чем: висеть рядом с живым кодом ей незачем, а нажми
    // человек её случайно — прежний код перестанет работать, и телефон покажет отказ.
    if (state.code == null && state.trouble != null && onNewCode != null) {
        Button(
            label = words.newCode,
            onClick = onNewCode,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    Button(
        label = Tima.words.common.back,
        onClick = onBack,
        kind = ButtonKind.Quiet,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Code(
    state: AuthState.Code,
    onCode: (String) -> Unit,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
) {
    val words = Tima.words.auth
    Caption(words.confirmation, fontSize = TimaType.sz2, weight = FontWeight.ExtraBold)
    Secondary(words.codeSentTo(state.phone))

    Field(
        value = state.code,
        onChange = onCode,
        hint = "······",
        numeric = true,
        byCenter = true,
    )

    // Подсказка стенда: код приходит в ответе только там, где сервер сам его прислал.
    // Написано вслух, чтобы её не приняли за «код виден всем».
    state.standHint?.let { Tertiary(words.standSentCode(it)) }

    state.trouble?.let { Trouble(it) }

    Button(
        label = if (state.expect) words.checking else words.confirm,
        onClick = onConfirm,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        label = words.changeNumber,
        onClick = onBack,
        kind = ButtonKind.Quiet,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Секретная фраза — **показывается один раз**.
 *
 * Это единственный момент, когда человек может её записать: слова не хранятся ни у нас, ни
 * на сервере — из них выводится ключ, и второй раз показать их будет нечем. Поэтому здесь
 * нет ни «пропустить», ни «потом»: только кнопка «Записал».
 *
 * Слова набраны крупно и по три в ряд: их переписывают на бумагу, а не читают. Порядок
 * важен, и номер у каждого слова стоит именно поэтому.
 */
@Composable
private fun Phrase(state: AuthState.Phrase, onSaved: () -> Unit) {
    val words = Tima.words.auth
    Caption(words.secretPhrase, fontSize = TimaType.sz2, weight = FontWeight.ExtraBold)
    Secondary(words.secretPhraseAbout)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
    ) {
        state.words.chunked(3).forEachIndexed { row, words ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
            ) {
                words.forEachIndexed { place, word ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(Tima.colors.softAccent, CircleShape)
                            .padding(vertical = TimaSpacing.about2),
                        contentAlignment = Alignment.Center,
                    ) {
                        // Номер слова: фраза восстанавливается по порядку, и человек
                        // переписывает её строками. Без номеров он собьётся на девятом.
                        Caption(
                            text = "${row * 3 + place + 1}. $word",
                            fontSize = TimaType.sz5,
                            weight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }

    Button(label = words.wroteDown, onClick = onSaved, modifier = Modifier.fillMaxWidth())
}

/**
 * Возврат по фразе: номер занят другой личностью.
 *
 * **Это не отказ, а другой путь.** Аккаунт существует, и владение им доказывает фраза —
 * номер этого не доказывает, номера перевыпускают. Код при этом уже подтверждён и не
 * спрашивается заново: человек не виноват в том, что у него есть аккаунт.
 *
 * «Начать заново» стоит здесь же, но последним и со сказанной ценой: собеседники увидят
 * предупреждение о смене личности, а прежняя переписка не вернётся.
 */
@Composable
private fun PhraseInput(
    state: AuthState.PhraseInput,
    onPhrase: (String) -> Unit,
    onEnter: () -> Unit,
    onAnew: () -> Unit,
    onOtherNumber: () -> Unit,
) {
    val words = Tima.words.auth
    Caption(words.phraseEntry, fontSize = TimaType.sz2, weight = FontWeight.ExtraBold)
    Secondary(words.accountExistsFor(state.phone))

    Field(
        value = state.phrase,
        onChange = onPhrase,
        hint = words.phraseHint,
    )

    state.trouble?.let { Trouble(it) }

    Button(
        label = if (state.expect) words.checking else words.enter,
        onClick = onEnter,
        modifier = Modifier.fillMaxWidth(),
    )

    // Выход из тупика. Сюда попадают и по опечатке в номере, и войдя с чужого телефона;
    // без этой кнопки оставались только «вспомнить фразу» и «начать заново», а второе
    // стирает прежнюю личность — то есть опечатка стоила бы аккаунта.
    Button(
        label = words.otherNumber,
        onClick = onOtherNumber,
        modifier = Modifier.fillMaxWidth(),
    )

    Tertiary(words.noPhrase)
    Button(
        label = words.startAnew,
        onClick = onAnew,
        kind = ButtonKind.Dangerous,
        modifier = Modifier.fillMaxWidth(),
    )
}
