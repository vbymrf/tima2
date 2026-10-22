package io.tima.feature.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.tima.core.media.PhotoLayers
import io.tima.core.media.PhotoMark
import io.tima.core.media.PhotoMarks
import io.tima.core.media.TextFace
import io.tima.core.media.drawPhotoLayers
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
    const val TEXT = "photo.edit.text"
    const val TEXT_FIELD = "photo.edit.text.field"
    const val TEXT_INK = "photo.edit.text.ink"
    const val TEXT_BACK = "photo.edit.text.back"
    const val NO_COLOR = "photo.edit.color.none"
    fun face(f: TextFace) = "photo.edit.face.${f.name.lowercase()}"
    const val UNDO = "photo.edit.undo"
    const val REDO = "photo.edit.redo"
    const val APPLY = "photo.edit.apply"
    const val ABOUT = "photo.edit.about"
    const val THIN = "photo.edit.thin"
    const val THICK = "photo.edit.thick"
    const val GLASS_THICK = "photo.edit.glass.thick"
    const val GLASS_WIDE = "photo.edit.glass.wide"
    const val ERASE_SMALL = "photo.edit.erase.small"
    const val ERASE_LARGE = "photo.edit.erase.large"
    const val IMAGE = "photo.edit.image"
    const val PANEL = "photo.edit.panel"
    const val CLOSE = "photo.edit.close"
    fun color(i: Int) = "photo.edit.color.$i"
}

private enum class Tool { None, Crop, Brush, Text }

/**
 * Надпись (заказчик 2026-10-22): нет выбранной рамки; рамка выбрана — набор текста в своей области над
 * клавиатурой; рамка выбрана — положение и размер на фото.
 */
private enum class TextPhase { None, Typing, Placing }

/** Толщина — доля большей стороны того, что видно (повёрнутого и обрезанного фото). */
private const val THIN = 0.006f
private const val THICK = 0.016f

/** Вид пера: сплошное, полупрозрачное (одним слоем), ластик. */
private enum class NibKind { Solid, Glass, Erase }

/**
 * Перо кисти (заказчик 2026-10-22): тонкая и толстая полоса — сплошные; два полупрозрачных — размером с
 * толстую и пошире, как маркер; два ластика — малый и большой, стирают нарисованное (надписи — нет).
 */
private enum class Nib(val width: Float, val kind: NibKind) {
    Thin(THIN, NibKind.Solid),
    Thick(THICK, NibKind.Solid),
    GlassThick(THICK, NibKind.Glass),
    GlassWide(THICK * 2.5f, NibKind.Glass),
    EraseSmall(THICK * 1.5f, NibKind.Erase),
    EraseLarge(THICK * 4f, NibKind.Erase),
}

/** Рамка обрезки не меньше этой доли стороны. */
private const val MIN_CROP = 0.05f

/**
 * Действие редактора — то, что снимают «Отменить» и возвращают «Повторить». Сами действия ничего не
 * впечатывают в фото: из них собирается [PhotoMarks] — поворот, рамка и слой отметок.
 */
private sealed interface Act {
    data object Turn : Act
    /** Новая рамка в долях исходного фото; `null` — без рамки. */
    data class Cut(val rect: Rect?) : Act
    data class Mark(val mark: PhotoMark) : Act
    /** Надпись [id] стала [text]; `null` — убрана (стёрли весь текст). */
    data class Write(val id: Long, val text: PhotoMark.Text?) : Act
}

/** Надписи по номерам — последнее состояние каждой. */
private fun textsOf(acts: List<Act>): Map<Long, PhotoMark.Text> {
    val out = LinkedHashMap<Long, PhotoMark.Text?>()
    for (a in acts) if (a is Act.Write) out[a.id] = a.text
    @Suppress("UNCHECKED_CAST")
    return out.filterValues { it != null } as Map<Long, PhotoMark.Text>
}

/** Стопка из действий; надпись [hide] не берётся — её правят и показывают отдельно. */
private fun marksOf(acts: List<Act>, hide: Long = -1L): PhotoMarks = PhotoMarks(
    turns = acts.count { it == Act.Turn },
    crop = acts.filterIsInstance<Act.Cut>().lastOrNull()?.rect,
    marks = acts.filterIsInstance<Act.Mark>().map { it.mark } + textsOf(acts).filterKeys { it != hide }.values,
)

