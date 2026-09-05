package io.tima.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import io.tima.core.ui.Secondary
import io.tima.core.ui.SubwindowHeader
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.Trouble

/**
 * Новый контакт — подокно (ПЛАН-КОНТАКТОВ.md, Д6).
 *
 * **Обязателен только номер.** По нему приложение находит человека; имя и раздел можно не
 * заполнять — имя подставится из телефонной книги, раздел будет общим.
 *
 * **Исход сверки сказан до нажатия**, и слово на кнопке от него зависит: «Написать»
 * обещает переписку, а обещать её тому, кого в TIMa нет, нельзя — писать ещё некому.
 *
 * **Имя здесь местное.** Оно живёт в нашей книге и обратно в телефон не пишется:
 * «Витя-сосед» — то, как его зовёте вы, а не то, как он назвался.
 */
@Composable
fun NewContactScreen(
    state: NewContactState,
    onPhone: (String) -> Unit,
    onName: (String) -> Unit,
    onSection: (String) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Tima.colors
    Column(modifier.fillMaxSize().background(colors.surface)) {
        SubwindowHeader(title = "Новый контакт", onBack = onBack)

        Box(
            modifier = Modifier.fillMaxSize().padding(TimaSpacing.about5),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(TimaSpacing.about4),
            ) {
                Caption("Номер телефона", fontSize = TimaType.sz5, weight = FontWeight.Bold)
                Field(value = state.phone, onChange = onPhone, hint = "+7 916 000-11-22")

                Caption("Имя — как будете звать его вы", fontSize = TimaType.sz5, weight = FontWeight.Bold)
                Field(value = state.name, onChange = onName, hint = "необязательно")

                Caption("Раздел", fontSize = TimaType.sz5, weight = FontWeight.Bold)
                Field(value = state.section, onChange = onSection, hint = "Общий")

                // Исход сверки: сказан обычным текстом, а не отказом. «Не найден» — не
                // ошибка человека, а состояние мира.
                state.about?.let { Secondary(it) }
                state.trouble?.let { Trouble(it) }

                // Кнопка не гаснет, а отвечает словами: погашенная кнопка не
                // объясняет, чего ей не хватает, и в неё жмут повторно.
                Button(
                    label = state.saveWord,
                    onClick = { if (state.canSave) onSave() },
                    kind = if (state.canSave) ButtonKind.Action else ButtonKind.Quiet,
                )
            }
        }
    }
}

/**
 * Новый раздел книги — то же подокно, второй его смысл.
 *
 * Из «＋» у поиска выбирают, что заводить: человека или раздел. Раздел с существующим
 * названием не заводится дважды — иначе счётчики разделов перестают складываться.
 */
@Composable
fun NewSectionScreen(
    name: String,
    onName: (String) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Tima.colors
    Column(modifier.fillMaxSize().background(colors.surface)) {
        SubwindowHeader(title = "Новый раздел", onBack = onBack)

        Box(
            modifier = Modifier.fillMaxSize().padding(TimaSpacing.about5),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(TimaSpacing.about4),
            ) {
                Caption("Название", fontSize = TimaType.sz5, weight = FontWeight.Bold)
                Field(value = name, onChange = onName, hint = "Дача")
                // Людей в раздел кладут потом: заставлять выбирать их сейчас значит
                // требовать решения там, где человек ещё только придумал имя папки.
                Secondary("Людей переложите в него потом — из строки контакта.")
                Button(
                    label = "Создать раздел",
                    onClick = { if (name.isNotBlank()) onSave() },
                    kind = if (name.isNotBlank()) ButtonKind.Action else ButtonKind.Quiet,
                )
            }
        }
    }
}
