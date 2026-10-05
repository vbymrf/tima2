package io.tima.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Стан — ряд полос. Одна разметка на все три формата (У.4).
 *
 * **Формат различается контейнерным запросом**: спрашивается доступная ширина, а не
 * устройство и не окно. На ПК окно приложения бывает узким, на планшете — половиной
 * экрана в разделённом режиме; спрашивать устройство значит однажды получить телефонную
 * раскладку на ПК и наоборот.
 *
 * Экраны про формат не знают ничего. Они отдают четыре слота — рейку, колонку, главную
 * область и панель, — а сколько из них видно, решает ширина:
 *
 * - **телефон**: одна полоса. Главная область не стоит рядом с колонкой, а **заменяет
 *   её**: подокно открывается перерисовкой. Это не «спрятать полосы», а то же самое
 *   поведение, что было в телефонном макете;
 * - **планшет**: рейка значками, колонка списка, главная область;
 * - **ПК**: рейка с подписями, колонка шире, справа страница объекта.
 *
 * Слот `панель` показывается только если ширина его вытерпела. Правило макета — «третья
 * полоса появляется, только когда на неё хватило места», — и вычисляет это [раскладкаДля],
 * а не таблица устройств.
 *
 * **Разделители тянутся мышью** (заказчик 2026-09-27), если дан [onSizes]. Рейка —
 * переключатель: значки или подписи, промежуточной ширины нет — подписи обрезались бы.
 * Колонка — плавно, но не уже [FormatTima.COLUMN_MIN] и не шире, чем оставляет главной
 * области [FormatTima.MAIN_DRAG_MIN]. Сколько полос — по-прежнему решает ширина окна.
 * Выбор отдаётся наверх, когда кнопку отпустили, — хранит его вызывающий.
 */
