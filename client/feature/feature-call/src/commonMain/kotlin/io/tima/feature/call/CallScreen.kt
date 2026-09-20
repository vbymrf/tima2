package io.tima.feature.call

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.tima.core.call.CallEvent
import io.tima.core.call.CallQuality
import io.tima.core.call.CallStage
import io.tima.core.call.CallState
import io.tima.core.call.VideoHandle
import io.tima.core.ui.Avatar
import io.tima.core.ui.AvatarSize
import io.tima.core.ui.Button
import io.tima.core.ui.ButtonKind
import io.tima.core.ui.Caption
import io.tima.core.ui.InCenter
import io.tima.core.ui.Name
import io.tima.core.ui.Secondary
import io.tima.core.ui.Tertiary
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.words

/**
 * Окно звонка — окно 0 (макет `doc/doc_UI/21-call.md`, ПЛАН-СТЕНДА-ЗВОНКОВ С2).
 *
 * **Экран чистый.** Он рисует [CallState] и зовёт обратные вызовы; ни LiveKit, ни сети не
 * видит. Поэтому его можно снять в проверках целиком, не поднимая ни одного звонка, — и
 * поэтому смена исполнения (Android, ПК, iOS) его не касается.
 *
 * ── ЧЕТЫРЕ СОСТОЯНИЯ, И ОНИ РАЗНЫЕ ПО СМЫСЛУ ────────────────────────────────
 *
 * | Что | Кнопки |
 * |---|---|
 * | **входящий** — нам звонят, мы ещё не ответили | Принять · Отклонить |
 * | **исходящий** — звоним мы, там ещё не сняли | Отменить |
 * | **разговор** | микрофон · камера · Завершить |
 * | **завершён** | Перезвонить · Закрыть |
 *
 * Первые два различает не состояние SFU, а то, **кто начал**: у SFU оба выглядят как
 * `Connecting`. Поэтому [incoming] приходит снаружи, из сигналинга, и вычислять его здесь
 * нечем.
 *
 * ── ПОЧЕМУ ВИДЕО ЗДЕСЬ НЕ РИСУЕТСЯ ──────────────────────────────────────────
 *
 * Картинка собеседника — платформенная поверхность (`SurfaceViewRenderer` на Android), и
 * общего Compose-вида у неё нет. Экран оставляет под неё место и рисует аватар, пока её
 * нет; сама поверхность встанет туда отдельным шагом, когда появится окно в приложении.
 */
