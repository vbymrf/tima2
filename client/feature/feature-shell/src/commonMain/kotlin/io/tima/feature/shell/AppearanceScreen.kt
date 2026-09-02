package io.tima.feature.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import io.tima.core.ui.ColorPicker
import io.tima.core.ui.ColorSlot
import io.tima.core.ui.Field
import io.tima.core.ui.ListLine
import io.tima.core.ui.Name
import io.tima.core.ui.SectionTitle
import io.tima.core.ui.Secondary
import io.tima.core.ui.Tertiary
import io.tima.core.ui.VitalPair
import io.tima.core.ui.contrastOf
import io.tima.core.ui.merged
import io.tima.core.ui.ThemeChoice
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaFixed
import io.tima.core.ui.TimaTheme
import io.tima.core.ui.TimaColors
import io.tima.core.ui.TimaShapes
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.colorOf
import io.tima.core.ui.colorProblem
import io.tima.core.ui.paletteFor
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
 * **Выбор цвета — поле и палитра готовых, а не круг с радугой.** Круг требует своей
 * отрисовки, своего жеста и своей проверки; поле требует шести знаков, а палитра не
 * требует и их. Когда понадобится круг, он встанет на это же место.
 *
 * ── ТРИ ПРАВКИ 2026-09-03, И ВСЕ ТРИ ОТМЕНЯЮТ ПРЕЖНИЕ РЕШЕНИЯ ────────────────
 *
 * **Цвет применяется по кнопке, а не на каждый набранный знак.** Прежняя редакция
 * применяла всё, что разобралось, и это ломалось ровно на стирании: `FF8AC44A` без
 * последнего знака — семь знаков, мусор; ещё без одного — шесть, то есть **другой
 * законный цвет**, и он тут же применялся. Поле после этого перерисовывалось из
 * применённого значения, и набранное человеком исчезало у него под пальцами. Заказчик
 * описал это точно: «цвета не дают установить; удаляя символы, автоматически
 * подставляется цвет».
 *
 * **Непонятный ввод не применяется и объясняет, чем он непонятен** ([colorProblem]).
 * «Не сохранилось» без причины неотличимо от поломки.
 *
 * **Экран рисуется не выбранной темой, а [TimaFixed.appearance] — чёрным по белому.**
 * Своя тема открыта целиком, и первым, что она делала нечитаемым, был этот самый экран:
 * человек оставался без строк, без цветов и без кнопки возврата. Цена принята: своей
 * темы на этом экране не видно. Видно её в образцах строк и во всём остальном
 * приложении.
 *
 * **Возврат есть, и это не украшение.** Семнадцать цветов открыты целиком, белый текст
 * на белом фоне здесь никто не запрещает — значит выход из положения, когда экран
 * перестал читаться, обязан существовать.
 *
 * **Кнопок возврата две — «Вернуть светлую» и «Вернуть тёмную».** Решение заказчика:
 * «настройки взять с них по умолчанию». Одна кнопка вынуждала угадывать, от какой темы
 * отталкивались, а угадывать здесь нечем: своя палитра к тому моменту может не походить
 * ни на одну из двух. Две кнопки называют исход прямо и заодно дают то, чего одна не
 * давала вовсе, — перейти к своей тёмной, начав со светлой.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceScreen(
    appearance: Appearance,
    onAppearance: (Appearance) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Экран подбора цветов не подчиняется подобранным цветам: иначе он ломается первым.
    TimaTheme(colors = TimaFixed.appearance) { Inside(appearance, onAppearance, modifier) }
}

