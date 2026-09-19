package io.tima.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Круглые управляющие элементы: кнопки, чипы, счётчики.
 *
 * Вторая половина правила формы: **круг означает «нажми»**. Счётчик при этом круглый,
 * хотя не нажимается, — и это осознанное исключение из макета: он пилюля, потому что
 * живёт в одном ряду с чипами и кнопками, а квадратный счётчик читался бы аватаром.
 */

/** Толщина обводки у «контурных» кнопок: `.кн.контур` — 2 px. */
private val OUTLINE = 2.dp

/**
 * Кнопка. Салатовая заливка — **навигация и действие**: «отправить», «написать»,
 * «назад», «подписаться».
 */
@Composable
fun Button(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Action,
    /**
     * Нажимается ли. `false` — **и выглядит иначе, и не срабатывает**.
     *
     * Второе важнее первого: кнопка, гасящая нажатие молча, читается как поломка
     * приложения. До 2026-09-06 «Отправить» на пустом отчёте была именно такой — тихой
     * на вид, но кликабельной, и человек жал её, не получая в ответ ничего.
     */
    enabled: Boolean = true,
) {
    val colors = Tima.colors
    val background = when {
        !enabled -> colors.quiet
        kind == ButtonKind.Action -> colors.navigation
        kind == ButtonKind.Quiet -> colors.softAccent
        // Сделано: заливка тревоги. Не «опасно» и не «ошибка», а наоборот — «получилось,
        // повторять не надо». Кнопка перестаёт звать и начинает отчитываться.
        kind == ButtonKind.Done -> colors.alarm
        // Опасное — БЕЗ ЦВЕТА, и это отдельное решение макета, оставшееся в силе:
        // отличают его слово, незаполненная кнопка и последнее место в списке.
        else -> Color.Transparent
    }
    val colorLabel = when {
        !enabled -> colors.text3
        kind == ButtonKind.Action || kind == ButtonKind.Done -> colors.onAccent
        else -> colors.text
    }

    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 42.dp)
            .background(background, CircleShape)
            .then(
                if (kind == ButtonKind.Dangerous) {
                    Modifier.border(OUTLINE, colors.text, CircleShape)
                } else {
                    Modifier
                },
            )
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = TimaSpacing.about2),
        contentAlignment = Alignment.Center,
    ) {
        Caption(label, fontSize = TimaType.sz5, weight = FontWeight.Bold, color = colorLabel)
    }
}

/**
 * Виды кнопок.
 *
 * **Опасного действия красным цветом не бывает** — решение макета, и оно осталось в силе:
 * отличается оно словом, незаполненной кнопкой и последним местом в списке. Красный
 * достался [ButtonKind.Done] — «сделано, повторять не надо», — и это разные вещи: одна
 * зовёт осторожно, вторая уже не зовёт вовсе.
 */
enum class ButtonKind { Action, Quiet, Dangerous, Done }

/**
 * Кнопка-иконка: круг 36 px. `.икона`.
 *
 * @param живая главное действие — салатовая. Внутри салатовой плашки шапки такая
 *   кнопка становится белой: салатовое на салатовом не видно, и это решено в
 *   [ШапкаОкна], а не здесь.
 */
