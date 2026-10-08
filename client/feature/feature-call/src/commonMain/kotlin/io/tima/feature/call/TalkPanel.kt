package io.tima.feature.call

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.tima.core.call.CallState
import io.tima.core.call.SoundRoute
import io.tima.core.call.VideoHandle
import io.tima.core.ui.Caption
import io.tima.core.ui.IconButton
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.words
import kotlin.math.roundToInt

// ── ПАНЕЛЬ РАЗГОВОРА (проба «а» из `пробы-окно-звонка.html`, заказчик 2026-10-08) ─────────
//
// Три столбца с подписями внизу — «Звук», «Камера», «Завершить». Над «Звук» крупный микрофон,
// левее — переключатель на два места: «Ухо» сверху, «Динамик» снизу; горит то, куда идёт звук.
// Над «Камера» крупная камера, правее — «картина с человечком» (передняя) сверху и «картина»
// (задняя) снизу: что показываем. Над «Завершить» — крупная красная. Между «Звук» и «Камера» —
// маленький глаз, как был: он гасит видео в обе стороны, и «Камера» под ним неактивна.
//
// Места кнопок не меняются ни с видео, ни без: правило прежнего ряда значков (2026-09-20)
// остаётся в силе, и ради него переключателя нет — место пустое, — а не сдвиг соседей.

/**
 * @param onParticipants групповой — журнал звонка (ГЗ6) маленькой кнопкой под глазом; `null` —
 *   звонок на двоих.
 */
@Composable
internal fun TalkPanel(
    state: CallState,
    onMicrophone: (Boolean) -> Unit,
    onCamera: (Boolean) -> Unit,
    onSpeaker: ((Boolean) -> Unit)?,
    onSwitchCamera: (() -> Unit)?,
    onRemoteVideo: ((Boolean) -> Unit)?,
    onParticipants: (() -> Unit)?,
    onHangUp: () -> Unit,
) {
    val words = Tima.words.call
    // Глаз выключен — видео не принимаем и не показываем. Камеру он гасит сам и помнит,
    // была ли она включена: открыли глаз — камера возвращается как была.
    val eyeOpen = onRemoteVideo == null || state.remoteVideoTaken
    var cameraBeforeEye by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxWidth()
            .padding(horizontal = TimaSpacing.about3, vertical = TimaSpacing.about2)
            .testTag(TALK_PANEL_TAG),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            // Переключатель, микрофон, глаз, камера, переключатель — одной группой с равными
            // промежутками (заказчик 2026-10-08): микрофон и камера стоят вплотную к глазу так
            // же, как переключатели к ним. Нет переключателя или глаза — на их месте пусто,
            // соседи не сдвигаются.
            Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                Row(horizontalArrangement = Arrangement.spacedBy(GAP), verticalAlignment = Alignment.Top) {
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
                    // «Звук» серый, когда микрофон выключен, — как «Камера» под закрытым глазом.
                    Labeled(words.panelSound, dim = !state.microphoneOn) {
                        BigButton(
                            glyph = if (state.microphoneOn) "🎤" else "🔇",
                            on = state.microphoneOn,
                            onClick = { onMicrophone(!state.microphoneOn) },
                        )
                    }
                    AtButton(EYE_WIDTH) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(TimaSpacing.about1),
                        ) {
                            if (onRemoteVideo != null) {
                                IconButton(
                                    glyph = if (eyeOpen) "👁" else "🙈",
                                    live = eyeOpen,
                                    onClick = {
                                        if (eyeOpen) {
                                            cameraBeforeEye = state.cameraOn
                                            onRemoteVideo(false)
                                            if (state.cameraOn) onCamera(false)
                                        } else {
                                            onRemoteVideo(true)
                                            if (cameraBeforeEye) onCamera(true)
                                        }
                                    },
                                    modifier = Modifier.testTag(EYE_TAG),
                                )
                            }
                            onParticipants?.let { IconButton(glyph = "👥", live = true, onClick = it) }
                        }
                    }
                    Labeled(words.panelCamera, dim = !eyeOpen) {
                        BigButton(
                            glyph = "📹",
                            on = state.cameraOn && eyeOpen,
                            enabled = eyeOpen,
                            onClick = { onCamera(!state.cameraOn) },
                            modifier = Modifier.testTag(CAMERA_TAG),
                        )
                    }
                    AtButton(SWITCH_WIDTH) {
                        if (onSwitchCamera != null && state.cameraSwitchable) {
                            TwoWay(
                                top = { lit -> PictureIcon(person = true, lit = lit) },
                                bottom = { lit -> PictureIcon(person = false, lit = lit) },
                                topOn = state.cameraFront,
                                enabled = eyeOpen,
                                onTop = { if (!state.cameraFront) onSwitchCamera() },
                                onBottom = { if (state.cameraFront) onSwitchCamera() },
                            )
                        }
                    }
                }
            }
            // ── Завершить ── своим столбцом справа.
            Box(Modifier.width(HANG_UP_COLUMN), contentAlignment = Alignment.TopCenter) {
                Labeled(words.hangUp) {
                    BigButton(glyph = "📞", on = false, danger = true, onClick = onHangUp)
                }
            }
        }
    }
}

