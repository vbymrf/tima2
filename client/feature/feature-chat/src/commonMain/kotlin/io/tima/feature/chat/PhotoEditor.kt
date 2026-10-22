package io.tima.feature.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.tima.core.media.PhotoStep
import io.tima.core.media.PhotoSteps
import io.tima.core.ui.Caption
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaType
import io.tima.core.ui.words
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Метки редактора фото (СЦЕНАРИИ-ПО-ОКНАМ, «Фото»). */
object PhotoEditorTags {
    const val WINDOW = "photo.edit"
    const val CANCEL = "photo.edit.cancel"
    const val SAVE = "photo.edit.save"
    const val TURN = "photo.edit.turn"
    const val CROP = "photo.edit.crop"
    const val BRUSH = "photo.edit.brush"
    const val ARROW = "photo.edit.arrow"
    const val UNDO = "photo.edit.undo"
    const val REDO = "photo.edit.redo"
    const val APPLY = "photo.edit.apply"
    const val ABOUT = "photo.edit.about"
    const val THIN = "photo.edit.thin"
    const val THICK = "photo.edit.thick"
    const val IMAGE = "photo.edit.image"
    fun color(i: Int) = "photo.edit.color.$i"
}

private enum class Tool { None, Crop, Brush, Arrow }

/** Толщина — доля большей стороны картинки. */
private const val THIN = 0.006f
private const val THICK = 0.016f

/** Рамка обрезки не меньше этой доли стороны. */
private const val MIN_CROP = 0.05f

/**
 * Редактор фото (заказчик 2026-10-22, ПЛАН-(ИФ) ИФ6; вид — правки того же дня).
 *
 * - **Внизу закреплены значки**: «Повернуть», «Обрезать», «Кисть», «Стрелка». Ряд не сдвигается: что
 *   появляется при выборе (пояснение, цвета, «Выполнить»), встаёт **над** ним.
 * - «Повернуть» — сразу, без подтверждения. Обрезка, кисть, стрелка — нарисовал, нажал «Выполнить» —
 *   осталось; не выполнено — при смене инструмента пропадает. «Сохранить» выполняет и несохранённое.
 * - Сверху — «Отмена», «Отменить», «Повторить», «Сохранить».
 * - Кисть и стрелку можно начинать **за краем фото**: след идёт по фото, палец — где угодно. Рамку
 *   обрезки рисуют, а потом тянут за любой угол.
 * - Закрытия смахиванием нет.
 *
 * Отправляет не редактор, а просмотр, когда его закроют. Прежних состояний после «Сохранить» нет.
 */
