package io.tima.feature.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.tima.core.ui.Chip
import io.tima.core.ui.Field
import io.tima.core.ui.ChipKind
import io.tima.core.words.Language
import io.tima.core.ui.ListLine
import io.tima.core.ui.Name
import io.tima.core.ui.Secondary
import io.tima.core.ui.Tertiary
import io.tima.core.ui.Trouble
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.words

/**
 * Выбор языка приложения (ПЛАН-ЯЗЫКА Я1).
 *
 * **Язык названий — свой собственный.** «English» написано по-английски, «Español» — по-
 * испански: человек, открывший список на незнакомом языке, обязан узнать свой. Перевод
 * названий языков на текущий сделал бы список нечитаемым ровно для того, кому он нужен.
 *
 * **Языки без словаря показаны и не выбираются.** Замысел виден целиком — три языка, — а
 * нажать можно то, у чего надписи уже есть. Спрятать их значило бы каждый раз объяснять,
 * будет ли вообще английский.
 *
 * **Сообщения не переводятся, и это сказано на экране.** Перевода сообщений нет вовсе
 * (решение заказчика 2026-09-08), и человек, выбирающий язык, не должен ждать, что чужие
 * реплики станут понятными.
 */
@Composable
fun LanguageScreen(
    current: String,
    onChoose: (Language) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Страна человека (ПЛАН-ЯЗЫКА Я7). `null` — настройки отбора не показываются вовсе:
     * экран остаётся выбором языка приложения.
     *
     * Простые значения, а не тип домена: оболочка знает раму, а не работу с сервером —
     * это правило модулей, и ради одного экрана его не нарушают.
     */
    country: String? = null,
    /**
     * Язык, на котором человек **пишет** (Я12) — не тот, что выбран ниже для надписей.
     * Свободный тег, а не выбор из списка: сервер держит его свободным текстом, и писать
     * по-немецки при русском интерфейсе законно.
     */
    writingLanguage: String = "",
    /** Какие языки читать, как набрано: через запятую. Пусто — язык человека. */
    readingLanguages: String = "",
    onlyMyCountry: Boolean = true,
    onlyMyLanguages: Boolean = true,
    localeTrouble: String? = null,
    onCountry: (String) -> Unit = {},
    onWritingLanguage: (String) -> Unit = {},
    onReadingLanguages: (String) -> Unit = {},
    onOnlyMyCountry: (Boolean) -> Unit = {},
    onOnlyMyLanguages: (Boolean) -> Unit = {},
) {
    val colors = Tima.colors
    val words = Tima.words
    // ── ПРОКРУТКА, И ПОЧЕМУ ОНА ЗДЕСЬ ОБЯЗАТЕЛЬНА ────────────────────────────
    //
    // Экран вырос: страна (Я7), язык письма и языки чтения (Я12), два переключателя —
    // и список языков приложения уехал за нижний край. На телефоне 720×1600 его стало
    // **не достать вовсе**: экран не прокручивался. Поймано живым прогоном 2026-09-14,
    // до него ни один тест этого не видел — снимки рисуются в заданном размере, а не в
    // телефонном.
    //
    // Своей шапки здесь нет намеренно: её рисует `SettingsScreen` — «шапка одна на
    // подокно», и заголовок в ней уже имя открытого пункта. Вторая давала две
    // одинаковые строки «Язык» с двумя кнопками «назад».
    Column(
        modifier
            .fillMaxSize()
            .background(colors.surface)
            .verticalScroll(rememberScrollState()),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(TimaSpacing.about4),
            verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
        ) {
            Tertiary(words.settings.languageAbout)
        }

        country?.let { chosenCountry ->
            Column(
                modifier = Modifier.fillMaxWidth().padding(TimaSpacing.about4),
                verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
            ) {
                localeTrouble?.let { Trouble(it) }

                // Страна — не язык, и это разные ответы: русский пишут и в Казахстане.
                // Пустая законна: «не указана» значит «видит всё», а не «ничего».
                Name(words.settings.country)
                Tertiary(words.settings.countryAbout)
                Field(
                    value = chosenCountry,
                    onChange = onCountry,
                    hint = words.settings.countryHint,
                )

                // Язык письма стоит рядом со страной, а не рядом с выбором языка
                // приложения ниже: это метаданные, которыми сервер отбирает ленты, и
                // соседство с надписями сбивало бы с толку ровно тех, у кого они разные.
                Name(words.settings.writingLanguage)
                Tertiary(words.settings.writingLanguageAbout)
                Field(
                    value = writingLanguage,
                    onChange = onWritingLanguage,
                    hint = words.settings.writingLanguageHint,
                )

                Name(words.settings.whatToShow)
                ChoiceSwitch(
                    title = words.settings.onlyMyCountry,
                    on = onlyMyCountry,
                    onChange = onOnlyMyCountry,
                )
                ChoiceSwitch(
                    title = words.settings.onlyMyLanguages,
                    on = onlyMyLanguages,
                    onChange = onOnlyMyLanguages,
                )
                // Список языков показывается, только пока отбор включён: поле, которое ни
                // на что не влияет, человек заполняет и ждёт действия.
                if (onlyMyLanguages) {
                    Name(words.settings.readingLanguages)
                    Tertiary(words.settings.readingLanguagesAbout)
                    Field(
                        value = readingLanguages,
                        onChange = onReadingLanguages,
                        hint = words.settings.readingLanguagesHint,
                    )
                }
                // Названо прямо, потому что человек ждёт обратного: отбор не касается
                // переписки и ленты друзей — друг остаётся другом, уехав и заговорив
                // на другом языке.
                Tertiary(words.settings.filterNotForChats)
            }
        }

        Name(words.settings.appLanguage, modifier = Modifier.padding(horizontal = TimaSpacing.about4))
        for (language in Language.entries) {
            ListLine(
                onClick = if (language.available) {
                    { onChoose(language) }
                } else {
                    null
                },
                middle = {
                    Column {
                        Name(language.ownName)
                        Secondary(language.tag, lineOne = true)
                    }
                },
                right = {
                    when {
                        language.tag == current -> Chip(words.settings.chosen, kind = ChipKind.Selected)
                        !language.available -> Chip(words.settings.soon, kind = ChipKind.Quiet)
                        else -> Unit
                    }
                },
            )
        }
    }
}


/** Переключатель одной строкой: название слева, состояние справа. */
@Composable
private fun ChoiceSwitch(title: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val words = Tima.words
    ListLine(
        onClick = { onChange(!on) },
        middle = { Name(title) },
        right = {
            Chip(
                if (on) words.settings.on else words.settings.off,
                kind = if (on) ChipKind.Selected else ChipKind.Quiet,
            )
        },
    )
}