/**
 * Само содержимое экрана. Отделено от [AppearanceScreen] ровно затем, чтобы обёртка темы
 * стояла одной строкой и её нельзя было потерять при следующей правке разметки.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Inside(
    appearance: Appearance,
    onAppearance: (Appearance) -> Unit,
    modifier: Modifier,
) {
    // Какой цвет сейчас правят. `null` — правят не цвет, а тему.
    var editing by remember { mutableStateOf<ColorSlot?>(null) }

    Column(
        modifier
            .fillMaxSize()
            .background(TimaFixed.paper)
            .verticalScroll(rememberScrollState()),
    ) {
        // Предупреждение стоит НАВЕРХУ и держится, пока пара не разведена: пока оно
        // висит, «назад» из оформления не выпускает. Человек, который этого не заметил,
        // упрётся в неработающую стрелку — и ответ будет уже на экране, а не в голове.
        for (pair in appearance.colors.merged()) {
            Merged(
                pair = pair,
                ratio = appearance.colors.contrastOf(pair),
                onFix = {
                    onAppearance(
                        appearance.copy(
                            custom = appearance.custom
                                .with(pair.front, TimaColors.light.slot(pair.front))
                                .with(pair.back, TimaColors.light.slot(pair.back)),
                        ),
                    )
                },
            )
        }

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
                    slot = slot,
                    value = value.hex(),
                    onColor = { onAppearance(appearance.copy(custom = appearance.custom.with(slot, it))) },
                    onDone = { editing = null },
                )
            }
        }

        // Две кнопки, а не одна: возврат обязан быть выбором, а не догадкой.
        // Промежуточная редакция того же дня решала за человека — сравнивала фон своей
        // темы с тёмным и возвращала «то, что ближе». Догадка врёт ровно тогда, когда
        // дороже всего: человек, начавший со светлой и перекрасивший фон в тёмный,
        // получал бы тёмную обратно и не понимал, почему.
        FlowRow(
            modifier = Modifier.padding(TimaSpacing.about4),
            horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
            verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
        ) {
            for ((label, base) in RESETS) {
                Button(
                    label = label,
                    kind = ButtonKind.Dangerous,
                    onClick = {
                        onAppearance(appearance.copy(custom = base))
                        editing = null
                    },
                )
            }
        }
    }
}

/**
 * Предупреждение о слившейся паре — и кнопка, которая её разводит.
 *
 * ── ПОЧЕМУ ПРЕДУПРЕЖДЕНИЕ, А НЕ ЗАПРЕТ НА ВВОДЕ ─────────────────────────────
 *
 * Заказчик назвал момент проверки сам: «при нажатии на кнопку назад — в этот момент
 * пользователь уже не вернётся». И это правильнее запрета в момент правки, вот почему.
 *
 * Пару меняют по одному цвету: чтобы прийти к чёрному тексту на белом из белого по
 * белому, надо пройти через одно из двух промежуточных состояний, и оба «слившиеся».
 * Запрет на «Применить» сделал бы такой переход невозможным вовсе — человек не смог бы
 * добраться до состояния, которое сам же считает правильным.
 *
 * Поэтому правка свободна, а заперта **дверь**: пока пара слита, из оформления не
 * выпускает. Всё, что можно испортить, чинится здесь же — экран читается всегда.
 *
 * Кнопка берёт значения **светлой** темы для этих двух цветов, а не «ближайшей»:
 * угадывать за человека уже пробовали 2026-09-02, и от этого отказались.
 */
@Composable
private fun Merged(pair: VitalPair, ratio: Double, onFix: () -> Unit) = Column(
    modifier = Modifier
        .fillMaxWidth()
        .background(Tima.colors.functional)
        .padding(TimaSpacing.about4),
    verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
) {
    Name("Так отсюда не выйти")
    Tertiary(
        "«${pair.front.title}» и «${pair.back.title}» слились: ${ratio.rounded()} : 1. " +
            "Этим нарисовано ${pair.where} — без них до оформления уже не дойти, " +
            "поэтому «назад» подождёт.",
    )
    Button(label = "Взять из светлой", onClick = onFix)
}

/** Контраст числом, каким его читает человек: «1,4», а не «1.3999999». */
private fun Double.rounded(): String {
    val tenths = (this * 10).toLong()
    return "${tenths / 10},${tenths % 10}"
}

/**
 * Кнопки возврата: надпись и палитра, к которой она возвращает.
 *
 * Списком, а не двумя вызовами подряд: набор кнопок — это решение, и проверять его надо
 * как решение. Третьей темы здесь взяться неоткуда — своя как раз и есть то, от чего
 * возвращаются.
 */
internal val RESETS: List<Pair<String, TimaColors>> = listOf(
    "Вернуть светлую" to TimaColors.light,
    "Вернуть тёмную" to TimaColors.dark,
)