@Composable
fun PhotoEditor(source: ImageBitmap, onSave: (ImageBitmap) -> Unit, onCancel: () -> Unit) {
    val colors = Tima.colors
    val w = Tima.words.chat
    val steps = remember(source) { mutableStateListOf<PhotoStep>() }
    val undone = remember(source) { mutableStateListOf<PhotoStep>() }
    var tool by remember { mutableStateOf(Tool.None) }
    var ink by remember { mutableStateOf(0) }
    var thick by remember { mutableStateOf(false) }
    // Нарисованное, но не выполненное: следы кисти и стрелки, рамка обрезки — в долях картинки.
    val pending = remember { mutableStateListOf<PhotoStep>() }
    var crop by remember { mutableStateOf<Rect?>(null) }
    // Ведомое пальцем прямо сейчас.
    val live = remember { mutableStateListOf<Offset>() }
    val image = remember(source, steps.size) { PhotoSteps.apply(source, steps.toList()) }
    val drawn = remember(image, pending.size) { PhotoSteps.apply(image, pending.toList()) }

    fun commit(step: PhotoStep) {
        steps.add(step)
        undone.clear()
    }
    fun apply() {
        when (tool) {
            Tool.Crop -> crop?.let { commit(PhotoStep.Crop(it.left, it.top, it.right, it.bottom)) }
            Tool.Brush, Tool.Arrow -> pending.toList().forEach(::commit)
            Tool.None -> Unit
        }
        pending.clear()
        crop = null
    }
    fun choose(next: Tool) {
        // Не выполненное пропадает: «нарисовал, нажал „Выполнить“ — осталось».
        pending.clear()
        crop = null
        tool = if (tool == next) Tool.None else next
    }
    fun undo() {
        when {
            pending.isNotEmpty() -> pending.removeAt(pending.lastIndex)
            crop != null -> crop = null
            steps.isNotEmpty() -> undone.add(steps.removeAt(steps.lastIndex))
        }
    }
    fun redo() {
        if (undone.isNotEmpty()) steps.add(undone.removeAt(undone.lastIndex))
    }
    val canUndo = pending.isNotEmpty() || crop != null || steps.isNotEmpty()

    Column(Modifier.fillMaxSize().background(colors.viewer).testTag(PhotoEditorTags.WINDOW)) {
        // ── Сверху: отмена всего, шаг назад и вперёд, сохранить ─────────────
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Pill(w.photoCancel, PhotoEditorTags.CANCEL, onClick = onCancel)
            Box(Modifier.weight(1f))
            IconCircle(Glyph.Undo, PhotoEditorTags.UNDO, enabled = canUndo, onClick = ::undo)
            IconCircle(Glyph.Redo, PhotoEditorTags.REDO, enabled = undone.isNotEmpty(), onClick = ::redo)
            Box(Modifier.weight(1f))
            Pill(w.photoSave, PhotoEditorTags.SAVE, strong = true) {
                apply()
                onSave(PhotoSteps.apply(source, steps.toList()))
            }
        }

        // ── Картинка: рисовать можно и за её краем ────────────────────────
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(12.dp)) {
            val density = LocalDensity.current
            val boxW = constraints.maxWidth.toFloat()
            val boxH = constraints.maxHeight.toFloat()
            val k = min(boxW / drawn.width, boxH / drawn.height)
            val shownW = drawn.width * k
            val shownH = drawn.height * k
            val left = (boxW - shownW) / 2
            val top = (boxH - shownH) / 2
            val handle = with(density) { 28.dp.toPx() }
            fun share(p: Offset) = Offset((p.x - left) / shownW, (p.y - top) / shownH)
            fun clamped(p: Offset) = Offset(p.x.coerceIn(0f, 1f), p.y.coerceIn(0f, 1f))
            Box(
                Modifier.fillMaxSize().pointerInput(tool, ink, thick, shownW, shownH) {
                    if (tool == Tool.None) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val start = share(down.position)
                        // Рамка: за угол — тянуть угол; иначе — новая рамка.
                        var corner = -1
                        crop?.takeIf { tool == Tool.Crop }?.let { r ->
                            val corners = listOf(r.topLeft, r.topRight, r.bottomRight, r.bottomLeft)
                            corner = corners.indexOfFirst { c ->
                                abs((c.x - start.x) * shownW) < handle && abs((c.y - start.y) * shownH) < handle
                            }
                        }
                        live.clear()
                        if (corner < 0) live.add(start)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            if (change.positionChange() != Offset.Zero) change.consume()
                            val at = share(change.position)
                            val r = crop
                            if (corner >= 0 && r != null) {
                                val p = clamped(at)
                                crop = when (corner) {
                                    0 -> Rect(min(p.x, r.right - MIN_CROP), min(p.y, r.bottom - MIN_CROP), r.right, r.bottom)
                                    1 -> Rect(r.left, min(p.y, r.bottom - MIN_CROP), max(p.x, r.left + MIN_CROP), r.bottom)
                                    2 -> Rect(r.left, r.top, max(p.x, r.left + MIN_CROP), max(p.y, r.top + MIN_CROP))
                                    else -> Rect(min(p.x, r.right - MIN_CROP), r.top, r.right, max(p.y, r.top + MIN_CROP))
                                }
                            } else {
                                live.add(at)
                            }
                        }
                        val pts = live.toList()
                        live.clear()
                        if (corner >= 0) return@awaitEachGesture
                        val width = if (thick) THICK else THIN
                        when (tool) {
                            Tool.Crop -> if (pts.size > 1) {
                                val a = clamped(pts.first())
                                val b = clamped(pts.last())
                                if (abs(b.x - a.x) > MIN_CROP && abs(b.y - a.y) > MIN_CROP) {
                                    crop = Rect(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))
                                }
                            }
                            Tool.Brush -> pending.add(PhotoStep.Brush(pts, PhotoSteps.INKS[ink], width))
                            Tool.Arrow -> if (pts.size > 1) pending.add(PhotoStep.Arrow(pts.first(), pts.last(), PhotoSteps.INKS[ink], width))
                            Tool.None -> Unit
                        }
                    }
                },
            ) {
                Image(
                    drawn,
                    contentDescription = null,
                    modifier = Modifier.offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                        .size(with(density) { shownW.toDp() }, with(density) { shownH.toDp() })
                        .testTag(PhotoEditorTags.IMAGE),
                )
                val frame = colors.navigation
                val shade = colors.viewer.copy(alpha = 0.6f)
                Canvas(Modifier.fillMaxSize()) {
                    fun px(p: Offset) = Offset(left + p.x * shownW, top + p.y * shownH)
                    crop?.let { r -> cropFrame(Rect(px(r.topLeft), px(r.bottomRight)), Rect(left, top, left + shownW, top + shownH), frame, shade, handle) }
                    val pts = live.map(::px)
                    if (pts.isEmpty()) return@Canvas
                    val stroke = ((if (thick) THICK else THIN) * max(shownW, shownH)).coerceAtLeast(2f)
                    when (tool) {
                        Tool.Crop -> if (pts.size > 1) {
                            val a = pts.first()
                            val b = pts.last()
                            drawRect(frame, Offset(min(a.x, b.x), min(a.y, b.y)), Size(abs(b.x - a.x), abs(b.y - a.y)), style = Stroke(2.dp.toPx()))
                        }
                        Tool.Brush -> {
                            val path = Path()
                            pts.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
                            drawPath(path, PhotoSteps.INKS[ink], style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                        Tool.Arrow -> if (pts.size > 1) drawLine(PhotoSteps.INKS[ink], pts.first(), pts.last(), stroke, StrokeCap.Round)
                        Tool.None -> Unit
                    }
                }
            }
        }

        // ── Над значками: пояснение, цвета, «Выполнить» ──────────────────
        if (tool != Tool.None) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp).background(colors.surface, RoundedCornerShape(12.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Caption(
                    when (tool) {
                        Tool.Crop -> w.photoCropAbout
                        Tool.Brush -> w.photoBrushAbout
                        else -> w.photoArrowAbout
                    },
                    fontSize = TimaType.sz5,
                    color = colors.text,
                    modifier = Modifier.testTag(PhotoEditorTags.ABOUT),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (tool == Tool.Brush || tool == Tool.Arrow) {
                        PhotoSteps.INKS.forEachIndexed { i, c ->
                            Box(
                                Modifier.size(28.dp).background(c, CircleShape)
                                    .border(if (i == ink) 3.dp else 1.dp, if (i == ink) colors.navigation else colors.text3, CircleShape)
                                    .clickable { ink = i }.testTag(PhotoEditorTags.color(i)),
                            )
                        }
                    }
                    Box(Modifier.weight(1f))
                    val ready = if (tool == Tool.Crop) crop != null else pending.isNotEmpty()
                    Pill(w.photoApply, PhotoEditorTags.APPLY, strong = true, enabled = ready, onClick = ::apply)
                }
                if (tool == Tool.Brush || tool == Tool.Arrow) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconCircle(Glyph.Thin, PhotoEditorTags.THIN, selected = !thick, size = 36) { thick = false }
                        IconCircle(Glyph.Thick, PhotoEditorTags.THICK, selected = thick, size = 36) { thick = true }
                    }
                }
            }
        }

        // ── Значки — внизу и на месте ─────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            ToolIcon(Glyph.Turn, w.photoRotate, PhotoEditorTags.TURN, selected = false) {
                choose(Tool.None)
                tool = Tool.None
                commit(PhotoStep.Turn)
            }
            ToolIcon(Glyph.Crop, w.photoCrop, PhotoEditorTags.CROP, selected = tool == Tool.Crop) { choose(Tool.Crop) }
            ToolIcon(Glyph.Brush, w.photoBrush, PhotoEditorTags.BRUSH, selected = tool == Tool.Brush) { choose(Tool.Brush) }
            ToolIcon(Glyph.Arrow, w.photoArrow, PhotoEditorTags.ARROW, selected = tool == Tool.Arrow) { choose(Tool.Arrow) }
        }
    }
}

