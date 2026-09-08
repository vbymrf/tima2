package io.tima.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
 * Заведение виртуального аккаунта — ПЛАН-КОНТАКТОВ.md, Д11.
 *
 * **Три шага в одном экране, а не три экрана.** Человек заводит одну вещь, и «назад» ему
 * нужно только на шаг, а не в дерево. Шаги при этом разделены по существу: ник проверяется
 * до подписи, фраза владельца спрашивается только когда ник принят, слова показываются
 * только когда аккаунт уже есть.
 *
 * **Про анонимность здесь сказано честно, и это не оговорка мелким шрифтом.** Виртуальный
 * аккаунт прячет связь с основным от собеседников, а не от нас: привязка лежит на сервере
 * открыто, иначе он не смог бы её проверять (ПЛАН-КОНТАКТОВ.md, §4). Обещать обратное
 * нельзя ни здесь, ни в описании приложения.
 */
@Composable
fun NewVirtualScreen(
    state: NewVirtualState,
    onNickname: (String) -> Unit,
    onNext: () -> Unit,
    onPhrase: (String) -> Unit,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
    /** «Записал»: слова показаны, дальше — вход в заведённый аккаунт. */
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Tima.colors
    Column(modifier.fillMaxSize().background(colors.surface)) {
        SubwindowHeader(title = Tima.words.auth.virtualAccount, onBack = onBack)

        Box(
            modifier = Modifier.fillMaxSize().padding(TimaSpacing.about5),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(TimaSpacing.about4),
            ) {
                when (state.step) {
                    NewVirtualStep.Nickname -> Nickname(state, onNickname, onNext)
                    NewVirtualStep.Phrase -> Phrase(state, onPhrase, onConfirm)
                    NewVirtualStep.Words -> Words(state, onDone)
                }
            }
        }
    }
}

/**
 * Шаг первый: ник.
 *
 * У виртуального аккаунта ник **обязателен**, и это не строгость: телефона у него нет, и
 * найти его больше не по чему.
 */
@Composable
private fun Nickname(state: NewVirtualState, onNickname: (String) -> Unit, onNext: () -> Unit) {
    val words = Tima.words.auth
    Caption(words.newNickname, fontSize = TimaType.sz2, weight = FontWeight.ExtraBold)
    Secondary(
        words.newNicknameAbout,
    )

    Field(value = state.nickname, onChange = onNickname, hint = "petr_smirnov")
    state.aboutNick(words)?.let { Secondary(it) }
    state.trouble?.let { Trouble(it) }

    Button(
        label = words.further,
        onClick = { if (state.canGoOn) onNext() },
        kind = if (state.canGoOn) ButtonKind.Action else ButtonKind.Quiet,
        modifier = Modifier.fillMaxWidth(),
    )

    // Сказано до того, как человек начал: узнать про предел после ввода фразы обиднее.
    Tertiary(
        words.fiveAtMost,
    )
    // Обещать анонимность от нас самих виртуальный аккаунт не может — см. пояснение
    // к экрану. Лучше сказать это здесь, чем дать человеку узнать это самому.
    Tertiary(
        words.linkNotHiddenFromUs,
    )
}

/**
 * Шаг второй: фраза владельца.
 *
 * **Спрашивается она не для порядка.** Кода из SMS для аккаунта без телефона не будет
 * никогда, и заверить создание нечем, кроме подписи ключом личности — а он выводится из
 * двенадцати слов и на устройстве не лежит (ADR-0010). Опирайся создание на один токен
 * устройства, укравший телефон заводил бы аккаунты от чужого имени.
 */
@Composable
private fun Phrase(state: NewVirtualState, onPhrase: (String) -> Unit, onConfirm: () -> Unit) {
    val words = Tima.words.auth
    Caption(words.yourSecretPhrase, fontSize = TimaType.sz2, weight = FontWeight.ExtraBold)
    Secondary(
        words.yourSecretPhraseAbout,
    )

    Field(value = state.phrase, onChange = onPhrase, hint = words.phraseHint)
    state.trouble?.let { Trouble(it) }

    Button(
        label = if (state.working) words.creating else words.createAccount(state.nickname),
        onClick = { if (!state.working) onConfirm() },
        kind = if (state.working) ButtonKind.Quiet else ButtonKind.Action,
        modifier = Modifier.fillMaxWidth(),
    )

    Tertiary(words.wordsGoNowhere)
}

/**
 * Шаг третий: фраза нового аккаунта — **показывается один раз**.
 *
 * Второго раза не будет ни у нас, ни на сервере: из слов выводится ключ, а сами они не
 * хранятся нигде. Поэтому здесь нет ни «пропустить», ни «потом» — только «Записал».
 */
@Composable
private fun Words(state: NewVirtualState, onDone: () -> Unit) {
    val words = Tima.words.auth
    Caption(words.phraseOf(state.nickname), fontSize = TimaType.sz2, weight = FontWeight.ExtraBold)
    Secondary(
        words.virtualPhraseSaved,
    )

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
                        // Номера — по той же причине, что и на входе: фраза
                        // восстанавливается по порядку, без них человек собьётся.
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

    Button(label = words.wroteDown, onClick = onDone, modifier = Modifier.fillMaxWidth())

    Tertiary(
        words.virtualEntryFromYourNumber,
    )
}