@Composable
fun CallScreen(
    state: CallState,
    /** Как зовут собеседника. Пусто — «Без имени»: выдумывать нечего. */
    peer: String,
    /** Нам звонят (а не мы). Решает сигналинг, не SFU: у того оба случая одинаковы. */
    incoming: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onHangUp: () -> Unit,
    onMicrophone: (Boolean) -> Unit,
    onCamera: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /** Сколько идёт разговор, секунды. Считает не экран: время — не его дело. */
    seconds: Int = 0,
    onCallAgain: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    /** Что случилось за звонок — полоса в самом верху (ЗВ10). Пусто — полосы нет. */
    events: List<CallEvent> = emptyList(),
    /** Картинка собеседника. `null` — он себя не показывает или мы отписались. */
    remoteVideo: VideoHandle? = null,
    /** Своя картинка — плашкой в углу. `null` — камера выключена. */
    localVideo: VideoHandle? = null,
    /** Принимать ли чужое видео (ЗВ11). `null` — кнопки нет: показывать нечего. */
    onRemoteVideo: ((Boolean) -> Unit)? = null,
) {
    val colors = Tima.colors
    val words = Tima.words.call
    Column(
        modifier = modifier.fillMaxSize().background(colors.surface),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Полоса событий — в самом верху, над всем остальным: это то, что случилось, и
        // читается оно первым (ЗВ10).
        CallEvents(events)

        Box(Modifier.weight(1f).fillMaxWidth()) {
            // Картинка собеседника во весь кадр, если он себя показывает. Аватар и имя
            // под ней не рисуются: они отвечают на тот же вопрос «с кем говорю», и
            // повторять его поверх лица незачем.
            if (remoteVideo != null) {
                CallVideo(remoteVideo, Modifier.fillMaxSize())
            }

            if (remoteVideo == null) {
            InCenter(Modifier.fillMaxSize()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
                ) {
                    Avatar(letters = letters(peer), size = AvatarSize.Big)
                    Name(peer.ifBlank { Tima.words.chat.nameless })
                    Secondary(under(state, incoming, seconds))

                    // Оценка связи — от SFU, своей не считаем. Пока не сказали — молчим:
                    // «связь выясняем» на каждом звонке было бы шумом.
                    if (state.quality != CallQuality.Unknown) {
                        Tertiary(words.quality(state.quality.name), lineOne = true)
                    }

                    // Видео погасил SFU (allow_pause: true с 2026-09-19). Человеку надо
                    // сказать: пропавшая без объяснения картинка читается как поломка.
                    if (state.videoPaused) {
                        Caption(
                            words.videoPaused,
                            fontSize = TimaType.sz5,
                            weight = FontWeight.SemiBold,
                            color = colors.alarm,
                        )
                    }

                    // Что происходит прямо сейчас: камеру не разрешили, собеседник
                    // показывает себя. Звонок при этом идёт — потому и не `trouble`.
                    state.notice?.let {
                        Caption(
                            it,
                            fontSize = TimaType.sz5,
                            weight = FontWeight.SemiBold,
                            color = colors.alarm,
                        )
                    }

                    state.trouble?.takeIf { state.stage == CallStage.Ended }?.let {
                        Tertiary(it, lineOne = true)
                    }
                }
            }
            }

            // Своё изображение — плашкой в углу, как в макете (`[[ Вы (PIP) ]]`).
            // Маленькое и сверху справа: человек проверяет им, что он в кадре, а не
            // смотрит на себя.
            if (localVideo != null) {
                CallVideo(
                    localVideo,
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(TimaSpacing.about3)
                        .size(width = PIP_WIDTH, height = PIP_HEIGHT),
                )
            }
        }

        // ── РЯД, КОТОРЫЙ ПЕРЕНОСИТСЯ ────────────────────────────────────────
        //
        // Был `Row`, и в разговоре он **терял «Завершить»**: четыре кнопки с полными
        // подписями («Микрофон включён», «Камера выключена», «Скрыть видео»,
        // «Завершить») в 380 точек ширины не влезают, а `Row` не переносит — он режет по
        // краю. Последняя кнопка и уезжала за экран; положить трубку было нечем
        // (живой прогон 2026-09-20).
        //
        // Подписи при этом оставлены полными: включён микрофон или выключен, человек
        // читает словами, а не угадывает по цвету. Перенос дешевле краткости.
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(TimaSpacing.about4),
            horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
        ) {
            when {
                state.stage == CallStage.Ended -> {
                    onCallAgain?.let { Button(label = words.callAgain, onClick = it) }
                    onClose?.let { Button(label = words.close, kind = ButtonKind.Quiet, onClick = it) }
                }

                incoming && state.stage != CallStage.Connected -> {
                    Button(label = words.accept, onClick = onAccept)
                    Button(label = words.decline, kind = ButtonKind.Dangerous, onClick = onDecline)
                }

                state.stage != CallStage.Connected -> {
                    // Исходящий до ответа: одна кнопка. «Завершить» тут не годится —
                    // завершать ещё нечего, разговор не начался.
                    Button(label = words.cancel, kind = ButtonKind.Dangerous, onClick = onHangUp)
                }

                else -> {
                    Button(
                        label = if (state.microphoneOn) words.microphoneOn else words.microphoneOff,
                        kind = if (state.microphoneOn) ButtonKind.Action else ButtonKind.Quiet,
                        onClick = { onMicrophone(!state.microphoneOn) },
                    )
                    Button(
                        label = if (state.cameraOn) words.cameraOn else words.cameraOff,
                        kind = if (state.cameraOn) ButtonKind.Action else ButtonKind.Quiet,
                        onClick = { onCamera(!state.cameraOn) },
                    )
                    // «Скрыть видео» появляется, только когда есть что скрывать, — и
                    // остаётся, пока скрыто: иначе вернуть картинку было бы нечем.
                    if (onRemoteVideo != null && (state.remoteVideoShown || !state.remoteVideoTaken)) {
                        Button(
                            label = if (state.remoteVideoTaken) words.hideRemote else words.showRemote,
                            kind = ButtonKind.Quiet,
                            onClick = { onRemoteVideo(!state.remoteVideoTaken) },
                        )
                    }
                    Button(label = words.hangUp, kind = ButtonKind.Dangerous, onClick = onHangUp)
                }
            }
        }
    }
}

/**
 * Строка под именем.
 *
 * Разговор показывает **время**, всё остальное — словами, что происходит. Время до ответа
 * было бы враньём: считать ещё нечего.
 */
@Composable
private fun under(state: CallState, incoming: Boolean, seconds: Int): String {
    val words = Tima.words.call
    return when (state.stage) {
        // До комнаты и в комнате — разные вещи, и человеку они разные. «Соединяем…» —
        // мы ещё идём; «Звоним…» — мы на месте и ждём ответа. Раньше обе назывались
        // одинаково, и ждать было непонятно чего.
        CallStage.Idle, CallStage.Connecting -> when {
            incoming -> words.incoming
            state.inRoom -> words.calling
            else -> words.connecting
        }
        // Время идёт только в разговоре: до ответа считать нечего, и показанные там
        // секунды были бы выдумкой. Ровно это и случилось на первом живом звонке.
        CallStage.Connected -> words.duration(seconds)
        CallStage.Reconnecting -> words.reconnecting
        CallStage.Ended -> if (state.peerLeft) words.peerLeft else words.ended
    }
}

/** Буквы аватара: как в списках — не больше двух, из имени. */
private fun letters(peer: String): String = peer.trim()
    .split(" ")
    .take(2)
    .mapNotNull { it.firstOrNull()?.uppercase() }
    .joinToString("")
    .ifEmpty { "+" }

/**
 * Размер своего изображения в углу.
 *
 * Пропорция 3:4 — портретная, как держат телефон. Ширина выбрана так, чтобы плашка
 * читалась («я в кадре, свет есть»), но не спорила с лицом собеседника: смотреть человек
 * должен на него, а не на себя.
 */
private val PIP_WIDTH = 96.dp
private val PIP_HEIGHT = 128.dp