/**
 * Редактор фото (заказчик 2026-10-22, ПЛАН-(ИФ) ИФ6; вид — правки того же дня).
 *
 * **Слоями** (заказчик 2026-10-22: «наложить сверху наши рисования, как в фотошопе слои… и далее
 * обработка в конце»): фото не трогается; поворот и рамка — для всей стопки, кисть и стрелка — свой слой
 * поверх, в долях исходного фото. Экран показывает стопку на уменьшенной копии; итог сводит вызывающий
 * ([PhotoLayers.render]) — на исходнике нужного размера. Рамку можно поправить и после рисования:
 * рисунки на своём слое не теряются.
 *
 * - **Внизу закреплены значки**: «Повернуть», «Обрезать», «Кисть», «Стрелка». Выбран инструмент —
 *   его окно встаёт **поверх** ряда вместе с подписями и не больше его: «✕» слева (закрыть,
 *   невыполненное пропадает), посередине — цвета и перья (кисть, стрелка) или пояснение (обрезка),
 *   галочка справа — выполнить.
 * - «Повернуть» — сразу, без подтверждения. Обрезка, кисть, стрелка — нарисовал, галочка — осталось;
 *   не выполнено — при «✕» и смене инструмента пропадает. «Сохранить» выполняет и несохранённое.
 * - **Масштаб**: двумя пальцами — увеличить и сдвинуть; одним без инструмента — сдвинуть увеличенное;
 *   двойное нажатие — при любом инструменте. Увеличенное не заходит на кнопки сверху и значки снизу.
 * - Сверху — «Отмена», «Отменить», «Повторить», «Сохранить».
 * - Кисть и стрелку можно начинать **за краем фото**: при выбранном инструменте вокруг фото поле в
 *   36 dp, входящее в область рисования, — палец ложится туда, след идёт по фото. Рамку обрезки рисуют,
 *   а потом тянут за любой угол; при обрезке видно всё фото, прежняя рамка — на месте.
 * - Закрытия смахиванием нет.
 */
