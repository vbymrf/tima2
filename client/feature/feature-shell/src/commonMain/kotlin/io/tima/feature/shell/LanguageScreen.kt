package io.tima.feature.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.tima.core.ui.Chip
import io.tima.core.ui.Field
import io.tima.core.ui.ChipKind
import io.tima.core.ui.Language
import io.tima.core.ui.ListLine
import io.tima.core.ui.Name
import io.tima.core.ui.Secondary
import io.tima.core.ui.SubwindowHeader
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
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Страна человека (ПЛАН-ЯЗЫКА Я7). `null` — настройки отбора не показываются вовсе:
     * экран остаётся выбором языка приложения.
     *
     * Простые значения, а не тип домена: оболочка знает раму, а не работу с сервером —
     * это правило модулей, и ради одного экрана его не нарушают.
     */
    country: String? = null,
    onlyMyCountry: Boolean = true,
    onlyMyLanguages: Boolean = true,
    localeTrouble: String? = null,
    onCountry: (String) -> Unit = {},
    onOnlyMyCountry: (Boolean) -> Unit = {},
    onOnlyMyLanguages: (Boolean) -> Unit = {},
) {
    val colors = Tima.colors
    val words = Tima.words
    Column(modifier.fillMaxSize().background(colors.surface)) {
        SubwindowHeader(title = words.language, onBack = onBack)

        Column(
            modifier = Modifier.fillMaxWidth().padding(TimaSpacing.about4),
            verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
        ) {
            Tertiary(words.languageAbout)
        }

        country?.let { chosenCountry ->
            Column(
                modifier = Modifier.fillMaxWidth().padding(TimaSpacing.about4),
                verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
            ) {
                localeTrouble?.let { Trouble(it) }

                // Страна — не язык, и это разные ответы: русский пишут и в Казахстане.
                // Пустая законна: «не указана» значит «видит всё», а не «ничего».
                Name("Страна")
                Tertiary("Ею сервер отбирает выдачу: своё, а не весь мир. Пусто — показывать всё")
                Field(
                    value = chosenCountry,
                    onChange = onCountry,
                    hint = "RU",
                )

                Name("Что показывать")
                ChoiceSwitch(
                    title = "Только моя страна",
                    on = onlyMyCountry,
                    onChange = onOnlyMyCountry,
                )
                ChoiceSwitch(
                    title = "Только мои языки",
                    on = onlyMyLanguages,
                    onChange = onOnlyMyLanguages,
                )
                // Названо прямо, потому что человек ждёт обратного: отбор не касается
                // переписки и ленты друзей — друг остаётся другом, уехав и заговорив
                // на другом языке.
                Tertiary("Переписки и ленты друзей это не касается")
            }
        }

        Name("Язык приложения", modifier = Modifier.padding(horizontal = TimaSpacing.about4))
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
                        language.tag == current -> Chip("выбран", kind = ChipKind.Selected)
                        !language.available -> Chip("скоро", kind = ChipKind.Quiet)
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
    ListLine(
        onClick = { onChange(!on) },
        middle = { Name(title) },
        right = {
            Chip(
                if (on) "включено" else "выключено",
                kind = if (on) ChipKind.Selected else ChipKind.Quiet,
            )
        },
    )
}