@Composable
fun Stage(
    /** Список: корневое окно целиком — шапка, вкладки, содержимое. На телефоне это экран. */
    column: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    /** Рейка окон. На телефоне переключение окон — подокно, и рейки нет. */
    rail: (@Composable (Layout) -> Unit)? = null,
    /** Подокно: чат, пост, слайд. `null` — ничего не выбрано. */
    main: (@Composable () -> Unit)? = null,
    /** Страница объекта. Показывается только на ПК. */
    panel: (@Composable () -> Unit)? = null,
    /** Что показать в главной области, пока ничего не выбрано. */
    empty: @Composable () -> Unit = { EmptyArea() },
    /**
     * Область 3 **только на широком формате** — поверх [main]. На телефоне её нет, и там
     * этот слот не зовётся вовсе: [main] на телефоне заменяет колонку, а это содержимое
     * колонку заменять не должно. Так на ПК показывается видео собеседника во время
     * звонка, пока кнопки и своё окошко остаются в колонке (заказчик 2026-09-26).
     */
    wideMain: (@Composable () -> Unit)? = null,
    /** Ширины, выставленные человеком. См. [StageSizes]. */
    sizes: StageSizes = StageSizes(),
    /** Человек отпустил разделитель — запомнить. `null` — разделители не тянутся. */
    onSizes: ((StageSizes) -> Unit)? = null,
) {
    BoxWithConstraints(modifier) {
        val available = maxWidth
        // Пока разделитель тянут, выбор живёт здесь: писать его в хранилище на каждый
        // сдвиг мыши незачем. Пришёл новый снаружи — он и становится текущим.
        var live by remember(sizes) { mutableStateOf(sizes) }
        // Место под панель держится, только когда её есть чем заполнить: иначе колонка
        // упиралась бы в пустоту шириной в панель (найдено 2026-09-27 живым прогоном).
        val byFormat = layoutFor(available).let { if (panel == null) it.copy(panel = null) else it }
        val layout = byFormat.withSizes(live, available)
        val colors = Tima.colors
        // Откуда начали тянуть и сколько протянули — ширина полосы по ходу меняется сама,
        // и считать от неё значило бы дёргаться на каждом шаге.
        var from by remember { mutableStateOf(0.dp) }
        var pulled by remember { mutableStateOf(0.dp) }
        CompositionLocalProvider(LayoutLocal provides layout) {
            if (layout.phone) {
                // Перерисовка, а не полосы: выбранное подокно занимает окно целиком, и
                // пустого состояния на телефоне не бывает вовсе — там список и есть экран.
                Box(Modifier.fillMaxSize()) { (main ?: column)() }
                return@CompositionLocalProvider
            }

            Row(Modifier.fillMaxSize()) {
                layout.rail?.let { width ->
                    Box(
                        Modifier
                            .width(width)
                            .fillMaxHeight()
                            .background(colors.functional)
                            .rightLine(colors.line),
                    ) {
                        rail?.invoke(layout)
                        if (onSizes != null) {
                            Splitter(
                                Modifier.align(Alignment.CenterEnd),
                                onStart = { from = width; pulled = 0.dp },
                                onDrag = { step ->
                                    pulled += step
                                    // Переключатель: за серединой между двумя ширинами — другое положение.
                                    val caption = from + pulled > (FormatTima.ICON_RAIL_WIDTH + FormatTima.CAPTION_RAIL) / 2
                                    if (caption != layout.railCaption) live = live.copy(railCaption = caption)
                                },
                                onStop = { onSizes(live) },
                            )
                        }
                    }
                }

                Box(
                    Modifier
                        .width(layout.column ?: available)
                        .fillMaxHeight()
                        .rightLine(colors.line),
                ) {
                    column()
                    val columnWidth = layout.column
                    if (onSizes != null && columnWidth != null) {
                        Splitter(
                            Modifier.align(Alignment.CenterEnd),
                            onStart = { from = columnWidth; pulled = 0.dp },
                            onDrag = { step ->
                                pulled += step
                                live = live.copy(column = from + pulled)
                            },
                            // Запоминается то, что встало, а не то, куда дотянула мышь за предел.
                            onStop = { onSizes(live.copy(column = byFormat.withSizes(live, available).column)) },
                        )
                    }
                }

                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(colors.surface),
                ) { (wideMain ?: main)?.invoke() ?: empty() }

                if (panel != null) {
                    layout.panel?.let { width ->
                        Box(
                            Modifier
                                .width(width)
                                .fillMaxHeight()
                                .background(colors.surface)
                                .leftLine(colors.line),
                        ) { panel() }
                    }
                }
            }
        }
    }
}

/**
 * Как выглядит указатель над разделителем. В общем коде есть только «рука»; ПК подставляет
 * свою стрелку «влево-вправо», которой здесь не достать.
 */
val LocalSplitterIcon = staticCompositionLocalOf { PointerIcon.Hand }

/**
 * Разделитель полос: узкая полоса у правого края, за которую тянут. Лежит поверх края
 * полосы, а не рядом, — рядом он съел бы ширину, посчитанную [withSizes].
 */
@Composable
private fun Splitter(modifier: Modifier, onStart: () -> Unit, onDrag: (Dp) -> Unit, onStop: () -> Unit) {
    val density = LocalDensity.current
    var dragging by remember { mutableStateOf(false) }
    val state = rememberDraggableState { px -> onDrag(with(density) { px.toDp() }) }
    Box(
        modifier
            .width(SPLITTER_WIDTH)
            .fillMaxHeight()
            .pointerHoverIcon(LocalSplitterIcon.current)
            .draggable(
                state = state,
                orientation = Orientation.Horizontal,
                onDragStarted = { dragging = true; onStart() },
                onDragStopped = { dragging = false; onStop() },
            ),
    ) {
        // Пока тянут — линия ярче: видно, что взялось.
        if (dragging) {
            Box(Modifier.align(Alignment.CenterEnd).width(2.dp).fillMaxHeight().background(Tima.colors.navigation))
        }
    }
}

private val SPLITTER_WIDTH = 6.dp

