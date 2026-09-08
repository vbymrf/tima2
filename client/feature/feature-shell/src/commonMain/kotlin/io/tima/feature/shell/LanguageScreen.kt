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
import io.tima.core.ui.ChipKind
import io.tima.core.ui.Language
import io.tima.core.ui.ListLine
import io.tima.core.ui.Name
import io.tima.core.ui.Secondary
import io.tima.core.ui.SubwindowHeader
import io.tima.core.ui.Tertiary
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
