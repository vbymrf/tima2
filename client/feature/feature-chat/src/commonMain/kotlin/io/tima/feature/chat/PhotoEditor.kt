package io.tima.feature.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.tima.core.media.PhotoStep
import io.tima.core.media.PhotoSteps
import io.tima.core.ui.Button
import io.tima.core.ui.ButtonKind
import io.tima.core.ui.Tima
import io.tima.core.ui.words

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
    const val THIN = "photo.edit.thin"
    const val THICK = "photo.edit.thick"
    const val IMAGE = "photo.edit.image"
    fun color(i: Int) = "photo.edit.color.$i"
}

private enum class Tool { None, Crop, Brush, Arrow }

/** Краска кисти и стрелки — часть самого фото, не темы: [PhotoSteps.INKS]. */
private val INKS = PhotoSteps.INKS

/** Толщина — доля большей стороны картинки. */
private const val THIN = 0.006f
private const val THICK = 0.016f

/**
 * Редактор фото (заказчик 2026-10-22, ПЛАН-(ИФ) ИФ6): поворот, обрезка, кисть, стрелка; цвет и
 * толщина; «Отменить» снимает последний шаг. «Сохранить» отдаёт готовую картинку — шагов после него
 * нет, прежних состояний редактор не хранит. Отправляет не редактор, а просмотр, когда его закроют.
 *
 * Обрезка, кисть, стрелка — движением пальца (мыши) по картинке: обрезка — прямоугольник от точки до
 * точки, кисть — след, стрелка — от начала к острию.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhotoEditor(source: ImageBitmap, onSave: (ImageBitmap) -> Unit, onCancel: () -> Unit) {
    val colors = Tima.colors
    val w = Tima.words.chat
    val steps = remember(source) { mutableStateListOf<PhotoStep>() }
    var tool by remember { mutableStateOf(Tool.None) }
    var ink by remember { mutableStateOf(0) }
    var thick by remember { mutableStateOf(false) }
    // Ведомое пальцем — в долях картинки; шагом становится, когда палец отпущен.
    val live = remember { mutableStateListOf<Offset>() }
    val image = remember(source, steps.size) { PhotoSteps.apply(source, steps.toList()) }

    Column(Modifier.fillMaxSize().background(colors.viewer).testTag(PhotoEditorTags.WINDOW)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Button(w.photoCancel, onCancel, Modifier.testTag(PhotoEditorTags.CANCEL), kind = ButtonKind.Quiet)
            Button(w.photoSave, { onSave(image) }, Modifier.testTag(PhotoEditorTags.SAVE))
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
            val density = LocalDensity.current
            val boxW = constraints.maxWidth.toFloat()
            val boxH = constraints.maxHeight.toFloat()
            val k = minOf(boxW / image.width, boxH / image.height)
            val shownW = image.width * k
            val shownH = image.height * k
            val sizeDp = with(density) { Size(shownW.toDp().value, shownH.toDp().value) }
            fun share(p: Offset) = Offset((p.x / shownW).coerceIn(0f, 1f), (p.y / shownH).coerceIn(0f, 1f))
            Box(
                Modifier.size(sizeDp.width.dp, sizeDp.height.dp).testTag(PhotoEditorTags.IMAGE)
                    .pointerInput(tool, ink, thick, image) {
                        if (tool == Tool.None) return@pointerInput
                        detectDragGestures(
                            onDragStart = { at -> live.clear(); live.add(share(at)) },
                            onDragEnd = {
                                val pts = live.toList()
                                live.clear()
                                val width = if (thick) THICK else THIN
                                val step = when (tool) {
                                    Tool.Crop -> pts.takeIf { it.size > 1 }?.let { PhotoStep.Crop(it.first().x, it.first().y, it.last().x, it.last().y) }
                                        ?.takeIf { kotlin.math.abs(it.right - it.left) > 0.05f && kotlin.math.abs(it.bottom - it.top) > 0.05f }
                                    Tool.Brush -> pts.takeIf { it.isNotEmpty() }?.let { PhotoStep.Brush(it, INKS[ink], width) }
                                    Tool.Arrow -> pts.takeIf { it.size > 1 }?.let { PhotoStep.Arrow(it.first(), it.last(), INKS[ink], width) }
                                    Tool.None -> null
                                }
                                if (step != null) steps.add(step)
                            },
                            onDragCancel = { live.clear() },
                        ) { change, _ ->
                            change.consume()
                            live.add(share(change.position))
                        }
                    },
            ) {
                Image(image, contentDescription = null, modifier = Modifier.fillMaxSize())
                // Ведомое пальцем — поверх картинки, пока не отпущено.
                val frame = colors.navigation
                Canvas(Modifier.fillMaxSize()) {
                    val pts = live.map { Offset(it.x * size.width, it.y * size.height) }
                    if (pts.isEmpty()) return@Canvas
                    val px = (if (thick) THICK else THIN) * maxOf(size.width, size.height) * 1f
                    when (tool) {
                        Tool.Crop -> if (pts.size > 1) {
                            val a = pts.first()
                            val b = pts.last()
                            drawRect(
                                frame,
                                topLeft = Offset(minOf(a.x, b.x), minOf(a.y, b.y)),
                                size = Size(kotlin.math.abs(b.x - a.x), kotlin.math.abs(b.y - a.y)),
                                style = Stroke(2.dp.toPx()),
                            )
                        }
                        Tool.Brush -> {
                            val path = Path()
                            pts.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
                            drawPath(path, INKS[ink], style = Stroke(px.coerceAtLeast(1f), cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                        Tool.Arrow -> if (pts.size > 1) drawLine(INKS[ink], pts.first(), pts.last(), px.coerceAtLeast(1f), StrokeCap.Round)
                        Tool.None -> Unit
                    }
                }
            }
        }
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(w.photoRotate, { steps.add(PhotoStep.Turn) }, Modifier.testTag(PhotoEditorTags.TURN), kind = ButtonKind.Quiet)
            ToolButton(w.photoCrop, tool == Tool.Crop, PhotoEditorTags.CROP) { tool = if (tool == Tool.Crop) Tool.None else Tool.Crop }
            ToolButton(w.photoBrush, tool == Tool.Brush, PhotoEditorTags.BRUSH) { tool = if (tool == Tool.Brush) Tool.None else Tool.Brush }
            ToolButton(w.photoArrow, tool == Tool.Arrow, PhotoEditorTags.ARROW) { tool = if (tool == Tool.Arrow) Tool.None else Tool.Arrow }
            Button(w.photoUndo, { if (steps.isNotEmpty()) steps.removeAt(steps.lastIndex) }, Modifier.testTag(PhotoEditorTags.UNDO), kind = ButtonKind.Quiet, enabled = steps.isNotEmpty())
        }
        if (tool == Tool.Brush || tool == Tool.Arrow) {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                INKS.forEachIndexed { i, c ->
                    Box(
                        Modifier.size(32.dp).background(c, CircleShape)
                            .border(if (i == ink) 3.dp else 1.dp, if (i == ink) colors.navigation else colors.text3, CircleShape)
                            .clickable { ink = i }.testTag(PhotoEditorTags.color(i)),
                    )
                }
                Box(Modifier.weight(1f))
                ToolButton(w.photoThin, !thick, PhotoEditorTags.THIN) { thick = false }
                ToolButton(w.photoThick, thick, PhotoEditorTags.THICK) { thick = true }
            }
        }
    }
}

@Composable
private fun ToolButton(label: String, on: Boolean, tag: String, onClick: () -> Unit) {
    Button(label, onClick, Modifier.testTag(tag), kind = if (on) ButtonKind.Action else ButtonKind.Quiet)
}
