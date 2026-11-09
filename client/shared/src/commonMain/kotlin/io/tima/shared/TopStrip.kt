package io.tima.shared

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.tima.core.call.CallStage
import io.tima.core.ui.Caption
import io.tima.core.ui.ProvidePlace
import io.tima.core.ui.TextPlace
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaType
import io.tima.core.ui.words

/**
 * Панель над шапкой — звонок (ПЛАН-(ВО) ВО1, проба 1в, заказчик 2026-11-09): белая, по краю бегут по кругу зелёные
 * штрихи с пропусками; точка «идёт», с кем, что со звонком, микрофон, «Завершить». Нажатие на панель — в окно звонка.
 *
 * Стоит там же, где полоса «сейчас играет», и вместо неё: звонок важнее звука (Р1). Заменила салатовую плашку
 * «Активный звонок» под шапкой окна (`ActiveCallBadge`): та была только в главных окнах, эта — над любым подокном.
 */
internal class CallStripModel(
    val call: CallHost,
    /** Показывать ли — звонок идёт и человек не в окне звонка. */
    val shown: () -> Boolean,
    val onOpen: () -> Unit,
    /** «Завершить» с панели — положить трубку и закрыть звонок: окна «Звонок завершён» человек не видит. */
    val onEnd: () -> Unit,
)

/** Место панели звонка: корень рисует, `App()` кладёт — звонок живёт там. */
internal val LocalCallStrip = staticCompositionLocalOf<MutableState<CallStripModel?>?> { null }

@Composable
internal fun CallStrip(model: CallStripModel) {
    val call = model.call
    val colors = Tima.colors
    val words = Tima.words.call
    val moving = rememberInfiniteTransition()
    // Как в пробе 1в (заказчик 2026-11-10): узор лучами из центра крутится по кругу — на длинной полосе штрихи у
    // краёв длинные и бегут быстро, у середины короткие и медленнее. Оборот — 6 с (заказчик 2026-11-10: было 2 с,
    // потом 4 с; в пробе — 3 с). Цвет — шапки (`navigation`), а не «подтверждено».
    val turn by moving.animateFloat(0f, 1f, infiniteRepeatable(tween(STRIP_TURN_MS, easing = LinearEasing)))
    val blink by moving.animateFloat(1f, 0.25f, infiniteRepeatable(tween(600), RepeatMode.Reverse))
    val title = call.group?.title ?: call.peer
    val status = stripStatus(call, words)
    val micOn = call.state.microphoneOn
    ProvidePlace(TextPlace.HEADERS) {
        Box(Modifier.fillMaxWidth().background(colors.surface).statusBarsPadding().padding(horizontal = 6.dp, vertical = 4.dp)) {
            Row(
                Modifier.fillMaxWidth()
                    .drawBehind {
                        val stroke = 3.dp.toPx()
                        drawRoundRect(
                            brush = Brush.sweepGradient(*rays(turn, colors.navigation), center = center),
                            topLeft = Offset(stroke / 2, stroke / 2),
                            size = Size(size.width - stroke, size.height - stroke),
                            cornerRadius = CornerRadius(14.dp.toPx()),
                            style = Stroke(width = stroke),
                        )
                    }
                    .clickable(onClick = model.onOpen)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .testTag(CALL_STRIP_TAG),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(10.dp).alpha(blink).background(colors.confirmed, CircleShape))
                Column(Modifier.weight(1f)) {
                    Caption(title, fontSize = TimaType.sz4, weight = FontWeight.Bold, lineOne = true)
                    Caption(status, fontSize = TimaType.sz6, color = colors.text2, lineOne = true)
                }
                // Микрофон выключен — значок зачёркнут (Р11).
                Box(
                    Modifier.size(36.dp).background(colors.functional, CircleShape)
                        .clickable { call.microphone(!micOn) }
                        .drawWithContent {
                            drawContent()
                            if (!micOn) drawLine(colors.alarm, Offset(size.width * 0.25f, size.height * 0.25f), Offset(size.width * 0.75f, size.height * 0.75f), 2.5.dp.toPx())
                        }
                        .testTag(CALL_STRIP_MIC_TAG),
                    contentAlignment = Alignment.Center,
                ) { Caption("🎤", fontSize = TimaType.sz5) }
                // Микрофон — вдвое дальше от «Завершить», чем прочие промежутки: мимо красной не промахнуться (2026-11-10).
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier.size(END_SIZE).background(colors.alarm, CircleShape).clickable(onClick = model.onEnd).testTag(CALL_STRIP_END_TAG),
                    contentAlignment = Alignment.Center,
                ) {
                    // Знак — 80 % кнопки (заказчик 2026-11-10).
                    val glyph = with(LocalDensity.current) { (END_SIZE * 0.8f).toSp() }
                    BasicText("✆", style = TextStyle(fontSize = glyph, lineHeight = glyph, color = colors.onAccent))
                }
            }
        }
    }
}

/**
 * Что со звонком — словами (Р11): входящий, вызов (никто ещё не ответил), соединяется (связь восстанавливается),
 * идёт со временем; у группового — сколько в комнате.
 */
internal fun stripStatus(call: CallHost, words: io.tima.core.words.CallWords): String =
    stripStatus(call.incoming, call.state.stage, call.seconds, call.state.others.size, call.group != null, words)

internal fun stripStatus(
    incoming: Boolean,
    stage: CallStage,
    seconds: Int,
    others: Int,
    group: Boolean,
    words: io.tima.core.words.CallWords,
): String {
    val people = if (group) " · " + words.stripPeople(others + 1) else ""
    return when {
        incoming && stage != CallStage.Connected -> words.stripIncoming
        stage == CallStage.Reconnecting || stage == CallStage.Connecting -> words.stripConnecting
        seconds == 0 && others == 0 -> words.stripCalling + people
        else -> words.stripTalking + " · " + words.duration(seconds) + people
    }
}

/** Узор рамки: 12 лучей по 18° с пропусками по 12°, повёрнутый на [turn] оборота; жёсткие края — парами точек. */
internal fun rays(turn: Float, color: Color): Array<Pair<Float, Color>> {
    val clear = color.copy(alpha = 0f)
    val parts = mutableListOf<Pair<Float, Float>>() // начало и конец луча в долях оборота
    for (k in 0 until RAYS) {
        val a = ((k * RAY_STEP + turn * 360f) % 360f) / 360f
        val b = a + RAY_ON / 360f
        if (b <= 1f) {
            parts += a to b
        } else {
            parts += a to 1f
            parts += 0f to (b - 1f)
        }
    }
    parts.sortBy { it.first }
    val stops = mutableListOf(0f to clear)
    for ((a, b) in parts) {
        stops += a to clear
        stops += a to color
        stops += b to color
        stops += b to clear
    }
    stops += 1f to clear
    return stops.toTypedArray()
}

private const val RAYS = 12
private const val RAY_STEP = 30f
private const val RAY_ON = 18f
private const val STRIP_TURN_MS = 6_000
private val END_SIZE = 36.dp

const val CALL_STRIP_TAG = "strip:call"
const val CALL_STRIP_MIC_TAG = "strip:call.mic"
const val CALL_STRIP_END_TAG = "strip:call.end"