@Composable
fun PhotoEditor(
    source: ImageBitmap,
    onSave: (PhotoMarks) -> Unit,
    onCancel: () -> Unit,
    /**
     * Надпись главной кнопки: «Сохранить» у правки отправленного, «Отправить» — у одного фото перед
     * отправкой (заказчик 2026-10-22); «Отмена» тогда отменяет отправку.
     */
    saveLabel: String? = null,
) {
    val colors = Tima.colors
    val w = Tima.words.chat
    val acts = remember(source) { mutableStateListOf<Act>() }
    val undone = remember(source) { mutableStateListOf<Act>() }
    var tool by remember { mutableStateOf(Tool.None) }
    var ink by remember { mutableStateOf(0) }
    var nib by remember { mutableStateOf(Nib.Thin) }
    fun nibOf(@Suppress("UNUSED_PARAMETER") t: Tool) = nib
    val measurer = rememberTextMeasurer()
    // Надпись (заказчик 2026-10-22): выбранная рамка, её состояние, набранное и настройки.
    var phase by remember { mutableStateOf(TextPhase.None) }
    var draft by remember { mutableStateOf<PhotoMark.Text?>(null) }
    var draftId by remember { mutableStateOf(-1L) }
    var draftNew by remember { mutableStateOf(false) }
    var nextTextId by remember { mutableStateOf(1L) }
    var typed by remember { mutableStateOf(TextFieldValue("")) }
    // Переключатель «Текст / Фон»: что задаёт выбранный цвет. Фон по умолчанию — без цвета (-1).
    var paintBack by remember { mutableStateOf(false) }
    var textInk by remember { mutableStateOf(DEFAULT_TEXT_INK) }
    var backInk by remember { mutableStateOf(-1) }
    var face by remember { mutableStateOf(TextFace.Normal) }
    fun restyle() {
        draft = draft?.copy(
            color = PhotoLayers.INKS[textInk],
            background = backInk.takeIf { it >= 0 }?.let { PhotoLayers.INKS[it] },
            face = face,
        )
    }
    /** Цвет следа на экране, пока ведётся: полупрозрачный — как ляжет. */
    fun brushInk() = PhotoLayers.INKS[ink].let { if (nib.kind == NibKind.Glass) it.copy(alpha = PhotoLayers.GLASS_ALPHA) else it }
    // Нарисованное, но не выполненное: следы кисти и стрелки — в долях фото; рамка обрезки — в долях
    // повёрнутого фото целиком (при обрезке оно видно без прежней рамки).
    val pending = remember { mutableStateListOf<PhotoMark>() }
    var crop by remember { mutableStateOf<Rect?>(null) }
    // Ведомое пальцем прямо сейчас — в долях видимого.
    val live = remember { mutableStateListOf<Offset>() }
    val marks = marksOf(acts)
    // При обрезке видно всё (повёрнутое) фото: рамку ставят заново или правят прежнюю. Правимая надпись —
    // из черновика, а не сохранённая.
    val shownMarks = (if (tool == Tool.Crop) marks.copy(crop = null) else marks).let { m ->
        if (draftId < 0) m else m.copy(marks = marksOf(acts, hide = draftId).marks + listOfNotNull(draft?.takeIf { it.text.isNotBlank() && phase == TextPhase.Placing }))
    }
    val out = PhotoLayers.outSize(source.width, source.height, shownMarks)

    fun commit(act: Act) {
        acts.add(act)
        undone.clear()
    }
    fun apply() {
        when (tool) {
            Tool.Crop -> crop?.let { r ->
                val rect = PhotoLayers.unrotate(r, marks.quarter)
                if (rect != marks.crop) commit(Act.Cut(rect))
            }
            Tool.Brush -> pending.toList().forEach { commit(Act.Mark(it)) }
            Tool.Text, Tool.None -> Unit
        }
        pending.clear()
        crop = null
    }
    /** Снять выбор рамки: новая несохранённая надпись пропадает, сохранённая остаётся как была. */
    fun dropText() {
        phase = TextPhase.None
        draft = null
        draftId = -1L
        draftNew = false
    }
    /** Сохранить надпись: пустая — убирается вместе с рамкой. */
    fun saveText() {
        val d = draft?.copy(text = typed.text.trimEnd())
        if (d != null) {
            if (d.text.isNotBlank()) commit(Act.Write(draftId, d)) else if (!draftNew) commit(Act.Write(draftId, null))
        }
        dropText()
    }
    fun choose(next: Tool) {
        // Не выполненное пропадает: «нарисовал, нажал „Выполнить“ — осталось».
        pending.clear()
        dropText()
        tool = if (tool == next) Tool.None else next
        // Обрезка начинается с прежней рамки — её можно поправить.
        crop = if (tool == Tool.Crop) marks.crop?.let { PhotoLayers.rotate(it, marks.quarter) } else null
    }
    fun undo() {
        when {
            pending.isNotEmpty() -> pending.removeAt(pending.lastIndex)
            acts.isNotEmpty() -> undone.add(acts.removeAt(acts.lastIndex))
        }
    }
    fun redo() {
        if (undone.isNotEmpty()) acts.add(undone.removeAt(undone.lastIndex))
    }
    val canUndo = pending.isNotEmpty() || acts.isNotEmpty()

    // Масштаб картинки в редакторе (заказчик 2026-10-22): двумя пальцами — увеличить и сдвинуть; одним —
    // рисовать (инструмент выбран) или сдвигать увеличенное (не выбран).
    var scale by remember(source) { mutableStateOf(1f) }
    var pan by remember(source) { mutableStateOf(Offset.Zero) }

    // Редактор забирает все касания своего экрана: касание в пустом месте (между кнопками, в зазорах ряда
    // значков) проходило в окно просмотра под ним, и то листало на соседнее фото — после «Отмена» или
    // «Сохранить» было открыто другое (заказчик 2026-10-22). Ничего не гасит: кнопкам касания достаются.
    Column(
        Modifier.fillMaxSize().background(colors.viewer)
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
            .testTag(PhotoEditorTags.WINDOW),
    ) {
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
            Pill(saveLabel ?: w.photoSave, PhotoEditorTags.SAVE, strong = true) {
                apply()
                // Выбранная надпись — как обе галочки сразу.
                if (phase != TextPhase.None) saveText()
                onSave(marksOf(acts))
            }
        }

        // ── Набор надписи — своя область между кнопками сверху и окном «Текста» над клавиатурой ──
        // (заказчик 2026-10-22: «лучше скроемся за своей новой областью набора текста» — клавиатура ничего
        // не закрывает). Фото на это время за ней не видно.
        if (phase == TextPhase.Typing) {
            val focus = remember { FocusRequester() }
            val back = backInk.takeIf { it >= 0 }?.let { PhotoLayers.INKS[it] } ?: PhotoLayers.TYPING_BACK
            Box(
                Modifier.weight(1f).fillMaxWidth().padding(12.dp)
                    .background(back, RoundedCornerShape(TYPING_CORNER)).padding(14.dp),
            ) {
                BasicTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    textStyle = TextStyle(
                        color = PhotoLayers.INKS[textInk],
                        fontSize = TYPING_SIZE,
                        fontWeight = if (face == TextFace.Bold) FontWeight.Bold else FontWeight.Normal,
                        fontStyle = if (face == TextFace.Italic) FontStyle.Italic else FontStyle.Normal,
                    ),
                    cursorBrush = SolidColor(PhotoLayers.INKS[textInk]),
                    modifier = Modifier.fillMaxSize().focusRequester(focus).testTag(PhotoEditorTags.TEXT_FIELD),
                )
            }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        } else
        // ── Картинка: рисовать можно и за её краем; двумя пальцами — масштаб ──
        // Увеличенная картинка обрезается своей областью: верхние кнопки и значки видны всегда.
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds().padding(12.dp)) {
            val density = LocalDensity.current
            val boxW = constraints.maxWidth.toFloat()
            val boxH = constraints.maxHeight.toFloat()
            // Кисть, стрелка, обрезка — вокруг фото поле для пальца (заказчик 2026-10-22): след и рамку можно
            // начать за краем фото, но внутри области рисования — без спора с жестом «Назад» у края экрана.
            // Поле входит в область рисования; без инструмента фото прежнего размера.
            val margin = if (tool == Tool.None) 0f else with(density) { EDGE_ROOM.toPx() }
            val k = min((boxW - 2 * margin) / out.width, (boxH - 2 * margin) / out.height)
            val shownW = out.width * k
            val shownH = out.height * k
            val left = (boxW - shownW) / 2
            val top = (boxH - shownH) / 2
            val center = Offset(boxW / 2, boxH / 2)
            val handle = with(density) { 28.dp.toPx() }
            val tapSlop = with(density) { 24.dp.toPx() }
            val tapNear = with(density) { 80.dp.toPx() }
            /** Сдвиг увеличенного — не дальше поля у края: полоса для начала следа остаётся и при увеличении. */
            fun limited(p: Offset, s: Float): Offset {
                val mx = max(0f, (s * shownW - (boxW - 2 * margin)) / 2)
                val my = max(0f, (s * shownH - (boxH - 2 * margin)) / 2)
                return Offset(p.x.coerceIn(-mx, mx), p.y.coerceIn(-my, my))
            }
            // Поле появилось или пропало — сдвиг в новых границах.
            LaunchedEffect(margin) { pan = if (scale > 1f) limited(pan, scale) else Offset.Zero }
            /** Точка экрана → место на неувеличенной картинке → доли видимого. */
            fun share(p: Offset): Offset {
                val base = (p - center - pan) / scale + center
                return Offset((base.x - left) / shownW, (base.y - top) / shownH)
            }
            fun clamped(p: Offset) = Offset(p.x.coerceIn(0f, 1f), p.y.coerceIn(0f, 1f))
            /** Доля видимого → доля фото: туда ложится отметка. */
            fun photo(q: Offset) = PhotoLayers.toPhoto(q, shownMarks.quarter, shownMarks.crop)
            /** Толщина пера в долях фото: перо выбрано по видимому, хранится по фото. */
            fun widthOnPhoto(t: Tool) = nibOf(t).width * max(out.width, out.height) / max(source.width, source.height)
            /** Рамка надписи: фото → видимое и обратно. */
            fun textVisible(t: PhotoMark.Text) = PhotoLayers.rectToVisible(t.rect, shownMarks.quarter, shownMarks.crop)
            fun textOnPhoto(r: Rect) = PhotoLayers.rectToPhoto(r, shownMarks.quarter, shownMarks.crop)
            /** Нажатие без выбранной рамки: в сохранённую — правка, мимо — новая (заказчик 2026-10-22). */
            fun textTap(q: Offset) {
                val hit = textsOf(acts).entries.lastOrNull { textVisible(it.value).contains(q) }
                if (hit != null) {
                    val t = hit.value
                    draftId = hit.key
                    draftNew = false
                    draft = t
                    typed = TextFieldValue(t.text, TextRange(t.text.length))
                    textInk = PhotoLayers.INKS.indexOf(t.color).takeIf { it >= 0 } ?: DEFAULT_TEXT_INK
                    backInk = t.background?.let { PhotoLayers.INKS.indexOf(it) } ?: -1
                    face = t.face
                } else {
                    // Новая рамка: угол — в точке нажатия, ширина 60 % видимого, высота — на две строки; у
                    // края — внутрь.
                    val wq = NEW_TEXT_WIDTH
                    val hq = (NEW_TEXT_WIDTH * shownW * NEW_TEXT_ASPECT / shownH).coerceAtMost(1f)
                    val l = q.x.coerceIn(0f, 1f - wq)
                    val t = q.y.coerceIn(0f, 1f - hq)
                    draftId = nextTextId++
                    draftNew = true
                    draft = PhotoMark.Text(
                        rect = textOnPhoto(Rect(l, t, l + wq, t + hq)),
                        text = "",
                        color = PhotoLayers.INKS[textInk],
                        background = backInk.takeIf { it >= 0 }?.let { PhotoLayers.INKS[it] },
                        face = face,
                        turn = shownMarks.quarter,
                    )
                    typed = TextFieldValue("")
                }
                phase = TextPhase.Typing
            }
            Box(
                Modifier.fillMaxSize().pointerInput(tool, ink, nib, shownW, shownH, shownMarks) {
                    // Двойное нажатие — при любом инструменте (заказчик 2026-10-22): увеличенное — к исходному,
                    // исходное — увеличить к месту нажатия. Короткое быстрое касание — нажатие, а не след:
                    // оно ничего не рисует, иначе двойное оставляло бы точки.
                    var lastTapUp = 0L
                    var lastTapAt = Offset.Zero
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var path = 0f
                        var upAt = down.uptimeMillis
                        val start = share(down.position)
                        if (tool == Tool.Text) {
                            // «Текст»: при выбранной рамке (положение и размер) — тянуть угол или маркер в
                            // центре; нажатие внутрь — снова набор; нажатия мимо, пока рамка выбрана, — ничего.
                            val vr = draft?.takeIf { phase == TextPhase.Placing }?.let(::textVisible)
                            var grab = -1
                            if (vr != null) {
                                val points = listOf(vr.topLeft, vr.topRight, vr.bottomRight, vr.bottomLeft, vr.center)
                                grab = points.indexOfFirst { c ->
                                    abs((c.x - start.x) * shownW * scale) < handle && abs((c.y - start.y) * shownH * scale) < handle
                                }
                            }
                            var twoFingers = false
                            var last = start
                            while (true) {
                                val event = awaitPointerEvent()
                                upAt = event.changes.maxOf { it.uptimeMillis }
                                event.changes.firstOrNull { it.id == down.id }?.let { path += it.positionChange().getDistance() }
                                if (event.changes.none { it.pressed }) break
                                if (event.changes.count { it.pressed } > 1) twoFingers = true
                                if (twoFingers || grab < 0) {
                                    if (twoFingers) {
                                        scale = (scale * event.calculateZoom()).coerceIn(1f, EDIT_MAX_SCALE)
                                        pan = if (scale > 1f) limited(pan + event.calculatePan(), scale) else Offset.Zero
                                        event.changes.forEach { if (it.positionChange() != Offset.Zero) it.consume() }
                                    }
                                    continue
                                }
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (change.positionChange() != Offset.Zero) change.consume()
                                val at = share(change.position)
                                val d = draft ?: break
                                val r = textVisible(d)
                                val p = clamped(at)
                                val moved = when (grab) {
                                    0 -> Rect(min(p.x, r.right - MIN_CROP), min(p.y, r.bottom - MIN_CROP), r.right, r.bottom)
                                    1 -> Rect(r.left, min(p.y, r.bottom - MIN_CROP), max(p.x, r.left + MIN_CROP), r.bottom)
                                    2 -> Rect(r.left, r.top, max(p.x, r.left + MIN_CROP), max(p.y, r.top + MIN_CROP))
                                    3 -> Rect(min(p.x, r.right - MIN_CROP), r.top, r.right, max(p.y, r.top + MIN_CROP))
                                    else -> {
                                        // Маркер в центре — перенос целиком, в пределах фото.
                                        val dx = (at.x - last.x).coerceIn(-r.left, 1f - r.right)
                                        val dy = (at.y - last.y).coerceIn(-r.top, 1f - r.bottom)
                                        r.translate(dx, dy)
                                    }
                                }
                                last = at
                                draft = d.copy(rect = textOnPhoto(moved))
                            }
                            val tap = !twoFingers && grab < 0 && path <= tapSlop && upAt - down.uptimeMillis <= EDIT_TAP_MS
                            if (tap) {
                                if (down.uptimeMillis - lastTapUp <= EDIT_DOUBLE_TAP_MS && (down.position - lastTapAt).getDistance() <= tapNear) {
                                    scale = if (scale > 1f) 1f else EDIT_DOUBLE_TAP_SCALE
                                    pan = if (scale > 1f) limited((center - down.position) * (EDIT_DOUBLE_TAP_SCALE - 1f), scale) else Offset.Zero
                                    lastTapUp = 0L
                                } else {
                                    lastTapUp = upAt
                                    lastTapAt = down.position
                                    when {
                                        phase == TextPhase.None -> textTap(start)
                                        phase == TextPhase.Placing && vr != null && vr.contains(start) -> phase = TextPhase.Typing
                                    }
                                }
                            }
                            return@awaitEachGesture
                        }
                        // Рамка: за угол — тянуть угол; иначе — новая рамка.
                        var corner = -1
                        crop?.takeIf { tool == Tool.Crop }?.let { r ->
                            val corners = listOf(r.topLeft, r.topRight, r.bottomRight, r.bottomLeft)
                            corner = corners.indexOfFirst { c ->
                                abs((c.x - start.x) * shownW * scale) < handle && abs((c.y - start.y) * shownH * scale) < handle
                            }
                        }
                        live.clear()
                        if (tool != Tool.None && corner < 0) live.add(start)
                        var twoFingers = false
                        while (true) {
                            val event = awaitPointerEvent()
                            upAt = event.changes.maxOf { it.uptimeMillis }
                            event.changes.firstOrNull { it.id == down.id }?.let { path += it.positionChange().getDistance() }
                            if (event.changes.none { it.pressed }) break
                            if (event.changes.count { it.pressed } > 1) {
                                // Второй палец — масштаб: начатый след не считается.
                                twoFingers = true
                                live.clear()
                            }
                            if (twoFingers || tool == Tool.None) {
                                val zoom = event.calculateZoom()
                                scale = (scale * zoom).coerceIn(1f, EDIT_MAX_SCALE)
                                pan = if (scale > 1f) limited(pan + event.calculatePan(), scale) else Offset.Zero
                                event.changes.forEach { if (it.positionChange() != Offset.Zero) it.consume() }
                                continue
                            }
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
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
                        val tap = !twoFingers && corner < 0 && path <= tapSlop && upAt - down.uptimeMillis <= EDIT_TAP_MS
                        if (tap) {
                            if (down.uptimeMillis - lastTapUp <= EDIT_DOUBLE_TAP_MS && (down.position - lastTapAt).getDistance() <= tapNear) {
                                if (scale > 1f) {
                                    scale = 1f
                                    pan = Offset.Zero
                                } else {
                                    scale = EDIT_DOUBLE_TAP_SCALE
                                    pan = limited((center - down.position) * (EDIT_DOUBLE_TAP_SCALE - 1f), scale)
                                }
                                lastTapUp = 0L
                            } else {
                                lastTapUp = upAt
                                lastTapAt = down.position
                            }
                            return@awaitEachGesture
                        }
                        lastTapUp = 0L
                        if (twoFingers || corner >= 0 || tool == Tool.None) return@awaitEachGesture
                        val width = widthOnPhoto(tool)
                        when (tool) {
                            Tool.Crop -> if (pts.size > 1) {
                                val a = clamped(pts.first())
                                val b = clamped(pts.last())
                                if (abs(b.x - a.x) > MIN_CROP && abs(b.y - a.y) > MIN_CROP) {
                                    crop = Rect(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))
                                }
                            }
                            Tool.Brush -> pending.add(
                                when (nib.kind) {
                                    NibKind.Erase -> PhotoMark.Erase(pts.map(::photo), width)
                                    else -> PhotoMark.Brush(pts.map(::photo), PhotoLayers.INKS[ink], width, glass = nib.kind == NibKind.Glass)
                                },
                            )
                            Tool.Text, Tool.None -> Unit
                        }
                    }
                },
            ) {
                // Картинка и рисуемое поверх — в одном слое: масштаб и сдвиг у них общие.
                Box(Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = pan.x, translationY = pan.y)) {
                    // Стопка: фото, рамка, поворот, отметки — то же, что сведётся в итог, только мельче.
                    Canvas(
                        Modifier.offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                            .size(with(density) { shownW.toDp() }, with(density) { shownH.toDp() })
                            .testTag(PhotoEditorTags.IMAGE),
                    ) {
                        scale(k, k, pivot = Offset.Zero) { drawPhotoLayers(source, shownMarks, pending.toList(), measurer) }
                    }
                    val frame = colors.navigation
                    val shade = colors.viewer.copy(alpha = 0.6f)
                    val eraseTrail = colors.surface.copy(alpha = 0.5f)
                    val idle = colors.text3
                    Canvas(Modifier.fillMaxSize()) {
                        fun px(p: Offset) = Offset(left + p.x * shownW, top + p.y * shownH)
                        crop?.let { r -> cropFrame(Rect(px(r.topLeft), px(r.bottomRight)), Rect(left, top, left + shownW, top + shownH), frame, shade, handle / scale) }
                        // «Текст»: сохранённые надписи — серые рамки, выбранная — своя, с углами и маркером.
                        if (tool == Tool.Text) {
                            for ((id, t) in textsOf(acts)) if (id != draftId) {
                                val r = textVisible(t)
                                drawRect(idle, px(r.topLeft), Size(r.width * shownW, r.height * shownH), style = Stroke(1.5.dp.toPx() / scale))
                            }
                            draft?.takeIf { phase == TextPhase.Placing }?.let { d ->
                                val r = textVisible(d)
                                textFrame(Rect(px(r.topLeft), px(r.bottomRight)), frame, handle / scale)
                            }
                        }
                        val pts = live.map(::px)
                        if (pts.isEmpty()) return@Canvas
                        val stroke = (nibOf(tool).width * max(shownW, shownH)).coerceAtLeast(2f)
                        when (tool) {
                            Tool.Crop -> if (pts.size > 1) {
                                val a = pts.first()
                                val b = pts.last()
                                drawRect(frame, Offset(min(a.x, b.x), min(a.y, b.y)), Size(abs(b.x - a.x), abs(b.y - a.y)), style = Stroke(2.dp.toPx() / scale))
                            }
                            Tool.Brush -> {
                                val path = Path()
                                pts.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
                                // Ластик, пока ведётся, — бледный след: что сотрётся, видно после отпускания.
                                drawPath(path, if (nib.kind == NibKind.Erase) eraseTrail else brushInk(), style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
                            }
                            Tool.Text, Tool.None -> Unit
                        }
                    }
                }
            }
        }

        // ── Низ: значки на месте; выбран инструмент — его окно поверх них, того же размера ──
        Box(Modifier.fillMaxWidth()) {
            // Окно инструмента открыто — значки под ним не видны и не нажимаются: касание мимо цвета
            // проходило сквозь окно и выбирало инструмент под ним (заказчик 2026-10-22).
            val iconsOn = tool == Tool.None
            Row(
                Modifier.fillMaxWidth().alpha(if (iconsOn) 1f else 0f).padding(12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                ToolIcon(Glyph.Turn, w.photoRotate, PhotoEditorTags.TURN, selected = false, enabled = iconsOn) {
                    choose(Tool.None)
                    tool = Tool.None
                    commit(Act.Turn)
                }
                ToolIcon(Glyph.Crop, w.photoCrop, PhotoEditorTags.CROP, selected = tool == Tool.Crop, enabled = iconsOn) { choose(Tool.Crop) }
                ToolIcon(Glyph.Brush, w.photoBrush, PhotoEditorTags.BRUSH, selected = tool == Tool.Brush, enabled = iconsOn) { choose(Tool.Brush) }
                ToolIcon(Glyph.Letter, w.photoText, PhotoEditorTags.TEXT, selected = tool == Tool.Text, enabled = iconsOn) { choose(Tool.Text) }
            }
            if (tool != Tool.None) {
                // Поверх всего ряда — со значками и подписями: компактно, полоса та же (заказчик 2026-10-22).
                Row(
                    Modifier.matchParentSize()
                        // Окно забирает касания всей полосы — до значков под ним они не доходят.
                        .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .background(colors.surface, RoundedCornerShape(16.dp)).padding(horizontal = 8.dp)
                        .testTag(PhotoEditorTags.PANEL),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // ✕ — закрыть окно: невыполненное пропадает. У «Текста» с выбранной рамкой — зелёный: снять выбор.
                    val chosen = tool == Tool.Text && phase != TextPhase.None
                    IconCircle(Glyph.Close, PhotoEditorTags.CLOSE, selected = chosen, size = 40) { if (chosen) dropText() else choose(tool) }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (tool == Tool.Text) {
                            // Цвета: «Текст» — цвет букв; «Фон» — заливка рамки, первым — без цвета.
                            val noColorMark = colors.text3
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (paintBack) {
                                    Box(
                                        Modifier.size(24.dp).border(if (backInk < 0) 3.dp else 1.dp, if (backInk < 0) colors.navigation else colors.text3, CircleShape)
                                            .clickable { backInk = -1; restyle() }.testTag(PhotoEditorTags.NO_COLOR),
                                        contentAlignment = Alignment.Center,
                                    ) { Canvas(Modifier.size(14.dp)) { drawLine(noColorMark, Offset(0f, size.height), Offset(size.width, 0f), 2.dp.toPx()) } }
                                }
                                PhotoLayers.INKS.forEachIndexed { i, c ->
                                    val on = if (paintBack) i == backInk else i == textInk
                                    Box(
                                        Modifier.size(24.dp).background(c, CircleShape)
                                            .border(if (on) 3.dp else 1.dp, if (on) colors.navigation else colors.text3, CircleShape)
                                            .clickable { if (paintBack) backInk = i else textInk = i; restyle() }.testTag(PhotoEditorTags.color(i)),
                                    )
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                TextFace.entries.forEach { f ->
                                    FaceChip(f, f == face) { face = f; restyle() }
                                }
                                Box(Modifier.size(6.dp))
                                SmallPill(w.photoTextInk, PhotoEditorTags.TEXT_INK, on = !paintBack) { paintBack = false }
                                SmallPill(w.photoTextBack, PhotoEditorTags.TEXT_BACK, on = paintBack) { paintBack = true }
                            }
                        } else if (tool == Tool.Brush) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                PhotoLayers.INKS.forEachIndexed { i, c ->
                                    Box(
                                        Modifier.size(24.dp).background(c, CircleShape)
                                            .border(if (i == ink) 3.dp else 1.dp, if (i == ink) colors.navigation else colors.text3, CircleShape)
                                            .clickable { ink = i }.testTag(PhotoEditorTags.color(i)),
                                    )
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                val now = nibOf(tool)
                                IconCircle(Glyph.Thin, PhotoEditorTags.THIN, selected = now == Nib.Thin, size = 28) { nib = Nib.Thin }
                                IconCircle(Glyph.Thick, PhotoEditorTags.THICK, selected = now == Nib.Thick, size = 28) { nib = Nib.Thick }
                                if (tool == Tool.Brush) {
                                    IconCircle(Glyph.GlassThick, PhotoEditorTags.GLASS_THICK, selected = now == Nib.GlassThick, size = 28) { nib = Nib.GlassThick }
                                    IconCircle(Glyph.GlassWide, PhotoEditorTags.GLASS_WIDE, selected = now == Nib.GlassWide, size = 28) { nib = Nib.GlassWide }
                                    IconCircle(Glyph.EraseSmall, PhotoEditorTags.ERASE_SMALL, selected = now == Nib.EraseSmall, size = 28) { nib = Nib.EraseSmall }
                                    IconCircle(Glyph.EraseLarge, PhotoEditorTags.ERASE_LARGE, selected = now == Nib.EraseLarge, size = 28) { nib = Nib.EraseLarge }
                                }
                            }
                        } else {
                            Caption(w.photoCropAbout, fontSize = TimaType.sz5, color = colors.text, maxLines = 3, modifier = Modifier.testTag(PhotoEditorTags.ABOUT))
                        }
                    }
                    // ✓ — выполнить: нарисованное остаётся, окно закрывается. У «Текста» — две галочки: набор →
                    // положение и размер → сохранено.
                    if (tool == Tool.Text) {
                        val ready = phase != TextPhase.None
                        IconCircle(Glyph.Apply, PhotoEditorTags.APPLY, selected = ready, enabled = ready, size = 40) {
                            when (phase) {
                                TextPhase.Typing -> {
                                    val text = typed.text.trimEnd()
                                    if (text.isBlank()) {
                                        // Стёрли весь текст — надпись убирается вместе с рамкой.
                                        if (!draftNew) commit(Act.Write(draftId, null))
                                        dropText()
                                    } else {
                                        draft = draft?.copy(text = text)
                                        phase = TextPhase.Placing
                                    }
                                }
                                TextPhase.Placing -> saveText()
                                TextPhase.None -> Unit
                            }
                        }
                    } else {
                        val ready = if (tool == Tool.Crop) crop != null else pending.isNotEmpty()
                        IconCircle(Glyph.Apply, PhotoEditorTags.APPLY, selected = ready, enabled = ready, size = 40) {
                            apply()
                            tool = Tool.None
                        }
                    }
                }
            }
        }
    }
}

