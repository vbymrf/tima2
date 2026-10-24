package io.tima.feature.chat

import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalDensity
import io.tima.domain.chat.ComposedLayout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.tima.core.ui.Caption
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaType
import io.tima.domain.chat.ComposedCell
import io.tima.domain.chat.ComposedDoc
import io.tima.domain.chat.ComposedItem
import io.tima.domain.chat.ComposedWidget
import io.tima.domain.chat.Drop
import io.tima.domain.chat.RichContent
import io.tima.domain.chat.Spot
import io.tima.domain.chat.TypingMarks
import kotlin.math.roundToInt

/**
 * Перенос в редакторе (Решения §10.3): палец — долгое нажатие и вести, мышь — за ручку. Куда
 * опустили — решают запомненные границы ячеек и кусков в координатах окна.
 */
@Stable
class EditorDrag {
    var from by mutableStateOf<Spot?>(null)
        private set
    var at by mutableStateOf(Offset.Zero)
        private set

    /** Верхний левый угол окна редактора — чтобы рисовать переносимое под пальцем. */
    var origin = Offset.Zero
    private val cells = mutableStateMapOf<Pair<Int, Int>, Rect>()
    private val items = mutableStateMapOf<Int, Rect>()
    private val listItems = mutableStateMapOf<Pair<Int, Int>, Rect>()

    fun cell(item: Int, cell: Int, r: Rect) { cells[item to cell] = r }
    fun item(item: Int, r: Rect) { items[item] = r }

    /** Пункт [listItem] списка [item] — подборку и подкладку можно опустить между пунктами (2026-10-21). */
    fun listItem(item: Int, listItem: Int, r: Rect) { listItems[item to listItem] = r }

    /** Низ последнего куска в координатах окна — ниже него пустое место. */
    fun bottom(): Float = items.values.maxOfOrNull { it.bottom } ?: 0f

    /** Документ сменился — прежние границы уже не про него. */
    fun forget(count: Int) {
        items.keys.filter { it >= count }.forEach(items::remove)
        cells.keys.filter { it.first >= count }.forEach(cells::remove)
        listItems.keys.filter { it.first >= count }.forEach(listItems::remove)
    }

    fun start(spot: Spot, point: Offset) { from = spot; at = point }
    fun move(by: Offset) { at += by }
    fun cancel() { from = null }

    /**
     * Опустили: в ячейку под пальцем, иначе между кусками — выше или ниже середины ближайшего.
     * [intoCells] — подкладка в ряд не встаёт: над рядом или под ним, по пальцу; [intoLists] — подборка
     * и подкладка над пунктом списка встают пунктом перед ним или после него.
     */
    fun target(count: Int, intoCells: Boolean = true, intoLists: Boolean = false): Drop {
        if (intoCells) cells.entries.firstOrNull { it.value.contains(at) }?.let { return Drop.IntoCell(it.key.first, it.key.second) }
        if (intoLists) listItems.entries.firstOrNull { it.value.contains(at) }?.let { (key, r) ->
            return Drop.IntoList(key.first, if (at.y < r.center.y) key.second else key.second + 1)
        }
        val hit = items.entries.sortedBy { it.key }.firstOrNull { at.y < it.value.bottom }
            ?: return Drop.Between(count)
        return Drop.Between(if (at.y < hit.value.center.y) hit.key else hit.key + 1)
    }

    /** Переносимое — то, что под пальцем: подсветить цель. */
    fun over(item: Int, cell: Int): Boolean = from != null && cells[item to cell]?.contains(at) == true
}

@Composable
internal fun rememberEditorDrag() = remember { EditorDrag() }

/** Жест переноса: долгое нажатие (палец) или ручка (мышь, сразу). */
private fun Modifier.draggable(
    drag: EditorDrag,
    spot: Spot,
    corner: () -> Offset,
    onDrop: (Spot, Drop) -> Unit,
    count: () -> Int,
    longPress: Boolean,
    intoCells: Boolean = true,
    intoLists: Boolean = false,
): Modifier =
    pointerInput(spot, longPress, intoCells, intoLists) {
        val start: (Offset) -> Unit = { off -> drag.start(spot, corner() + off) }
        val end: () -> Unit = {
            val from = drag.from
            if (from != null) onDrop(from, drag.target(count(), intoCells, intoLists))
            drag.cancel()
        }
        if (longPress) {
            // Долгое нажатие ловится раньше того, что внутри виджета (проход Initial): кнопка ссылки,
            // подборка, фото сами забирают нажатие — и перенос пальцем у них не начинался (2026-10-21).
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                val early = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        val c = e.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed || (c.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                    }
                    true
                }
                // Отпустили или повели до срока — это не перенос: нажатие уходит виджету как было.
                if (early != null) return@awaitEachGesture
                start(down.position)
                while (true) {
                    val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    val c = e.changes.firstOrNull { it.id == down.id }
                    if (c == null) {
                        drag.cancel()
                        break
                    }
                    if (!c.pressed) {
                        c.consume()
                        end()
                        break
                    }
                    val d = c.position - c.previousPosition
                    c.consume()
                    drag.move(d)
                }
            }
        } else {
            detectDragGestures(onDragStart = start, onDrag = { c, d -> c.consume(); drag.move(d) }, onDragEnd = end, onDragCancel = drag::cancel)
        }
    }

