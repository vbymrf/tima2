package io.tima.feature.call

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.tima.core.call.CallState
import io.tima.core.call.CameraCrop
import io.tima.core.call.SoundRoute
import io.tima.core.ui.Caption
import io.tima.core.ui.CheckMark
import io.tima.core.ui.Secondary
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaFixed
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.words

// ── НОВЫЙ ВИД ОКНА РАЗГОВОРА (ПЛАН-(ЗН), макет `пробы-звонок.html`, заказчик 2026-11-10) ─────
//
// Один ряд слева направо: Свернуть (маленькая) · Микрофон (большая) · ухо / динамик (столбиком) ·
// Камера (большая) · Настройки звонка ≡ (маленькая) · Завершить (большая, красная). Подписи —
// только у больших (ответ 9). Цвета — прежней панели: салатовая — включено, серая — выключено,
// красная — конец. Смена камеры — кругом на своём изображении, «глаз» — в листе настроек
// пунктом «Экономить трафик». Групповой звонок этот вид не получает (ответ 4).

/** Что нужно новому виду сверх прежнего окна. Есть — окно разговора на двоих рисуется по-новому. */
class FreshCall(
    /** «Свернуть» — в «Телефон» на «Чаты»; звонок остаётся панелью над шапкой. */
    val onMinimize: () -> Unit,
    /** «Экономить трафик» — поведением нынешнего глаза (ответ 7). */
    val onSaveTraffic: (Boolean) -> Unit,
    /** «Показать экран устройства»; `null` — движок не умеет, пункта нет. */
    val onScreenShare: ((Boolean) -> Unit)?,
    /** Режим адаптации изображения: текущее приближение; `null` — режим выключен в настройках. */
    val crop: CameraCrop?,
    val onCrop: (CameraCrop) -> Unit,
)

@Composable
internal fun FreshTalkPanel(
    state: CallState,
    onMicrophone: (Boolean) -> Unit,
    onCamera: (Boolean) -> Unit,
    onSpeaker: ((Boolean) -> Unit)?,
    sheetOpen: Boolean,
    onSheet: () -> Unit,
    onMinimize: () -> Unit,
    onHangUp: () -> Unit,
) {
    val words = Tima.words.call
    // Экономия трафика гасит камеру и запрещает её, как закрытый глаз; показ экрана — тоже:
    // уходит экран, а не камера.
    val saving = !state.remoteVideoTaken
    val cameraFree = !saving && !state.screenSharing
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = TimaSpacing.about2, vertical = TimaSpacing.about2)
            .testTag(FRESH_PANEL_TAG),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Top,
    ) {
        AtButton(SMALL_SLOT) {
            SmallRound(onClick = onMinimize, modifier = Modifier.testTag(MINIMIZE_TAG)) { ink -> Chevron(ink) }
        }
        Labeled(words.panelSound, dim = !state.microphoneOn) {
            BigButton(
                glyph = if (state.microphoneOn) "🎤" else "🔇",
                on = state.microphoneOn,
                onClick = { onMicrophone(!state.microphoneOn) },
                modifier = Modifier.testTag(MICROPHONE_TAG),
            )
        }
        AtButton(SWITCH_WIDTH) {
            if (onSpeaker != null && state.sound != SoundRoute.Unknown) {
                val speaker = state.sound == SoundRoute.Speaker
                TwoWay(
                    top = { Glyph(if (state.sound == SoundRoute.Headset) "🎧" else "👂") },
                    bottom = { Glyph("🔊") },
                    topOn = !speaker,
                    onTop = { onSpeaker(false) },
                    onBottom = { onSpeaker(true) },
                )
            }
        }
        Labeled(words.panelCamera, dim = !cameraFree) {
            BigButton(
                glyph = "📹",
                on = state.cameraOn && cameraFree,
                enabled = cameraFree,
                onClick = { onCamera(!state.cameraOn) },
                modifier = Modifier.testTag(CAMERA_TAG),
            )
        }
        AtButton(SMALL_SLOT) {
            SmallRound(onClick = onSheet, live = sheetOpen, modifier = Modifier.testTag(CALL_SHEET_TAG)) { ink -> Bars(ink) }
        }
        Labeled(words.hangUp) {
            BigButton(glyph = "📞", on = false, danger = true, onClick = onHangUp, modifier = Modifier.testTag(HANG_UP_TAG))
        }
    }
}

/**
 * Лист «Настройки звонка» (ЗН3, ЗН6) — снизу поверх изображения. Выключатели — квадратом:
 * оба могут стоять вместе.
 */