/**
 * Содержимое главной области по центру полосы.
 *
 * Поток не растягивается во всю ширину: строка длиной в метр не читается. Предел тот же,
 * что в макете, — [TimaФорматы.ПРЕДЕЛ_СОДЕРЖИМОГО].
 */
@Composable
fun InCenter(modifier: Modifier = Modifier, content: @Composable () -> Unit) = Box(
    modifier = modifier.fillMaxWidth(),
    contentAlignment = Alignment.TopCenter,
) {
    Box(Modifier.widthIn(max = FormatTima.CONTENT_MAX_WIDTH)) { content() }
}

/**
 * Пустая главная область: пока ничего не выбрано.
 *
 * На телефоне такого состояния нет вовсе, и [Стан] его там не показывает.
 *
 * **Знака по умолчанию нет намеренно.** В первой редакции здесь стоял «✉», и на снимке он
 * вышел пустым прямоугольником: этого глифа нет в шрифте, которым рисует система, а
 * подстановки для него не нашлось. Знак — дело набора значков (К5), и до него лучше
 * пустое место, чем квадратик: пустое место человек не примет за поломку.
 */
@Composable
fun EmptyArea(
    glyph: String? = null,
    /**
     * Заголовок. Умолчание приходит из словаря (ПЛАН-(Я)-ЯЗЫКА Я2): раньше оно стояло здесь
     * строкой, и на другом языке пустая область осталась бы русской.
     */
    title: String = LocalWords.current.common.nothingChosen,
    explanation: String? = null,
    modifier: Modifier = Modifier,
) = Column(
    modifier = modifier.fillMaxSize().padding(TimaSpacing.about6),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2, Alignment.CenterVertically),
) {
    glyph?.let { Caption(it, fontSize = TimaType.sz1, color = Tima.colors.text3) }
    Caption(title, fontSize = TimaType.sz3, weight = FontWeight.ExtraBold)
    explanation?.let { Secondary(it) }
}

/**
 * Зона 3 — гроздь создания — там, где ей место в этом формате.
 *
 * **Одна разметка, разное место.** На телефоне круглые кнопки висят над списком: там они
 * опираются на содержимое, и это нормально. На широком формате они **опускаются** в
 * отдельную область у нижнего края колонки, отделённую линией, — тем же приёмом, что
 * настройки внизу рейки. Круглая кнопка, висящая поверх широкого списка, опирается только
 * на воздух.
 *
 * Кнопки при этом те же самые. Вызывающий передаёт их один раз и не спрашивает про формат.
 */
@Composable
fun WithCluster(
    cluster: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    /** Подпись рядом с кнопками. Появляется только внизу колонки: на телефоне места нет. */
    caption: String? = null,
    /**
     * Второй вход, стоящий отдельно от главного.
     *
     * **Отдельный слот, а не ещё одна кнопка внутри [cluster].** Найдено глазами на ПК
     * 2026-08-26: «Группа», положенная в ту же гроздь, встала вплотную к кругу «Написать»
     * и читалась как часть его — то есть подпись объясняла не ту кнопку. В макете
     * (`Layout-UI-light/пк/телефон.html`, `низ-колонки`) там ровно один круг с подписью.
     *
     * На телефоне гроздь ещё и складывалась: `Box` кладёт детей друг на друга, и два
     * входа оказывались один поверх другого в том же углу.
     */
    secondary: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val layout = LayoutLocal.current
    val colors = Tima.colors
    if (layout.phone) {
        Box(modifier.fillMaxSize()) {
            content()
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(TimaSpacing.about4),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
            ) {
                secondary?.invoke()
                cluster()
            }
        }
        return
    }

    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) { content() }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.functional)
                .topLine(colors.line)
                .padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about3),
            horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            cluster()
            caption?.let { Caption(it, fontSize = TimaType.sz5, weight = FontWeight.Bold, color = colors.text2) }
            // Второй вход уходит к дальнему краю: между ним и подписанным кругом остаётся
            // пустота, и она и есть то, что разделяет два разных действия.
            if (secondary != null) {
                Box(Modifier.weight(1f))
                secondary()
            }
        }
    }
}
