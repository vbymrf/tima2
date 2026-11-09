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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
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
)

/** Место панели звонка: корень рисует, `App()` кладёт — звонок живёт там. */
internal val LocalCallStrip = staticCompositionLocalOf<MutableState<CallStripModel?>?> { null }

@Composable
internal fun CallStrip(model: CallStripModel) {
    val call = model.call
    val colors = Tima.colors
    val words = Tima.words.call
    val moving = rememberInfiniteTransition()
    // Штрих и пропуск — 18 и 10 точек; сдвиг на их сумму за 1,2 с — штрихи бегут по кругу без рывка.
    val run by moving.animateFloat(0f, 1f, infiniteRepeatable(tween(1_200, easing = LinearEasing)))
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
                        val dash = 18.dp.toPx()
                        val gap = 10.dp.toPx()
                        drawRoundRect(
                            color = colors.confirmed,
                            topLeft = Offset(stroke / 2, stroke / 2),
                            size = Size(size.width - stroke, size.height - stroke),
                            cornerRadius = CornerRadius(14.dp.toPx()),
                            style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, gap), -run * (dash + gap))),
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
                Box(
                    Modifier.size(36.dp).background(colors.alarm, CircleShape).clickable { call.hangUp() }.testTag(CALL_STRIP_END_TAG),
                    contentAlignment = Alignment.Center,
                ) { Caption("✆", fontSize = TimaType.sz4, weight = FontWeight.Bold, color = colors.onAccent) }
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

const val CALL_STRIP_TAG = "strip:call"
const val CALL_STRIP_MIC_TAG = "strip:call.mic"
const val CALL_STRIP_END_TAG = "strip:call.end"
