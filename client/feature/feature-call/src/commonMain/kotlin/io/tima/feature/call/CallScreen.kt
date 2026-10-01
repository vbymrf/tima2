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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.tima.core.call.CallAction
import io.tima.core.call.CallEvent
import io.tima.core.call.CallQuality
import io.tima.core.call.CallStage
import io.tima.core.call.CallState
import io.tima.core.call.SoundRoute
import io.tima.core.call.VideoHandle
import io.tima.core.ui.Avatar
import io.tima.core.ui.AvatarSize
import io.tima.core.ui.Button
import io.tima.core.ui.ButtonKind
import io.tima.core.ui.Caption
import io.tima.core.ui.IconButton
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
 * | **разговор** | динамик · микрофон · камера · переключение камеры · скрыть видео · Завершить |
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
    /**
     * Принять входящий. `null` — **на этом устройстве нечем говорить** (ПК0): движка звонка
     * нет. Тогда вместо кнопки — строка «ответьте на телефоне». До 2026-09-26 кнопка
     * стояла всегда и на ПК не делала ничего: звонящий слушал гудки до 50-й секунды.
     */
    onAccept: (() -> Unit)?,
    onDecline: () -> Unit,
    onHangUp: () -> Unit,
    onMicrophone: (Boolean) -> Unit,
    onCamera: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /** Вызов дошёл до телефона собеседника — «Звонит» вместо «Вызов…» (ВЗ0а). */
    peerRinging: Boolean = false,
    /** Сколько идёт разговор, секунды. Считает не экран: время — не его дело. */
    seconds: Int = 0,
    onCallAgain: (() -> Unit)? = null,
    /**
     * Чем перезвонить: `true` — видео. Переменная окна 0, а не настройка: её ставит то,
     * как окно открыли, и ведёт камера в разговоре (заказчик 2026-09-25).
     */
    redialVideo: Boolean = false,
    /** Переключатель «голос · видео» справа от «Перезвонить». `null` — его нет. */
    onRedialKind: ((Boolean) -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    /** Что случилось за звонок — полоса в самом верху (ЗВ10). Пусто — полосы нет. */
    events: List<CallEvent> = emptyList(),
    /**
     * Идущий забег стенда: номер набора и последние числа. `null` — забега нет, и
     * полосы тоже (обычный звонок при выключенном испытательном режиме).
     */
    bench: BenchLine? = null,
    /** Картинка собеседника. `null` — он себя не показывает или мы отписались. */
    remoteVideo: VideoHandle? = null,
    /**
     * Рисовать картинку собеседника здесь. `false` — на широком формате она уходит в
     * область 3 (заказчик 2026-09-26), а здесь остаются кнопки, аватар и своё окошко.
     */
    remoteHere: Boolean = true,
    /** Своя картинка — плашкой в углу. `null` — камера выключена. */
    localVideo: VideoHandle? = null,
    /** Принимать ли чужое видео (ЗВ11). `null` — кнопки нет: показывать нечего. */
    onRemoteVideo: ((Boolean) -> Unit)? = null,
    /** Сделать то, что предлагает событие: уйти в настройки телефона. */
    onEventAction: ((CallAction) -> Unit)? = null,
    /**
     * «Динамик» — громкая или разговорный (ПЛАН-ВИДЕО.md В9). Кнопка стоит, только когда
     * движок знает, куда идёт звук: на ПК колонки выбираются в настройках.
     */
    onSpeaker: ((Boolean) -> Unit)? = null,
    /** «Переключение камеры». Стоит, только когда камер больше одной. */
    onSwitchCamera: (() -> Unit)? = null,
    /**
     * Групповой звонок (ПЛАН-ГРУППОВЫХ-ЗВОНКОВ ГЗ4): сетка участников вместо одного
     * собеседника. `null` — звонок на двоих.
     */
    group: GroupStage? = null,
) {
    val colors = Tima.colors
    val words = Tima.words.call
    var askHangUp by remember { mutableStateOf(false) }
    Column(
        modifier = modifier.fillMaxSize().background(colors.surface),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Полоса забега выше ленты событий: номер прогона — это про то, ЧТО сейчас
        // меряется, и без него лента про камеру и микрофон читается не о том.
        bench?.let { BenchStrip(it) }

        // Полоса событий — в самом верху, над всем остальным: это то, что случилось, и
        // читается оно первым (ЗВ10).
        CallEvents(events, onAction = onEventAction)

        // Групповой в разговоре — сетка участников, своя картинка — одной из клеток.
        if (group != null && state.stage == CallStage.Connected) {
            Column(Modifier.weight(1f).fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about1),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Name(group.title)
                    Secondary(Tima.words.groupCall.count(group.count, group.max) + " · " + words.duration(seconds))
                }
                if (group.paused) {
                    Caption(
                        Tima.words.groupCall.pausedBanner,
                        modifier = Modifier.padding(horizontal = TimaSpacing.about4),
                        fontSize = TimaType.sz5,
                        weight = FontWeight.SemiBold,
                        color = colors.alarm,
                    )
                }
                if (group.tiles.size <= 1) {
                    Tertiary(Tima.words.groupCall.alone, modifier = Modifier.padding(horizontal = TimaSpacing.about4))
                }
                // Широкий формат: сетка — в области 3, рядом с окном (как видео собеседника).
                if (remoteHere) {
                    GroupCallGrid(group.tiles, Modifier.weight(1f).fillMaxWidth().padding(TimaSpacing.about2))
                } else {
                    Box(Modifier.weight(1f))
                }
            }
        } else
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // Картинка собеседника во весь кадр, если он себя показывает. Аватар и имя
            // под ней не рисуются: они отвечают на тот же вопрос «с кем говорю», и
            // повторять его поверх лица незачем.
            val remoteShown = remoteVideo?.takeIf { remoteHere }
            if (remoteShown != null) {
                CallVideo(remoteShown, Modifier.fillMaxSize())
            }

            if (remoteShown == null) {
            InCenter(Modifier.fillMaxSize()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
                ) {
                    Avatar(letters = letters(peer), size = AvatarSize.Big)
                    Name(peer.ifBlank { Tima.words.chat.nameless })
                    Secondary(under(state, incoming, seconds, peerRinging))

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
                // Создатель кладёт трубку — выйти самому или завершить для всех (решение 6).
                askHangUp && group != null && state.stage == CallStage.Connected -> {
                    Secondary(Tima.words.groupCall.hangUpQuestion)
                    Button(label = Tima.words.groupCall.leave, kind = ButtonKind.Quiet, onClick = {
                        askHangUp = false
                        onHangUp()
                    })
                    Button(label = Tima.words.groupCall.stopAll, kind = ButtonKind.Dangerous, onClick = {
                        askHangUp = false
                        group.onStopAll()
                    })
                    Button(label = words.cancel, kind = ButtonKind.Quiet, onClick = { askHangUp = false })
                }

                state.stage == CallStage.Ended -> {
                    onCallAgain?.let { Button(label = words.callAgain, onClick = it) }
                    if (onCallAgain != null && onRedialKind != null) {
                        KindSwitch(video = redialVideo, onPick = onRedialKind)
                    }
                    onClose?.let { Button(label = words.close, kind = ButtonKind.Quiet, onClick = it) }
                }

                incoming && state.stage != CallStage.Connected -> {
                    if (onAccept != null) {
                        Button(label = words.accept, onClick = onAccept)
                    } else {
                        Secondary(words.noEngineHere)
                    }
                    Button(label = words.decline, kind = ButtonKind.Dangerous, onClick = onDecline)
                }

                state.stage != CallStage.Connected -> {
                    // Исходящий до ответа: одна кнопка. «Завершить» тут не годится —
                    // завершать ещё нечего, разговор не начался.
                    Button(label = words.cancel, kind = ButtonKind.Dangerous, onClick = onHangUp)
                }

                else -> {
                    // ── РАЗГОВОР: ЗНАЧКИ, А НЕ ПОДПИСИ ──────────────────────
                    //
                    // **Управление обязано выглядеть одинаково с видео и без.** С
                    // подписями оно так не могло: «Микрофон включён», «Камера включена»,
                    // «Скрыть видео» и «Завершить» в одну строку не влезают, ряд
                    // переносился, и кнопки переезжали с места на место — а с видео
                    // «Завершить» уходила за нижний край и положить трубку было нечем
                    // (заказчик 2026-09-20).
                    //
                    // Четыре круглых значка влезают всегда и стоят на одних и тех же
                    // местах при любом состоянии. Состояние несёт цвет: салатовый —
                    // включено, серый — выключено. Что именно случилось, словами говорит
                    // полоса событий сверху, и там на это есть место.
                    // Порядок — решение заказчика 2026-09-29: «Динамик», «Микрофон»,
                    // «Камера», «Переключение камеры», дальше прежние (ПЛАН-ВИДЕО.md В9).
                    // Горит — громкая связь; наушники — свой значок, нажатие ведёт в громкую.
                    if (onSpeaker != null && state.sound != SoundRoute.Unknown) {
                        CallButton(
                            glyph = when (state.sound) {
                                SoundRoute.Speaker -> "🔊"
                                SoundRoute.Headset -> "🎧"
                                else -> "🔈"
                            },
                            on = state.sound == SoundRoute.Speaker,
                            onClick = { onSpeaker(state.sound != SoundRoute.Speaker) },
                        )
                    }
                    CallButton(
                        glyph = if (state.microphoneOn) "🎤" else "🔇",
                        on = state.microphoneOn,
                        onClick = { onMicrophone(!state.microphoneOn) },
                    )
                    CallButton(
                        glyph = "📹",
                        on = state.cameraOn,
                        onClick = { onCamera(!state.cameraOn) },
                    )
                    // Действие, а не состояние: горит, когда снимает задняя.
                    if (onSwitchCamera != null && state.cameraSwitchable) {
                        CallButton(glyph = "🔄", on = !state.cameraFront, onClick = onSwitchCamera)
                    }
                    // Принимать ли чужое видео — **решение, а не действие**, и потому
                    // кнопка стоит всегда, а не появляется вместе с картинкой. Нажали
                    // заранее — чужая камера, включённая потом, к нам не приедет и
                    // трафика не съест.
                    if (onRemoteVideo != null) {
                        CallButton(
                            glyph = if (state.remoteVideoTaken) "👁" else "🙈",
                            on = state.remoteVideoTaken,
                            onClick = { onRemoteVideo(!state.remoteVideoTaken) },
                        )
                    }
                    // Групповой: журнал звонка — кто в нём и команды (ГЗ6).
                    if (group != null) {
                        CallButton(glyph = "👥", on = true, onClick = group.onParticipants)
                    }
                    CallButton(
                        glyph = "📞",
                        on = false,
                        danger = true,
                        onClick = { if (group?.mine == true) askHangUp = true else onHangUp() },
                    )
                }
            }
        }
    }
}