/** Виджет в редакторе: как увидит получатель, нажатие — выбрать, ручка и долгое нажатие — перенос. */
@Composable
internal fun EditorWidget(
    widget: ComposedWidget,
    spot: Spot,
    chosen: Boolean,
    model: EditorModel,
    drag: EditorDrag,
    preview: (ComposedDoc) -> RichContent?,
) {
    // «Не показывать в сообщении» — и в редакторе нет (заказчик 2026-10-24): правятся через «Свойства» видео
    // или аудио → «Редактировать метки».
    if (widget is ComposedWidget.Marks && widget.hidden) return
    val colors = Tima.colors
    var corner by remember { mutableStateOf(Offset.Zero) }
    val onDrop: (Spot, Drop) -> Unit = { from, to -> model.drop(from, to) }
    // Подкладка — не в ряд; подборка и подкладка — по пунктам готового списка (заказчик 2026-10-21).
    val intoCells = widget !is ComposedWidget.Underlay
    val intoLists = widget is ComposedWidget.Quoted || widget is ComposedWidget.Underlay
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp)
            .then(if (chosen) Modifier.border(2.dp, colors.navigation, RoundedCornerShape(8.dp)) else Modifier)
            .onGloballyPositioned { corner = it.boundsInRoot().topLeft }
            .draggable(drag, spot, { corner }, onDrop, { model.doc.items.size }, longPress = true, intoCells, intoLists)
            .clickable { model.select(spot) }
            .padding(4.dp)
            .testTag(EditorTags.spot(spot)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // В редакторе внутри виджета ничего не нажимается (заказчик 2026-10-21: «кликабельность убери —
        // ссылки и другого в редакторе, если не в режиме Показать сообщение»): нажатие выбирает виджет,
        // долгое — переносит. Прежде нажатие на ссылку открывало окно перехода с запретом.
        Box(Modifier.weight(1f)) {
            androidx.compose.runtime.CompositionLocalProvider(LocalGateBlocked provides true) {
                preview(ComposedDoc(listOf(ComposedItem.Widget(widget)), marks = model.doc.marks))?.let { RichBody(it) }
            }
            Box(
                Modifier.matchParentSize().clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                ) { model.select(spot) },
            )
            if (chosen && widget is ComposedWidget.Image) ImageHandle(widget, spot, model)
        }
        // Ручка — для мыши: тянуть сразу, без долгого нажатия.
        Caption(
            "⠿",
            fontSize = 18.sp,
            color = colors.text3,
            modifier = Modifier.padding(start = 4.dp)
                .draggable(drag, spot, { corner }, onDrop, { model.doc.items.size }, longPress = false, intoCells, intoLists)
                .testTag(EditorTags.spot(spot) + ".handle"),
        )
    }
}

/**
 * Ручка ширины картинки (заказчик 2026-10-21): у края выбранной картинки — тянуть пальцем или мышью,
 * доля ширины места ступенями по 5 %, от 20 до 100. Край — по выравниванию содержимого: слева —
 * правый край, по центру — правый (вдвое быстрее), справа — левый.
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.ImageHandle(widget: ComposedWidget.Image, spot: Spot, model: EditorModel) {
    val colors = Tima.colors
    val current by androidx.compose.runtime.rememberUpdatedState(widget)
    val item = model.doc.items.getOrNull(spot.item)
    val align = when (item) {
        is ComposedItem.Row -> item.cells.getOrNull(spot.cell)?.style?.get("align") ?: item.style["align"]
        else -> item?.style?.get("align")
    }
    // Ширина места — по факту, после раскладки: BoxWithConstraints здесь нельзя — ячейка ряда меряет
    // высоту заранее (intrinsic), и он падал (Samsung 193, 2026-10-21: «картинка в ряд — вылетает»).
    var full by remember { mutableStateOf(0f) }
    Box(Modifier.matchParentSize().onSizeChanged { full = it.width.toFloat() }) {
        val w = full * current.share / 100f
        val (edge, scale) = when (align) {
            "center" -> (full + w) / 2f to 2f
            "end" -> (full - w) to -1f
            else -> w to 1f
        }
        val half = with(androidx.compose.ui.platform.LocalDensity.current) { 9.dp.toPx() }
        var moved by remember { mutableStateOf(0f) }
        if (full > 0f) Box(
            Modifier.offset { IntOffset((edge - half).roundToInt(), 0) }
                .width(18.dp).fillMaxHeight()
                .pointerInput(spot) {
                    detectHorizontalDragGestures(onDragEnd = { moved = 0f }) { change, dx ->
                        change.consume()
                        moved += dx * scale
                        val step = full * 5f / 100f
                        val steps = (moved / step).toInt()
                        if (steps != 0 && full > 0f) {
                            moved -= steps * step
                            val next = (current.share + steps * 5).coerceIn(20, 100)
                            if (next != current.share) model.layout { d, s -> withWidget(d, s, current.copy(share = next)) to s }
                        }
                    }
                }
                .testTag(EditorTags.spot(spot) + ".width"),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.width(6.dp).fillMaxHeight().padding(vertical = 8.dp).background(colors.navigation, RoundedCornerShape(3.dp)))
        }
    }
}

/**
 * Ряд в редакторе — та же сетка, что у получателя ([SpanGrid]): строки × столбцы, слитые ячейки
 * прямоугольником. Ряд выбран — между столбцами рамки: тянуть пальцем или мышью, ширина
 * перераспределяется между двумя соседними ступенями по 5 % (заказчик 2026-10-18). Не помещается —
 * красная рамка, отправить нельзя.
 */