@Composable
internal fun CallSheet(
    saving: Boolean,
    onSaveTraffic: (Boolean) -> Unit,
    sharing: Boolean,
    onScreenShare: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = Tima.colors
    val words = Tima.words.call
    Column(
        modifier.fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(topStart = SHEET_RADIUS, topEnd = SHEET_RADIUS))
            .padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2)
            .testTag(CALL_SHEET_BODY_TAG),
        verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).width(36.dp).height(4.dp).background(colors.line, RoundedCornerShape(2.dp)))
        Caption(words.sheetTitle, fontSize = TimaType.sz4, weight = FontWeight.Bold)
        if (onScreenShare != null) {
            SheetRow("🖥", words.shareScreen, words.shareScreenAbout, sharing, Modifier.testTag(SCREEN_SHARE_TAG)) { onScreenShare(!sharing) }
        }
        SheetRow(if (saving) "🙈" else "👁", words.saveTraffic, words.saveTrafficAbout, saving, Modifier.testTag(SAVE_TRAFFIC_TAG)) {
            onSaveTraffic(!saving)
        }
    }
}

@Composable
private fun SheetRow(glyph: String, title: String, about: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) = Row(
    modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = TimaSpacing.about1),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
) {
    Caption(glyph, fontSize = TimaType.sz4)
    Column(Modifier.weight(1f)) {
        Caption(title, fontSize = TimaType.sz5, weight = FontWeight.Bold)
        Secondary(about)
    }
    CheckMark(on)
}

/**
 * Режим адаптации изображения (ЗН5): поверх изображения, выше ряда кнопок — «−», «+», стрелки и
 * «1×». Своё изображение обрезается перед отправкой, и собеседник видит то же, что окошко.
 */
@Composable
internal fun AdaptBar(crop: CameraCrop, onCrop: (CameraCrop) -> Unit, modifier: Modifier = Modifier) = Column(
    modifier,
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(TimaSpacing.about1),
) {
    // Сдвиг цифрами (заказчик 2026-11-10): проценты кадра, вправо и вверх — плюс; отдельно — сколько ушло за край.
    val words = Tima.words.call
    val (sx, sy) = crop.shiftPercent
    val (ox, oy) = crop.overPercent
    Row(
        Modifier.background(TimaFixed.videoShade.copy(alpha = 0.45f), RoundedCornerShape(50))
            .padding(horizontal = TimaSpacing.about2, vertical = 2.dp)
            .testTag(ADAPT_NUMBERS_TAG),
        horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
    ) {
        Caption(words.adaptShift(CameraCrop.signed(sx), CameraCrop.signed(sy)), fontSize = TimaType.sz6, weight = FontWeight.Bold, color = TimaFixed.onVideo, lineOne = true)
        Caption(
            words.adaptOver(CameraCrop.signed(ox), CameraCrop.signed(oy)),
            fontSize = TimaType.sz6,
            weight = FontWeight.Bold,
            color = if (ox != 0 || oy != 0) Tima.colors.activity else TimaFixed.onVideo,
            lineOne = true,
        )
    }
    AdaptKeys(crop, onCrop)
}

@Composable
private fun AdaptKeys(crop: CameraCrop, onCrop: (CameraCrop) -> Unit) {
    Row(
        Modifier
            .background(TimaFixed.videoShade.copy(alpha = 0.45f), RoundedCornerShape(50))
            .padding(horizontal = TimaSpacing.about2, vertical = TimaSpacing.about1)
            .testTag(ADAPT_TAG),
        horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AdaptKey("−", crop.canFarther, ADAPT_OUT_TAG) { onCrop(crop.farther()) }
        AdaptKey("+", crop.canCloser, ADAPT_IN_TAG) { onCrop(crop.closer()) }
        // Знак текстового начертания (U+FE0E): без него Samsung рисует ◀ и ▶ цветными значками,
        // а ▲ и ▼ — текстом (живая проверка 2026-11-10).
        AdaptKey("◀\uFE0E", crop.canMove(-1, 0), ADAPT_LEFT_TAG) { onCrop(crop.moved(-1, 0)) }
        AdaptKey("▲\uFE0E", crop.canMove(0, -1), ADAPT_UP_TAG) { onCrop(crop.moved(0, -1)) }
        AdaptKey("▼\uFE0E", crop.canMove(0, 1), ADAPT_DOWN_TAG) { onCrop(crop.moved(0, 1)) }
        AdaptKey("▶\uFE0E", crop.canMove(1, 0), ADAPT_RIGHT_TAG) { onCrop(crop.moved(1, 0)) }
        // Ступень сейчас — на «1×» не тратится отдельное место: «1×» и есть сброс, а пока не
        // приблизили, на нём стоит то, что сейчас.
        AdaptKey(CameraCrop.label(crop.zoom) + "×", !crop.full, ADAPT_RESET_TAG) { onCrop(CameraCrop.FULL) }
    }
}

@Composable
private fun AdaptKey(text: String, enabled: Boolean, tag: String, onClick: () -> Unit) = Box(
    Modifier.size(ADAPT_KEY)
        .background(TimaFixed.onVideo.copy(alpha = if (enabled) 0.22f else 0.08f), CircleShape)
        .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
        .testTag(tag),
    contentAlignment = Alignment.Center,
) {
    Caption(text, modifier = Modifier.alpha(if (enabled) 1f else DIM), fontSize = TimaType.sz5, weight = FontWeight.Bold, color = TimaFixed.onVideo, lineOne = true)
}