/**
 * Групповой звонок для окна 0 (ГЗ4).
 *
 * @param tiles клетки сетки: участники и я; у кого видео — картинка, у кого нет — аватар.
 * @param mine я создатель: «Завершить» спрашивает «выйти или для всех».
 */
data class GroupStage(
    val title: String,
    val tiles: List<GroupTile>,
    val count: Int,
    val max: Int,
    val paused: Boolean,
    val mine: Boolean,
    val onParticipants: () -> Unit,
    val onStopAll: () -> Unit,
)

/** Клетка сетки группового звонка. */
data class GroupTile(
    val key: String,
    val name: String,
    val letters: String,
    val video: VideoHandle?,
    val microphoneOn: Boolean,
    val speaking: Boolean,
    val paused: Boolean,
    val self: Boolean,
    /** Видео участника не приходит или не раскодируется — словами на его клетке; `null` — всё в порядке. */
    val videoTrouble: String? = null,
)

/**
 * Сетка: один — во весь кадр, до четырёх — 2×2, до девяти — 3×3, больше — по четыре в
 * ряд (видео там нет по правилам, только аватары). Говорящий — в рамке.
 */
@Composable
fun GroupCallGrid(tiles: List<GroupTile>, modifier: Modifier) {
    val columns = when {
        tiles.size <= 1 -> 1
        tiles.size <= 4 -> 2
        tiles.size <= 9 -> 3
        else -> 4
    }
    val rows = tiles.chunked(columns)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(TimaSpacing.about1)) {
        for (row in rows) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about1)) {
                for (tile in row) GroupCell(tile, Modifier.weight(1f).fillMaxHeight())
                repeat(columns - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun GroupCell(tile: GroupTile, modifier: Modifier) {
    val colors = Tima.colors
    Box(
        modifier
            .background(colors.functional)
            .border(width = if (tile.speaking) 3.dp else 0.dp, color = if (tile.speaking) colors.navigation else colors.functional),
    ) {
        if (tile.video != null) {
            CallVideo(tile.video, Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Avatar(letters = tile.letters, size = AvatarSize.Big) }
        }
        tile.videoTrouble?.let {
            Caption(
                it,
                modifier = Modifier.align(Alignment.TopStart).background(colors.surface.copy(alpha = 0.8f))
                    .padding(horizontal = TimaSpacing.about2, vertical = TimaSpacing.about1),
                fontSize = TimaType.sz6,
                weight = FontWeight.SemiBold,
                color = colors.alarm,
            )
        }
        val marks = (if (!tile.microphoneOn) " 🔇" else "") + (if (tile.paused) " ⏸" else "")
        Caption(
            tile.name + marks,
            modifier = Modifier.align(Alignment.BottomStart).background(colors.surface.copy(alpha = 0.7f))
                .padding(horizontal = TimaSpacing.about2, vertical = TimaSpacing.about1),
            fontSize = TimaType.sz5,
            weight = FontWeight.SemiBold,
            lineOne = true,
        )
    }
}

/**
 * Круглая кнопка управления разговором.
 *
 * Своя, а не [io.tima.core.ui.IconButton] напрямую: у той два цвета задаются по
 * отдельности, и четыре вызова подряд с одинаковыми хвостами разошлись бы при первой же
 * правке. Здесь состояние — одно слово: включено или нет.
 */
@Composable
private fun CallButton(glyph: String, on: Boolean, onClick: () -> Unit, danger: Boolean = false) {
    val colors = Tima.colors
    IconButton(
        glyph = glyph,
        onClick = onClick,
        live = on,
        background = if (danger) colors.alarm else null,
        colorGlyph = if (danger) colors.onAccent else null,
    )
}

/**
 * Переключатель «голос · видео» — два значка, горит выбранный.
 *
 * Одним `Row`, а не двумя кнопками в общем ряду: `FlowRow` при переносе разнёс бы их по
 * строкам, и переключатель перестал бы читаться как один. Значки те же, что у микрофона и
 * камеры в разговоре, — человек их уже знает, и подписи не нужны.
 */
@Composable
private fun KindSwitch(video: Boolean, onPick: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about1)) {
        CallButton(glyph = "🎤", on = !video, onClick = { onPick(false) })
        CallButton(glyph = "📹", on = video, onClick = { onPick(true) })
    }
}

/**
 * Строка под именем.
 *
 * Разговор показывает **время**, всё остальное — словами, что происходит. Время до ответа
 * было бы враньём: считать ещё нечего.
 */
@Composable
private fun under(state: CallState, incoming: Boolean, seconds: Int, peerRinging: Boolean): String {
    val words = Tima.words.call
    return when (state.stage) {
        // До комнаты и в комнате — разные вещи, и человеку они разные. «Соединяем…» —
        // мы ещё идём; «Звоним…» — мы на месте и ждём ответа. Раньше обе назывались
        // одинаково, и ждать было непонятно чего.
        CallStage.Idle, CallStage.Connecting -> when {
            incoming -> words.incoming
            state.inRoom -> if (peerRinging) words.ringing else words.calling
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