@Composable
internal fun EditorRow(
    row: ComposedItem.Row,
    index: Int,
    model: EditorModel,
    drag: EditorDrag,
    marks: TypingMarks?,
    preview: (ComposedDoc) -> RichContent?,
    tooWide: Boolean = false,
) {
    val colors = Tima.colors
    val spot = model.spot
    val look = blockStyleOf(row.style)
    val columns = row.columns.coerceAtLeast(1)
    val widths = row.cells.take(columns).map { it.width }
    val spans = remember(row) { ComposedLayout.spans(row) }
    val gap = 4.dp
    val gapPx = with(LocalDensity.current) { gap.toPx() }
    Box(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .then(
                when {
                    tooWide -> Modifier.border(2.dp, colors.alarm, RoundedCornerShape(8.dp))
                    spot == Spot(index) -> Modifier.border(2.dp, colors.navigation, RoundedCornerShape(8.dp))
                    else -> Modifier
                },
            )
            .styled(look)
            .testTag(EditorTags.item(index)),
    ) {
        // Ширина известна сразу (не после первого кадра): рамки встают на место в том же кадре.
        // Ряд редактора никто не спрашивает «какой ты высоты по содержимому» — подсостав здесь можно.
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gridPx = constraints.maxWidth
            Column(Modifier.fillMaxWidth()) {
                // Рамки — линейкой над рядом, как в Word: не режут слитые ячейки и не перехватывают
                // нажатия по ячейкам. Только у выбранного ряда.
                if (spot.item == index && columns > 1 && gridPx > 0) {
                    Box(Modifier.fillMaxWidth().height(RULER).testTag(EditorTags.ruler(index))) {
                        val free = gridPx - gapPx * (columns - 1)
                        for (boundary in 1 until columns) {
                            val x = free * widths.take(boundary).sum() / 100f + gapPx * (boundary - 1) + gapPx / 2
                            ColumnBorder(x, boundary, gridPx, index, model)
                        }
                    }
                }
                SpanGrid(columns, widths, spans, gap, Modifier.fillMaxWidth()) { c ->
                    val (w, _) = spans[c]
                    val percent = (c % columns until c % columns + w).sumOf { widths.getOrElse(it) { 0 } }
                    EditorCell(row.cells[c], index, c, percent, model, drag, marks, preview, Modifier, look)
                }
            }
        }
    }
    DividerAfter(look)
}

/** Рамка между столбцами [boundary] − 1 и [boundary]: тянуть — ширина ступенями по 5 %. */
@Composable
private fun ColumnBorder(x: Float, boundary: Int, gridPx: Int, item: Int, model: EditorModel) {
    val colors = Tima.colors
    val width by rememberUpdatedState(gridPx)
    val half = with(LocalDensity.current) { 12.dp.toPx() }
    Box(
        Modifier.offset { IntOffset((x - half).roundToInt(), 0) }.width(24.dp).fillMaxHeight()
            .pointerInput(item, boundary) {
                var moved = 0f
                detectHorizontalDragGestures(onDragStart = { moved = 0f }) { change, dx ->
                    change.consume()
                    moved += dx
                    val step = width * ComposedLayout.STEP / 100f
                    if (step > 0f) {
                        val steps = (moved / step).toInt()
                        if (steps != 0) {
                            model.resize(item, boundary, steps * ComposedLayout.STEP)
                            moved -= steps * step
                        }
                    }
                }
            }
            .testTag(EditorTags.border(item, boundary)),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(6.dp).fillMaxHeight().padding(vertical = 2.dp).background(colors.navigation, RoundedCornerShape(3.dp)))
    }
}