/**
 * Приблизить показ своего изображения так, как режется уходящий кадр: середина видимой части —
 * в середину окна. Для платформ, что показывают себя мимо обрезки ([io.tima.core.call.VideoHandle.cropped]).
 */
internal fun cropView(crop: CameraCrop): Modifier = Modifier.graphicsLayer {
    scaleX = crop.zoom
    scaleY = crop.zoom
    translationX = -crop.x * size.width * crop.zoom
    translationY = -crop.y * size.height * crop.zoom
}

/** Смена камеры кругом на своём изображении (ЗН4): передняя ↔ задняя. */
@Composable
internal fun CameraFlip(onClick: () -> Unit, modifier: Modifier = Modifier) = Box(
    modifier.size(FLIP)
        .background(TimaFixed.videoShade.copy(alpha = 0.5f), CircleShape)
        .clickable(onClick = onClick)
        .testTag(CAMERA_FLIP_TAG),
    contentAlignment = Alignment.Center,
) {
    val ink = TimaFixed.onVideo
    Canvas(Modifier.size(ICON)) {
        val w = size.width
        val stroke = Stroke(width = w * 0.1f, cap = StrokeCap.Round)
        // Две дуги по кругу со стрелками — «повернуть»: верхняя по часовой, нижняя против.
        drawArc(ink, startAngle = 200f, sweepAngle = 140f, useCenter = false, topLeft = Offset(w * 0.12f, w * 0.12f), size = androidx.compose.ui.geometry.Size(w * 0.76f, w * 0.76f), style = stroke)
        drawArc(ink, startAngle = 20f, sweepAngle = 140f, useCenter = false, topLeft = Offset(w * 0.12f, w * 0.12f), size = androidx.compose.ui.geometry.Size(w * 0.76f, w * 0.76f), style = stroke)
        drawPath(Path().apply { moveTo(w * 0.86f, w * 0.18f); lineTo(w * 0.88f, w * 0.42f); lineTo(w * 0.66f, w * 0.36f); close() }, ink)
        drawPath(Path().apply { moveTo(w * 0.14f, w * 0.82f); lineTo(w * 0.12f, w * 0.58f); lineTo(w * 0.34f, w * 0.64f); close() }, ink)
    }
}

/** Маленькая круглая кнопка ряда: серая, горит салатовым, пока её лист открыт. */
@Composable
private fun SmallRound(onClick: () -> Unit, modifier: Modifier = Modifier, live: Boolean = false, icon: @Composable (Color) -> Unit) {
    val colors = Tima.colors
    Box(
        modifier.size(SMALL)
            .background(if (live) colors.navigation else colors.line, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { icon(if (live) colors.onAccent else colors.text) }
}

/** «Свернуть» — галка вниз, как в макете. */
@Composable
private fun Chevron(ink: Color) = Canvas(Modifier.size(ICON)) {
    val w = size.width
    drawPath(
        Path().apply {
            moveTo(w * 0.25f, w * 0.38f)
            lineTo(w * 0.5f, w * 0.63f)
            lineTo(w * 0.75f, w * 0.38f)
        },
        ink,
        style = Stroke(width = w * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

/** «Настройки звонка» — три полосы. */
@Composable
private fun Bars(ink: Color) = Canvas(Modifier.size(ICON)) {
    val w = size.width
    for (y in listOf(0.3f, 0.5f, 0.7f)) {
        drawLine(ink, Offset(w * 0.22f, w * y), Offset(w * 0.78f, w * y), strokeWidth = w * 0.1f, cap = StrokeCap.Round)
    }
}

/** Маленькая кнопка ряда и её место. */
private val SMALL = 38.dp
private val SMALL_SLOT = 40.dp
private val SHEET_RADIUS = 16.dp
private val ADAPT_KEY = 32.dp
private val FLIP = 30.dp

const val FRESH_PANEL_TAG: String = "call:fresh"
const val MINIMIZE_TAG: String = "call:minimize"
const val MICROPHONE_TAG: String = "call:microphone"
const val CALL_SHEET_TAG: String = "call:sheet"
const val CALL_SHEET_BODY_TAG: String = "call:sheet:body"
const val SCREEN_SHARE_TAG: String = "call:screen"
const val SAVE_TRAFFIC_TAG: String = "call:save"
const val CAMERA_FLIP_TAG: String = "call:flip"
const val ADAPT_TAG: String = "call:adapt"
const val ADAPT_IN_TAG: String = "call:adapt:in"
const val ADAPT_OUT_TAG: String = "call:adapt:out"
const val ADAPT_LEFT_TAG: String = "call:adapt:left"
const val ADAPT_RIGHT_TAG: String = "call:adapt:right"
const val ADAPT_UP_TAG: String = "call:adapt:up"
const val ADAPT_DOWN_TAG: String = "call:adapt:down"
const val ADAPT_RESET_TAG: String = "call:adapt:reset"
const val ADAPT_NUMBERS_TAG: String = "call:adapt:numbers"