/** Крупная кнопка с подписью под ней. */
@Composable
private fun Labeled(text: String, dim: Boolean = false, button: @Composable () -> Unit) = Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(TimaSpacing.about1),
) {
    button()
    Label(text, Modifier, dim)
}

/** Место высотой с крупную кнопку: переключатель и глаз стоят по её середине. */
@Composable
private fun AtButton(width: androidx.compose.ui.unit.Dp, content: @Composable () -> Unit) = Box(
    Modifier.width(width).height(BIG).wrapContentHeight(unbounded = true),
    contentAlignment = Alignment.Center,
) { content() }

@Composable
private fun Label(text: String, modifier: Modifier, dim: Boolean = false) = Caption(
    text,
    modifier = modifier,
    fontSize = TimaType.sz6,
    weight = FontWeight.Bold,
    color = if (dim) Tima.colors.text3 else Tima.colors.text,
    lineOne = true,
    textAlign = TextAlign.Center,
)

/** Крупная круглая кнопка панели: салатовая — включено, серая — выключено, красная — конец. */
@Composable
private fun BigButton(
    glyph: String,
    on: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = Tima.colors
    Box(
        modifier
            .size(BIG)
            .background(
                when {
                    danger -> colors.alarm
                    !enabled -> colors.quiet
                    on -> colors.navigation
                    else -> colors.line
                },
                CircleShape,
            )
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Caption(glyph, modifier = Modifier.alpha(if (enabled) 1f else DIM), fontSize = TimaType.sz3)
    }
}

/** Переключатель на два места столбиком: горит выбранное; нажатие на другое — переключить. */
@Composable
private fun TwoWay(
    top: @Composable (Boolean) -> Unit,
    bottom: @Composable (Boolean) -> Unit,
    topOn: Boolean,
    onTop: () -> Unit,
    onBottom: () -> Unit,
    enabled: Boolean = true,
) {
    val colors = Tima.colors
    Column(
        Modifier.background(colors.quiet, RoundedCornerShape(50))
            // Переключается и свайпом: вверх — верхнее место, вниз — нижнее (заказчик 2026-10-08).
            .then(if (enabled) Modifier.flipBy(vertical = true, onFirst = onTop, onSecond = onBottom) else Modifier)
            .padding(SWITCH_PAD),
        verticalArrangement = Arrangement.spacedBy(SWITCH_PAD),
    ) {
        for ((icon, lit, tap) in listOf(Triple(top, topOn, onTop), Triple(bottom, !topOn, onBottom))) {
            Box(
                Modifier.size(SWITCH_CELL)
                    .background(if (lit) (if (enabled) colors.navigation else colors.line) else androidx.compose.ui.graphics.Color.Transparent, CircleShape)
                    .then(if (enabled) Modifier.clickable(onClick = tap) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.alpha(if (enabled) 1f else DIM)) { icon(lit) }
            }
        }
    }
}