/** Высота линейки рамок над выбранным рядом. */
private val RULER = 22.dp

@Composable
private fun EditorCell(
    cell: ComposedCell,
    index: Int,
    c: Int,
    percent: Int,
    model: EditorModel,
    drag: EditorDrag,
    marks: TypingMarks?,
    preview: (ComposedDoc) -> RichContent?,
    modifier: Modifier,
    rowLook: io.tima.domain.chat.BlockStyle = io.tima.domain.chat.BlockStyle.NONE,
) {
    val colors = Tima.colors
    val spot = model.spot
    val cellChosen = spot.item == index && spot.cell == c
    val textSpot = Spot(index, c, Spot.TEXT)
    // Цвет, размер и выравнивание ряда — ячейке, если у неё своих нет; как у получателя.
    val look = blockStyleOf(cell.style).over(rowLook.copy(background = null, padding = null, divider = null, dividerColor = null))
    val border = when {
        drag.over(index, c) -> colors.confirmed
        cellChosen && spot.widget == -1 -> colors.navigation
        else -> colors.line
    }
    Column(
        modifier.fillMaxSize()
            .border(if (cellChosen || drag.over(index, c)) 2.dp else 1.dp, border, RoundedCornerShape(6.dp))
            .onGloballyPositioned { drag.cell(index, c, it.boundsInRoot()) }
            .clickable { model.select(Spot(index, c)) }
            .padding(4.dp)
            .styled(look)
            .testTag(EditorTags.cell(index, c)),
        verticalArrangement = when (look.valign) {
            "center" -> Arrangement.Center
            "bottom" -> Arrangement.Bottom
            else -> Arrangement.Top
        },
    ) {
        Caption("$percent%", fontSize = 9.sp, color = colors.text3)
        val shown = model.typedAt(textSpot)
        val value = if (spot == textSpot) model.field else TextFieldValue(shown.text)
        // Курсор просили сюда (новый ряд, 2026-10-21) — встать в текст ячейки.
        val cellFocus = remember { androidx.compose.ui.focus.FocusRequester() }
        androidx.compose.runtime.LaunchedEffect(model.focusAsk) {
            if (model.focusAsk > 0 && model.spot == textSpot) runCatching { cellFocus.requestFocus() }
        }
        BasicTextField(
            value = value,
            onValueChange = { v -> model.changedCell(index, c, v) },
            textStyle = TextStyle(
                fontSize = sizeOf(look.size, TimaType.sz5),
                color = io.tima.core.ui.paletteColor(look.color) ?: colors.text,
                textAlign = alignOf(look.align),
            ),
            cursorBrush = SolidColor(colors.navigation),
            visualTransformation = rememberTypedView(shown, marks, open = false, color = io.tima.core.ui.paletteColor(look.color)),
            modifier = Modifier.fillMaxWidth().heightIn(min = 28.dp)
                .focusRequester(cellFocus)
                .onFocusChanged { if (it.isFocused) model.focusedCell(index, c) }
                .testTag(EditorTags.cellText(index, c)),
        )
        // Написанное в ячейке — виджет «Текст» с ручкой: его можно унести в другую ячейку (§10.3).
        if (cell.text.isNotBlank()) {
            var corner by remember { mutableStateOf(Offset.Zero) }
            Caption(
                "⠿",
                fontSize = 14.sp,
                color = colors.text3,
                modifier = Modifier.align(Alignment.End)
                    .onGloballyPositioned { corner = it.boundsInRoot().topLeft }
                    .draggable(drag, textSpot, { corner }, { from, to -> model.drop(from, to) }, { model.doc.items.size }, longPress = false)
                    .testTag(EditorTags.cellText(index, c) + ".handle"),
            )
        }
        cell.widgets.forEachIndexed { w, widget ->
            EditorWidget(widget, Spot(index, c, w), spot == Spot(index, c, w), model, drag, preview)
        }
    }
}

/** Переносимое под пальцем — тень, чтобы было видно, что тянется. */
@Composable
internal fun DragGhost(drag: EditorDrag) {
    if (drag.from == null) return
    val colors = Tima.colors
    val p = drag.at - drag.origin
    Box(
        Modifier.offset { IntOffset(p.x.roundToInt() - 20, p.y.roundToInt() - 20) }
            .size(40.dp)
            .background(colors.navigation.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .testTag("editor:drag"),
        contentAlignment = Alignment.Center,
    ) {
        Caption("⠿", fontSize = 18.sp, color = colors.onAccent)
    }
}