/** Надпись: новая рамка — 60 % ширины видимого, высота — 0,3 её ширины (две строки); цвет букв — белый. */
private const val NEW_TEXT_WIDTH = 0.6f
private const val NEW_TEXT_ASPECT = 0.3f
private const val DEFAULT_TEXT_INK = 4
private val TYPING_SIZE = 22.sp
private val TYPING_CORNER = 16.dp

/** Рамка выбранной надписи: углы — ручки размера, в центре — маркер переноса. */
private fun DrawScope.textFrame(r: Rect, frame: Color, handle: Float) {
    drawRect(frame, r.topLeft, r.size, style = Stroke(2.dp.toPx()))
    val arm = handle * 0.7f
    val sw = 4.dp.toPx()
    for ((c, dx, dy) in listOf(Triple(r.topLeft, 1f, 1f), Triple(r.topRight, -1f, 1f), Triple(r.bottomRight, -1f, -1f), Triple(r.bottomLeft, 1f, -1f))) {
        drawLine(frame, c, Offset(c.x + dx * arm, c.y), sw, StrokeCap.Round)
        drawLine(frame, c, Offset(c.x, c.y + dy * arm), sw, StrokeCap.Round)
    }
    drawCircle(frame, handle * 0.35f, r.center)
    drawCircle(frame, handle * 0.35f, r.center, style = Stroke(sw / 2))
}

