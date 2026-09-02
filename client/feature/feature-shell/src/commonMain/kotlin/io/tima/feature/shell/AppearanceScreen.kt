package io.tima.feature.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.tima.core.ui.Appearance
import io.tima.core.ui.Button
import io.tima.core.ui.ButtonKind
import io.tima.core.ui.ColorSlot
import io.tima.core.ui.Field
import io.tima.core.ui.ListLine
import io.tima.core.ui.Name
import io.tima.core.ui.SectionTitle
import io.tima.core.ui.Secondary
import io.tima.core.ui.Tertiary
import io.tima.core.ui.ThemeChoice
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaColors
import io.tima.core.ui.TimaShapes
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.colorOf
import io.tima.core.ui.hex
import io.tima.core.ui.slot
import io.tima.core.ui.with

/**
 * «Оформление» — раздел настроек: какая тема и из чего состоит своя.
 *
 * ── ЧТО ЗДЕСЬ ЕСТЬ И ЧЕГО НАМЕРЕННО НЕТ ─────────────────────────────────────
 *
 * Три темы списком, и у выбранной стоит пометка. Под «Пользовательской» — семнадцать
 * строк «что → цвет»: образец, название, назначение и значение. Нажатие на строку
 * открывает ввод; ввод принимает `RRGGBB` и `AARRGGBB`, с решёткой и без.
 *
 * **Выбор цвета — поле, а не круг с радугой.** Круг требует своей отрисовки, своего
 * жеста и своей проверки в двух темах; поле требует шести знаков, которые человек и так
 * копирует из макета или из палитры. Когда понадобится круг, он встанет на это же место
 * — но заводить его до того, как в нём появилась нужда, значит платить за него сейчас.
 *
 * **Тема применяется сразу, а не по кнопке «Сохранить».** Оформление тем и проверяют,
 * что смотрят на него; кнопка между выбором и результатом превращает подбор цвета в
 * череду сохранений.
 *
 * **«Вернуть как было» есть, и это не украшение.** Семнадцать цветов открыты целиком,
 * белый текст на белом фоне здесь никто не запрещает — значит выход из положения, когда
 * экран перестал читаться, обязан существовать. Кнопка возвращает светлую или тёмную,
 * смотря от какой отталкивались.
 */
@Composable
fun AppearanceScreen(
    appearance: Appearance,
    onAppearance: (Appearance) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Tima.colors
    // Какой цвет сейчас правят. `null` — правят не цвет, а тему.
    var editing by remember { mutableStateOf<ColorSlot?>(null) }

    Column(
        modifier
            .fillMaxSize()
            .background(colors.surface)
            .verticalScroll(rememberScrollState()),
    ) {
        SectionTitle("Тема")
        for (choice in ThemeChoice.entries) {
            ListLine(
                onClick = { onAppearance(appearance.copy(choice = choice)) },
                left = { Sample(sampleOf(choice, appearance)) },
                right = if (choice == appearance.choice) {
                    { Name("✓") }
                } else {
                    null
                },
                middle = { Name(choice.title) },
            )
        }

        if (appearance.choice != ThemeChoice.Custom) {
            // Строки цветов показываются только у своей темы. Показать их у светлой
            // значило бы предложить править то, что не применится.
            Tertiary(
                text = "Свои цвета показываются, когда выбрана «Пользовательская». " +
                    "Правки в ней сохраняются и при переключении на светлую или тёмную.",
                modifier = Modifier.padding(TimaSpacing.about4),
            )
            return@Column
        }

        SectionTitle("Цвета")
        for (slot in ColorSlot.entries) {
            val value = appearance.custom.slot(slot)
            ListLine(
                onClick = { editing = if (editing == slot) null else slot },
                left = { Sample(value) },
                right = { Secondary(value.hex()) },
                middle = {
                    Column {
                        Name(slot.title)
                        if (slot.about.isNotBlank()) Tertiary(slot.about)
                    }
                },
            )
            if (editing == slot) {
                Editor(
                    value = value.hex(),
                    onColor = { onAppearance(appearance.copy(custom = appearance.custom.with(slot, it))) },
                    onDone = { editing = null },
                )
            }
        }

        Box(Modifier.padding(TimaSpacing.about4)) {
            Button(
                label = "Вернуть как было",
                kind = ButtonKind.Dangerous,
                onClick = {
                    // Отталкиваемся от того, что ближе: тёмную возвращаем тёмной.
                    val base = if (appearance.custom.surface == TimaColors.dark.surface) {
                        TimaColors.dark
                    } else {
                        TimaColors.light
                    }
                    onAppearance(appearance.copy(custom = base))
                    editing = null
                },
            )
        }
    }
}

/**
 * Ввод одного цвета.
 *
 * Правка применяется **на каждый разобранный ввод**, а не по кнопке: человек видит цвет
 * на самом экране, который правит. Непонятный набор знаков не применяется и ничего не
 * ломает — прежнее значение остаётся, пока не наберётся понятное.
 */
@Composable
private fun Editor(
    value: String,
    onColor: (androidx.compose.ui.graphics.Color) -> Unit,
    onDone: () -> Unit,
) {
    var typed by remember(value) { mutableStateOf(value) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Tima.colors.functional)
            .padding(TimaSpacing.about4),
        verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
    ) {
        Field(
            value = typed,
            onChange = { text ->
                typed = text
                colorOf(text)?.let(onColor)
            },
            hint = "AARRGGBB",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2)) {
            Tertiary(
                text = if (colorOf(typed) == null) {
                    "Шесть или восемь шестнадцатеричных знаков. Пока не разобрано — цвет прежний"
                } else {
                    "Первые два знака — непрозрачность: FF непрозрачный, 00 невидимый"
                },
                modifier = Modifier.weight(1f),
            )
            Button(label = "Готово", onClick = onDone)
        }
    }
}

/** Образец цвета: квадрат, потому что это «что-то», а не «нажми». */
@Composable
private fun Sample(color: androidx.compose.ui.graphics.Color) = Box(
    modifier = Modifier
        .size(TimaZonesAvatar)
        .background(color, RoundedCornerShape(TimaShapes.square))
        // Рамка обязательна: без неё белый образец на белом фоне — пустое место, а
        // именно белый и надо увидеть, когда подбираешь тему.
        .border(1.dp, Tima.colors.line, RoundedCornerShape(TimaShapes.square)),
    contentAlignment = Alignment.Center,
) {}

private val TimaZonesAvatar = 32.dp

/** Чем показать тему в списке: её собственным цветом навигации. */
private fun sampleOf(choice: ThemeChoice, appearance: Appearance) = when (choice) {
    ThemeChoice.Light -> TimaColors.light.navigation
    ThemeChoice.Dark -> TimaColors.dark.surface
    ThemeChoice.Custom -> appearance.custom.navigation
}