/** Рамка обрезки: вне её — притемнено, по углам — ручки. */
private fun DrawScope.cropFrame(r: Rect, image: Rect, frame: Color, shade: Color, handle: Float) {
    drawRect(shade, image.topLeft, Size(image.width, r.top - image.top))
    drawRect(shade, Offset(image.left, r.bottom), Size(image.width, image.bottom - r.bottom))
    drawRect(shade, Offset(image.left, r.top), Size(r.left - image.left, r.height))
    drawRect(shade, Offset(r.right, r.top), Size(image.right - r.right, r.height))
    drawRect(frame, r.topLeft, r.size, style = Stroke(2.dp.toPx()))
    val arm = handle * 0.7f
    val sw = 4.dp.toPx()
    for ((c, dx, dy) in listOf(Triple(r.topLeft, 1f, 1f), Triple(r.topRight, -1f, 1f), Triple(r.bottomRight, -1f, -1f), Triple(r.bottomLeft, 1f, -1f))) {
        drawLine(frame, c, Offset(c.x + dx * arm, c.y), sw, StrokeCap.Round)
        drawLine(frame, c, Offset(c.x, c.y + dy * arm), sw, StrokeCap.Round)
    }
}

/** Значки редактора и просмотра — рисуются, а не шрифтом: знаков вроде «⟳» в шрифте может не быть. */
internal enum class Glyph { Turn, Crop, Brush, Arrow, Undo, Redo, Thin, Thick, Edit }