/**
 * Переключатель на два места меняется и свайпом, а не только нажатием (заказчик 2026-10-08):
 * влево или вверх — первое место, вправо или вниз — второе. Жест свой и поглощается: свайп
 * по переключателю не листает окна.
 */
internal fun Modifier.flipBy(vertical: Boolean, onFirst: () -> Unit, onSecond: () -> Unit): Modifier = composed {
    val first by rememberUpdatedState(onFirst)
    val second by rememberUpdatedState(onSecond)
    pointerInput(vertical) {
        val threshold = FLIP_SWIPE.toPx()
        var moved = 0f
        val end = {
            if (moved <= -threshold) first() else if (moved >= threshold) second()
            moved = 0f
        }
        if (vertical) {
            detectVerticalDragGestures(onDragStart = { moved = 0f }, onDragEnd = end, onDragCancel = { moved = 0f }) { change, shift ->
                moved += shift
                change.consume()
            }
        } else {
            detectHorizontalDragGestures(onDragStart = { moved = 0f }, onDragEnd = end, onDragCancel = { moved = 0f }) { change, shift ->
                moved += shift
                change.consume()
            }
        }
    }
}

@Composable
private fun Glyph(text: String) = Caption(text, fontSize = TimaType.sz5)

/**
 * Значок переключателя камеры, рисованный: рамка картины и в ней человечек (передняя — меня
 * видно) или горы (задняя — видно, что вокруг). Заказчик 2026-10-08: «Смайлик поменять на
 * картину с человечком». Готового знака для этого нет, а рисованный выглядит одинаково везде.
 */
@Composable
private fun PictureIcon(person: Boolean, lit: Boolean) {
    val ink = if (lit) Tima.colors.onAccent else Tima.colors.text2
    Canvas(Modifier.size(ICON)) {
        val w = size.width
        val h = size.height
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.09f)
        drawRoundRect(
            ink,
            topLeft = Offset(w * 0.08f, h * 0.18f),
            size = androidx.compose.ui.geometry.Size(w * 0.84f, h * 0.64f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.1f),
            style = stroke,
        )
        if (person) {
            drawCircle(ink, radius = w * 0.11f, center = Offset(w * 0.5f, h * 0.42f))
            drawArc(
                ink, startAngle = 180f, sweepAngle = 180f, useCenter = true,
                topLeft = Offset(w * 0.3f, h * 0.56f), size = androidx.compose.ui.geometry.Size(w * 0.4f, h * 0.36f),
            )
        } else {
            val hills = androidx.compose.ui.graphics.Path().apply {
                moveTo(w * 0.16f, h * 0.74f)
                lineTo(w * 0.4f, h * 0.44f)
                lineTo(w * 0.56f, h * 0.62f)
                lineTo(w * 0.68f, h * 0.5f)
                lineTo(w * 0.84f, h * 0.74f)
                close()
            }
            drawPath(hills, ink)
            drawCircle(ink, radius = w * 0.07f, center = Offset(w * 0.7f, h * 0.34f))
        }
    }
}

/**
 * Своё окошко поверх кадра (заказчик 2026-10-08): нажатие — поменять местами с собеседником,
 * зажатие — толстая зелёная рамка, и окошко переносится.
 *
 * **Рамка — полем вокруг картинки, а не чертой поверх неё.** Картинка — платформенный вид, и
 * черта Compose поверх него может оказаться под ним; поле вокруг видно всегда. Жесты ловит
 * прозрачный слой сверху: вид картинки нажатия себе не забирает.
 */