@Composable
fun IconButton(
    glyph: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    live: Boolean = false,
    background: Color? = null,
    colorGlyph: Color? = null,
) {
    val colors = Tima.colors
    Box(
        modifier = modifier
            .size(TimaSizes.iconButton)
            .background(background ?: circleFill(live), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Caption(
            text = glyph,
            fontSize = TimaType.sz4,
            weight = FontWeight.Bold,
            color = colorGlyph ?: glyphColor(live),
        )
    }
}

/**
 * Стоим ли мы внутри салатовой плашки шапки.
 *
 * Правило макета — две строки: `.икона { background: var(--чёрный-6) }` и
 * `.зона-1 .икона { background: #ffffff }`. Салатовое на салатовом не видно, а серый
 * `--чёрный-6` на салатовом виден немногим лучше: шесть процентов чёрного поверх
 * зелёного дают почти тот же зелёный. **Внутри плашки круг белый, иначе круга нет
 * вовсе** — а без круга кнопка перестаёт выглядеть кнопкой, и заказчик увидел это
 * глазами 2026-09-02: «кнопки в шапке нужно разместить на своём круглом фоне».
 *
 * Признаком, а не параметром: правило принадлежит плашке, а не тому, кто кладёт в неё
 * кнопки. Параметром его пришлось бы передавать через каждое окно, и первый же
 * забывший вернул бы кнопку без круга.
 */
val LocalInPlate = staticCompositionLocalOf { false }

/**
 * Заливка круглой кнопки: белая в плашке, салатовая у главного действия, иначе серая.
 *
 * Серый здесь `--чёрный-6` — **тот же, что у невыбранной подвкладки**. Это не совпадение
 * и не экономия токена: в макете `.икона` и `.чип` берут одну и ту же тихую подложку,
 * и «как фон подвкладки» — ровно то, как заказчик её и назвал.
 */
/**
 * Цвет знака на круглой кнопке.
 *
 * **Внутри плашки он считается от заливки, а не берётся у темы.** Круг там белый, а
 * `colors.text` в тёмной теме тоже белый — знак пропадал целиком. Заливка круга при этом
 * человеку открыта («Внутри плашки» в «Оформлении»), поэтому и цвет знака обязан
 * считаться, а не быть выбранным заранее: см. [TimaContrast.readable].
 */
@Composable
private fun glyphColor(live: Boolean): Color {
    val colors = Tima.colors
    return when {
        LocalInPlate.current -> TimaContrast.readable(on = colors.inPlate, under = colors.navigation)
        live -> colors.onAccent
        else -> colors.text
    }
}

@Composable
private fun circleFill(live: Boolean): Color {
    val colors = Tima.colors
    return when {
        // Плашка сильнее «живости»: `.зона-1 .икона.жив { background: #ffffff }`.
        LocalInPlate.current -> colors.inPlate
        live -> colors.navigation
        else -> colors.quiet
    }
}

/**
 * Круглая кнопка с **рисунком** вместо знака: стрелка «назад», стрелка «отправить».
 *
 * Та же кнопка, что [КнопкаИконка], и отличается только тем, что внутри. Знаки, которых
 * нет в шрифте, рисуются (Знаки.kt), и такой кнопке нужно место под рисунок, а не под
 * строку.
 */
@Composable
fun ButtonCircle(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    live: Boolean = false,
    background: Color? = null,
    drawing: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .size(36.dp)
            .background(background ?: circleFill(live), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { drawing() }
}

/**
 * Чип: короткая пилюля. `.чип`.
 *
 * Значение несёт заливка, а не форма: салатовая — выбранное, зелёная — подтверждённое
 * (E2E), тихая — обычная пометка.
 */
@Composable
fun Chip(
    label: String,
    modifier: Modifier = Modifier,
    kind: ChipKind = ChipKind.Quiet,
    onClick: (() -> Unit)? = null,
    /**
     * Поле по бокам. Десять точек — `.чип` макета и значение для всех мест, кроме одного.
     *
     * Ужимает его только ряд подвкладок в «Коллекциях»: там рядом стоит переключатель, и
     * весь ряд обязан уместиться в одну строку (`.строка-фильтров > .чип { padding: 3px 6px }`).
     * Параметром, а не новым видом чипа: меняется размер одного ряда, а не значение
     * пилюли, и остальные чипы в списках трогать незачем.
     */
    horizontalPadding: Dp = 10.dp,
    /**
     * Знак перед словом — или вместо него, если [label] пуст. Получает цвет надписи, чтобы
     * краситься вместе с ней: на салатовом — белым, на сером — тёмным.
     *
     * Заведён для полосы разделов ярлычками (`разделы.md`, исполнение В): там чип — это
     * значок, а слово появляется только в исполнении Г.
     */
    leading: (@Composable (Color) -> Unit)? = null,
) {
    val colors = Tima.colors
    val background = when (kind) {
        ChipKind.Quiet -> colors.softAccent
        ChipKind.Neutral -> colors.quiet
        ChipKind.Selected -> colors.navigation
        ChipKind.Confirmed -> colors.confirmed
    }
    val labelColor = when (kind) {
        ChipKind.Quiet, ChipKind.Neutral -> colors.text2
        else -> colors.onAccent
    }

    Box(
        modifier = modifier
            .background(background, CircleShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = horizontalPadding, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading?.invoke(labelColor)
            if (label.isNotEmpty()) {
                Caption(label, fontSize = TimaType.sz6, weight = FontWeight.Bold, color = labelColor)
            }
        }
    }
}

/**
 * Вкладка окна. `.таб`.
 *
 * **Невыбранная вкладка — слово без заливки.** В этом всё её отличие от чипа, и оно
 * рабочее: ряд вкладок и ряд подвкладок стоят друг под другом, и если оба набраны
 * залитыми пилюлями, человек видит один сплошной ряд из шести кнопок. Заказчик прочёл
 * это ровно так 2026-09-02: «второй ряд полностью повторяет первый».
 *
 * До того дня вкладка была [Chip] вида [ChipKind.Quiet] — залитая тихим оттенком
 * навигации, ростом и отступами с чип. Макет с самого начала говорил другое:
 *
 * ```css
 * .таб     { padding: 6px 11px; font-size: var(--щ5); color: var(--чёрный-50) }
 * .таб.тек { background: var(--навигация); color: #ffffff }
 * ```
 *
 * Ни фона, ни рамки у невыбранной — и размер крупнее чипового. Расходился код, а не
 * макет, поэтому чинится код.
 *
 * **Поле по бокам — одиннадцать точек, а не четырнадцать.** Решение заказчика
 * 2026-09-03: все пункты меню обязаны стоять в одну строку. Четырьмя вкладками окна 5
 * («Подписан · Лента · Коллекции · Группы») при четырнадцати ряд просил 358 точек при
 * 348 доступных на телефоне и переносился; при одиннадцати просит 328. Ряд теперь
 * стоит строкой — это проверяет [RowFitTest], а не глаз.
 */
/**
 * Кнопка в ряду вкладок — `.таб-вид` макета.
 *
 * ── ПОЧЕМУ НЕ ПРОСТО `Tab` ──────────────────────────────────────────────────
 *
 * До 2026-09-16 «Вид» рисовался как `Tab(current = false)`: прозрачный фон и тихий
 * текст — то есть **ровно как невыбранная вкладка**. Заказчик сказал прямо: «кнопка
 * Вид не выглядит кнопкой», и это не вкусовщина — вкладка и кнопка означают разное.
 * Вкладка переключает то, ЧТО показано; кнопка открывает подокно. Одинаковый вид у
 * разного смысла — это обещание, которого интерфейс не держит.
 *
 * В макете различие есть и задано явно (`стиль.css`):
 *
 * ```css
 * .таб     { font-size: var(--щ5); color: var(--чёрный-50) }        без фона
 * .таб-вид { background: var(--чёрный-6); font-size: var(--щ6);     ЗАЛИТА,
 *            font-weight: 800; gap: 5px }                            со значком
 * ```
 *
 * Фон — [TimaColors.quiet] («тихая нецветная подложка», тот самый `--чёрный-6`), а не
 * `softAccent`: салатовый оттенок здесь означал бы навигацию, а кнопка не навигация.
 * Кегль на ступень мельче вкладки и начертание тяжелее — так она не спорит с ними за
 * внимание, оставаясь при этом видимой.
 */
/**
 * Отметка в списке — галка или точка, **нарисованная**, а не знаком шрифта.
 *
 * ── ПОЧЕМУ НАРИСОВАННАЯ ─────────────────────────────────────────────────────
 *
 * До 2026-09-16 здесь стояли знаки `☑` и `●` обычным текстом. Заказчик сказал: «плохо
 * видно галочки и точки, выбранное сделай зелёным». Знак `●` покрасился, а `☑` — нет:
 * **платформа рисует его цветным эмодзи и наш цвет игнорирует**. На снимке галки
 * вышли синими — цветом системного эмодзи, которого в нашей палитре нет вовсе.
 *
 * Знак шрифта нельзя ни покрасить наверняка, ни задать ему размер: у эмодзи своя
 * метрика, и `sz2` меняет её не так, как у буквы. Поэтому отметка рисуется фигурой —
 * тогда и цвет наш, и размер наш, и выглядит она одинаково на любом телефоне.
 *
 * Размеры из макета (`стиль.css`, `.галка`): квадрат 22, скругление 6, рамка 2.
 * Включённая залита навигацией, знак внутри белый — `.галка.вкл`.
 *
 * ── КВАДРАТ И КРУГ ЗНАЧАТ РАЗНОЕ ────────────────────────────────────────────
 *
 * [CheckMark] — квадрат: «сколько угодно, в том числе ничего».
 * [RadioMark] — круг: «одно из». Это то же правило формы, что у аватара и кнопки, и
 * менять его на «покрасим поярче» нельзя: форма отвечает на вопрос, сколько можно
 * выбрать, а цвет — только на вопрос, выбрано ли.
 */
@Composable
fun CheckMark(on: Boolean, modifier: Modifier = Modifier) {
    val colors = Tima.colors
    Box(
        modifier = modifier
            .size(MARK_SIDE)
            .background(if (on) colors.navigation else Color.Transparent, RoundedCornerShape(MARK_ROUNDING))
            .border(MARK_BORDER, if (on) colors.navigation else colors.border, RoundedCornerShape(MARK_ROUNDING)),
        contentAlignment = Alignment.Center,
    ) {
        if (on) {
            Caption(text = "✓", fontSize = TimaType.sz5, weight = FontWeight.ExtraBold, color = colors.onAccent)
        }
    }
}

/** Точка выбора: круг той же меры, что и галка, — «одно из». */
@Composable
fun RadioMark(on: Boolean, modifier: Modifier = Modifier) {
    val colors = Tima.colors
    Box(
        modifier = modifier
            .size(MARK_SIDE)
            .background(if (on) colors.navigation else Color.Transparent, CircleShape)
            .border(MARK_BORDER, if (on) colors.navigation else colors.border, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (on) {
            Box(Modifier.size(MARK_DOT).background(colors.onAccent, CircleShape))
        }
    }
}

/** Мера отметки — `.галка { width: 22px; border-radius: 6px; border: 2px }` макета. */
private val MARK_SIDE = 22.dp
private val MARK_ROUNDING = 6.dp
private val MARK_BORDER = 2.dp

/** Точка внутри круга: видна, но не заполняет его целиком. */
private val MARK_DOT = 8.dp

@Composable
fun TabButton(
    label: String,
    glyph: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Tima.colors
    Row(
        modifier = modifier
            .background(colors.quiet, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Caption(text = glyph, fontSize = TimaType.sz6, weight = FontWeight.ExtraBold, color = colors.text2)
        Caption(text = label, fontSize = TimaType.sz6, weight = FontWeight.ExtraBold, color = colors.text2)
    }
}

@Composable
fun Tab(
    label: String,
    current: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Tima.colors
    Box(
        modifier = modifier
            .background(if (current) colors.navigation else Color.Transparent, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = TAB_SIDE, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Caption(
            text = label,
            fontSize = TimaType.sz5,
            weight = FontWeight.Bold,
            color = if (current) colors.onAccent else colors.text2,
        )
    }
}

/**
 * Поле по бокам у вкладки — `.таб { padding: 6px 11px }` макета.
 *
 * Числом рядом с самой вкладкой, а не токеном отступа: в лестнице `--о1…--о6`
 * одиннадцати точек нет, и подгонять `about3` под этот случай значило бы менять её всем
 * остальным. Число подобрано под ширину ряда, а не под лестницу, — см. KDoc у [Tab].
 */
private val TAB_SIDE = 11.dp

/**
 * Виды чипа.
 *
 * [Quiet] и [Neutral] различаются только оттенком подложки, и различие это рабочее:
 * тихая подложка несёт оттенок навигации, нейтральная — нет. Ряд вкладок набран
 * первой, ряд подвкладок под ним — второй, и человек видит, какой из двух рядов
 * главнее, не читая надписей. Решение заказчика 2026-09-02.
 */
enum class ChipKind { Quiet, Neutral, Selected, Confirmed }

/**
 * Счётчик непрочитанного — **янтарь**. `.счёт`.
 *
 * Янтарь означает активность: новые сообщения, комментарии, непрочитанное. Текст на
 * нём чёрный в обеих темах: 12,32 : 1, и терять этот контраст не за что — янтарь не
 * зелёный, и правило про текст на заливке к нему не относится.
 */
@Composable
fun Counter(howMany: Int, modifier: Modifier = Modifier) {
    if (howMany <= 0) return
    val colors = Tima.colors
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 22.dp, minHeight = 22.dp)
            .background(colors.activity, CircleShape)
            .padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Caption(
            // Больше 99 показывать незачем: точное число не меняет решения человека,
            // а ширину пилюли меняет.
            text = if (howMany > 99) "99+" else howMany.toString(),
            fontSize = TimaType.sz6,
            weight = FontWeight.ExtraBold,
            color = colors.onAmber,
        )
    }
}

/** Ряд управляющих элементов с одинаковым зазором: чипы, кнопки шапки. */
@Composable
fun ControlRow(
    modifier: Modifier = Modifier,
    gap: androidx.compose.ui.unit.Dp = TimaSpacing.about2,
    content: @Composable () -> Unit,
) = Row(
    modifier = modifier,
    horizontalArrangement = Arrangement.spacedBy(gap),
    verticalAlignment = Alignment.CenterVertically,
) { content() }

/** Общие меры элементов управления: одна мера — одно место, иначе они расходятся. */
object TimaSizes {
    /**
     * Сторона круглой кнопки-иконки.
     *
     * Публичная, а не литерал внутри [IconButton]: по ней равняется поле поиска — заказчик
     * 2026-09-19 («сделай пузырь поиска поуже — до размера кнопки крестика»). Два литерала
     * `36.dp` в разных файлах разошлись бы на первой же правке одного из них.
     */
    val iconButton = 36.dp
}