@Composable
private fun ToolIcon(glyph: Glyph, label: String, tag: String, selected: Boolean, onClick: () -> Unit) {
    val colors = Tima.colors
    Column(
        Modifier.clickable(onClick = onClick).testTag(tag).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconCircle(glyph, null, selected = selected, onClick = onClick)
        Caption(
            label,
            fontSize = TimaType.sz5,
            weight = FontWeight.Bold,
            color = colors.text,
            modifier = Modifier.background(colors.surface, RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** Значок в круге — сплошной фон темы, без прозрачности: на тёмном виден всегда. */
@Composable
internal fun IconCircle(
    glyph: Glyph,
    tag: String?,
    selected: Boolean = false,
    enabled: Boolean = true,
    size: Int = 48,
    onClick: () -> Unit,
) {
    val colors = Tima.colors
    val back = if (selected) colors.navigation else colors.surface
    val ink = when {
        !enabled -> colors.text3
        selected -> colors.surface
        else -> colors.text
    }
    Box(
        Modifier.size(size.dp).background(back, CircleShape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size((size * 0.55f).dp)) { glyph(glyph, ink) }
    }
}

/** Кнопка-слово на сплошном фоне; [strong] — главная (зелёная). */
@Composable
private fun Pill(label: String, tag: String, strong: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = Tima.colors
    Caption(
        label,
        fontSize = TimaType.sz4,
        weight = FontWeight.Bold,
        color = if (!enabled) colors.text3 else if (strong) colors.surface else colors.text,
        modifier = Modifier.background(if (strong && enabled) colors.navigation else colors.surface, RoundedCornerShape(24.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp).testTag(tag),
    )
}

private fun DrawScope.glyph(g: Glyph, c: Color) {
    val s = size.minDimension
    val sw = s * 0.11f
    val o = Offset((size.width - s) / 2, (size.height - s) / 2)
    fun p(x: Float, y: Float) = Offset(o.x + x * s, o.y + y * s)
    fun line(a: Offset, b: Offset, width: Float = sw) = drawLine(c, a, b, width, StrokeCap.Round)
    fun head(tip: Offset, angle: Float, len: Float = s * 0.28f) {
        for (side in listOf(-1f, 1f)) {
            val back = angle + PI.toFloat() + side * 0.6f
            line(tip, Offset(tip.x + len * cos(back), tip.y + len * sin(back)))
        }
    }
    when (g) {
        Glyph.Turn -> {
            // Дуга по часовой и остриё на её конце.
            drawArc(c, -60f, 300f, false, p(0.12f, 0.12f), Size(s * 0.76f, s * 0.76f), style = Stroke(sw, cap = StrokeCap.Round))
            val a = (240f) * PI.toFloat() / 180f
            val tip = Offset(o.x + s * 0.5f + s * 0.38f * cos(a), o.y + s * 0.5f + s * 0.38f * sin(a))
            head(tip, a + PI.toFloat() / 2)
        }
        Glyph.Crop -> {
            line(p(0.25f, 0.0f), p(0.25f, 0.75f)); line(p(0.25f, 0.75f), p(1.0f, 0.75f))
            line(p(0.0f, 0.25f), p(0.75f, 0.25f)); line(p(0.75f, 0.25f), p(0.75f, 1.0f))
        }
        Glyph.Brush -> {
            val path = Path().apply {
                moveTo(o.x + s * 0.05f, o.y + s * 0.7f)
                cubicTo(o.x + s * 0.3f, o.y + s * 0.2f, o.x + s * 0.5f, o.y + s * 1.0f, o.x + s * 0.95f, o.y + s * 0.3f)
            }
            drawPath(path, c, style = Stroke(sw, cap = StrokeCap.Round))
        }
        Glyph.Arrow -> {
            val a = p(0.1f, 0.9f)
            val b = p(0.9f, 0.1f)
            line(a, b)
            head(b, atan2(b.y - a.y, b.x - a.x))
        }
        Glyph.Undo, Glyph.Redo -> {
            // Полукруглая стрелка: «Отменить» — влево, «Повторить» — вправо.
            val left = g == Glyph.Undo
            drawArc(c, 180f, 180f, false, p(0.15f, 0.3f), Size(s * 0.7f, s * 0.6f), style = Stroke(sw, cap = StrokeCap.Round))
            val tip = if (left) p(0.15f, 0.6f) else p(0.85f, 0.6f)
            head(tip, PI.toFloat() / 2, s * 0.25f)
        }
        Glyph.Thin -> line(p(0.1f, 0.5f), p(0.9f, 0.5f), s * 0.08f)
        Glyph.Thick -> line(p(0.1f, 0.5f), p(0.9f, 0.5f), s * 0.28f)
        Glyph.Edit -> {
            // Карандаш: стержень по диагонали и остриё.
            line(p(0.25f, 0.75f), p(0.85f, 0.15f), s * 0.2f)
            line(p(0.1f, 0.9f), p(0.22f, 0.78f), s * 0.1f)
        }
    }
}