/** Начертание надписи — буква «А» в круге: обычная, жирная, курсив. */
@Composable
private fun FaceChip(f: TextFace, on: Boolean, onClick: () -> Unit) {
    val colors = Tima.colors
    Box(
        Modifier.size(28.dp).background(if (on) colors.navigation else colors.surface, CircleShape)
            .border(1.dp, colors.text3, CircleShape).clickable(onClick = onClick).testTag(PhotoEditorTags.face(f)),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.text.BasicText(
            Tima.words.chat.photoFaceLetter,
            style = TextStyle(
                color = if (on) colors.surface else colors.text,
                fontSize = 14.sp,
                fontWeight = if (f == TextFace.Bold) FontWeight.Black else FontWeight.Normal,
                fontStyle = if (f == TextFace.Italic) FontStyle.Italic else FontStyle.Normal,
            ),
        )
    }
}

/** Маленький переключатель-слово на сплошном фоне. */
@Composable
private fun SmallPill(label: String, tag: String, on: Boolean, onClick: () -> Unit) {
    val colors = Tima.colors
    Caption(
        label,
        fontSize = TimaType.sz6,
        weight = FontWeight.Bold,
        color = if (on) colors.surface else colors.text,
        modifier = Modifier.background(if (on) colors.navigation else colors.functional, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp).testTag(tag),
    )
}