@Composable
internal fun Pip(
    video: VideoHandle,
    offset: Offset,
    /** Отступ сверху: окошко стоит ниже строки имени и времени, чтобы их не закрывать. */
    below: androidx.compose.ui.unit.Dp,
    dragging: Boolean,
    onTap: () -> Unit,
    onDrag: (Boolean) -> Unit,
    onMove: (Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .padding(top = below)
            .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
            .padding(TimaSpacing.about3)
            .size(width = PIP_WIDTH, height = PIP_HEIGHT)
            .background(if (dragging) Tima.colors.navigation else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(PIP_RADIUS))
            .testTag(PIP_TAG),
    ) {
        CallVideo(video, Modifier.fillMaxSize().padding(if (dragging) PIP_FRAME else 0.dp))
        Box(
            Modifier.fillMaxSize()
                .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { onDrag(true) },
                        onDragEnd = { onDrag(false) },
                        onDragCancel = { onDrag(false) },
                        onDrag = { change, amount ->
                            change.consume()
                            onMove(amount)
                        },
                    )
                },
        )
    }
}

/**
 * Где окошко может стоять: внутри кадра. Сдвиг считается от правого верхнего угла — там оно
 * стоит сначала, — значит влево он отрицательный, вниз положительный.
 */
internal fun clampPip(wanted: Offset, area: IntSize, pip: Offset): Offset {
    if (area.width == 0 || area.height == 0) return wanted
    val left = -(area.width - pip.x).coerceAtLeast(0f)
    val down = (area.height - pip.y).coerceAtLeast(0f)
    return Offset(wanted.x.coerceIn(left, 0f), wanted.y.coerceIn(0f, down))
}

/** Дозваниваемся: волны от аватара расходятся и гаснут, пока не ответили (заказчик 2026-10-08). */
@Composable
internal fun Dialing(side: androidx.compose.ui.unit.Dp = WAVES, content: @Composable () -> Unit) {
    val color = Tima.colors.navigation
    val wave by rememberInfiniteTransition(label = "вызов").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(WAVE_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "волна",
    )
    Box(contentAlignment = Alignment.Center, modifier = Modifier.testTag(DIALING_TAG)) {
        Canvas(Modifier.size(side)) {
            val base = size.minDimension / 4
            for (i in 0 until 2) {
                val t = (wave + i / 2f) % 1f
                drawCircle(
                    color = color.copy(alpha = (1f - t) * 0.45f),
                    radius = base + (size.minDimension / 2 - base) * t,
                )
            }
        }
        content()
    }
}

/** Крупная кнопка панели. */
private val BIG = 54.dp

/** Место маленького глаза между микрофоном и камерой. */
private val EYE_WIDTH = 36.dp

/** Место переключателя на два места: ячейка и поля вокруг. */
private val SWITCH_WIDTH = 34.dp

/** Один промежуток между соседями группы «Звук · глаз · Камера». */
private val GAP = 10.dp

/** Столбец «Завершить». */
private val HANG_UP_COLUMN = 84.dp

private val SWITCH_CELL = 28.dp
private val SWITCH_PAD = 3.dp

/** Сколько провести по переключателю, чтобы он сменился: меньше кнопки, больше дрожи пальца. */
private val FLIP_SWIPE = 16.dp

/** Прозрачность недоступного значка. */
private const val DIM = 0.35f

/** Значок внутри места переключателя. */
private val ICON = 20.dp

/**
 * Размер своего изображения в углу.
 *
 * Пропорция 3:4 — портретная, как держат телефон. Ширина выбрана так, чтобы плашка
 * читалась («я в кадре, свет есть»), но не спорила с лицом собеседника: смотреть человек
 * должен на него, а не на себя.
 */
internal val PIP_WIDTH = 96.dp
internal val PIP_HEIGHT = 128.dp
private val PIP_RADIUS = 10.dp
private val PIP_FRAME = 4.dp

private val WAVES = 200.dp
private const val WAVE_MS = 1_600

const val TALK_PANEL_TAG: String = "call:panel"
const val EYE_TAG: String = "call:eye"
const val CAMERA_TAG: String = "call:camera"
const val PIP_TAG: String = "call:pip"
const val DIALING_TAG: String = "call:dialing"