/**
 * Ввод одного цвета: поле, палитра готовых и кнопка «Применить».
 *
 * ── ПРАВКА ПРИМЕНЯЕТСЯ ПО КНОПКЕ, И ЭТО ГЛАВНОЕ ЗДЕСЬ ───────────────────────
 *
 * Набранное живёт **только в поле**, пока человек не нажал «Применить». Прежняя редакция
 * применяла всё, что разобралось, на каждый знак — и это ломалось не на вводе, а на
 * стирании: у `FF8AC44A` шесть последних знаков — тоже законный цвет, и он применялся
 * посреди удаления. Дальше поле перерисовывалось из применённого значения, и набранное
 * исчезало под пальцами.
 *
 * Поэтому же `remember` держит слот ключом, а не значение цвета: значение меняется от
 * применения, и ключ по нему стирал бы набранное ровно в тот момент, когда оно нужно.
 *
 * **Непонятное не применяется и объясняет, чем оно непонятно.** Кнопка при этом гаснет:
 * запрет виден до нажатия, а не после.
 */
@Composable
private fun Editor(
    slot: ColorSlot,
    value: String,
    onColor: (androidx.compose.ui.graphics.Color) -> Unit,
    onDone: () -> Unit,
) {
    var typed by remember(slot) { mutableStateOf(value) }
    var palette by remember(slot) { mutableStateOf(false) }
    val problem = colorProblem(typed)
    val ready = colorOf(typed)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Tima.colors.functional)
            .padding(TimaSpacing.about4),
        verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Field(
                value = typed,
                onChange = { typed = it },
                hint = "AARRGGBB",
                modifier = Modifier.weight(1f),
            )
            // «Палитра» стоит правее области номера — так её и заказали 2026-09-03.
            // Выбор из палитры **набирает число в поле**, а не применяется сам: путь к
            // применению один, и он проходит через «Применить». Иначе получилось бы,
            // что набранное требует подтверждения, а выбранное — нет.
            Button(
                label = if (palette) "Скрыть" else "Палитра",
                kind = ButtonKind.Quiet,
                onClick = { palette = !palette },
            )
        }

        if (palette) {
            // Подбор — квадратом и полосой; готовые цвета проекта остались рядом рядком.
            // Одно другого не заменяет: квадратом подбирают, образцом возвращаются.
            ColorPicker(
                color = colorOf(typed) ?: colorOf(value) ?: Tima.colors.surface,
                onPick = { typed = it.hex() },
            )
            Tertiary("Цвета проекта")
            Ready(slot) { typed = it.hex() }
        }

        Tertiary(
            text = problem
                ?: "Первые два знака — непрозрачность: FF непрозрачный, 00 невидимый",
        )

        Row(horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2)) {
            // Гаснет, а не ругается после нажатия: запрет виден до того, как в него
            // упёрлись. Ровно поэтому же кнопка не «Готово»: она применяет, и называть
            // её надо тем, что она делает.
            Button(
                label = "Применить",
                kind = if (ready == null) ButtonKind.Quiet else ButtonKind.Action,
                onClick = {
                    val color = colorOf(typed) ?: return@Button
                    onColor(color)
                    onDone()
                },
            )
            Button(label = "Отмена", kind = ButtonKind.Dangerous, onClick = onDone)
        }
    }
}

/**
 * Готовые цвета проекта плиткой: нажатие набирает число в поле.
 *
 * Первые два — значения **этого места** в светлой и тёмной теме; дальше вся палитра
 * проекта. Список считает [paletteFor] из самих тем, руками здесь не выписано ничего.
 *
 * Оставлены рядом с квадратом подбора нарочно: квадратом подбирают новое, образцом
 * возвращаются к известному. Попасть пальцем ровно в `#8AC44A` на квадрате нельзя.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Ready(slot: ColorSlot, onPick: (androidx.compose.ui.graphics.Color) -> Unit) = FlowRow(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
    verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
) {
    for (color in paletteFor(slot)) {
        Box(
            modifier = Modifier
                .size(TimaZonesAvatar)
                .background(color, RoundedCornerShape(TimaShapes.square))
                .border(1.dp, Tima.colors.line, RoundedCornerShape(TimaShapes.square))
                .clickable { onPick(color) },
        )
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