/** Поле вокруг фото при кисти, стрелке, обрезке — шире пальца. */
private val EDGE_ROOM = 36.dp

/** Наибольшее увеличение в редакторе; двойное нажатие; нажатие — не дольше, второе — не позже. */
private const val EDIT_MAX_SCALE = 5f
private const val EDIT_DOUBLE_TAP_SCALE = 2.5f
private const val EDIT_TAP_MS = 300L
private const val EDIT_DOUBLE_TAP_MS = 400L

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
internal enum class Glyph { Turn, Crop, Brush, Arrow, Undo, Redo, Thin, Thick, Edit, Apply, Close, GlassThick, GlassWide, EraseSmall, EraseLarge, Letter }

@Composable
private fun ToolIcon(glyph: Glyph, label: String, tag: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = Tima.colors
    Column(
        Modifier.then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier).testTag(tag).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconCircle(glyph, null, selected = selected, enabled = enabled, onClick = onClick)
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
        // Пустой круг — полупрозрачное перо: тонкий обод — размером с толстую кисть, толстый обод — шире.
        Glyph.GlassThick -> drawCircle(c, s * 0.36f, Offset(o.x + s / 2, o.y + s / 2), style = Stroke(s * 0.1f))
        Glyph.GlassWide -> drawCircle(c, s * 0.32f, Offset(o.x + s / 2, o.y + s / 2), style = Stroke(s * 0.24f))
        // Ластик — пустой квадрат: малый и большой.
        Glyph.EraseSmall -> drawRect(c, p(0.3f, 0.3f), Size(s * 0.4f, s * 0.4f), style = Stroke(s * 0.1f))
        Glyph.EraseLarge -> drawRect(c, p(0.08f, 0.08f), Size(s * 0.84f, s * 0.84f), style = Stroke(s * 0.1f))
        // «А» — надпись.
        Glyph.Letter -> {
            line(p(0.12f, 0.95f), p(0.5f, 0.05f))
            line(p(0.5f, 0.05f), p(0.88f, 0.95f))
            line(p(0.3f, 0.62f), p(0.7f, 0.62f))
        }
        Glyph.Apply -> {
            line(p(0.1f, 0.55f), p(0.4f, 0.85f))
            line(p(0.4f, 0.85f), p(0.92f, 0.2f))
        }
        Glyph.Close -> {
            line(p(0.15f, 0.15f), p(0.85f, 0.85f))
            line(p(0.85f, 0.15f), p(0.15f, 0.85f))
        }
        Glyph.Edit -> {
            // Карандаш: стержень по диагонали и остриё.
            line(p(0.25f, 0.75f), p(0.85f, 0.15f), s * 0.2f)
            line(p(0.1f, 0.9f), p(0.22f, 0.78f), s * 0.1f)
        }
    }
}
