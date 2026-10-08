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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
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
import io.tima.core.ui.Secondary
import io.tima.core.ui.Tertiary
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.words

/**
 * Окно звонка — окно 0 (макет `doc/doc_UI/21-call.md`, ПЛАН-(С)-СТЕНДА-ЗВОНКОВ С2).
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
     * «Динамик» — громкая или разговорный (ПЛАН-(В)-ВИДЕО.md В9). Кнопка стоит, только когда
     * движок знает, куда идёт звук: на ПК колонки выбираются в настройках.
     */
    onSpeaker: ((Boolean) -> Unit)? = null,
    /** «Переключение камеры». Стоит, только когда камер больше одной. */
    onSwitchCamera: (() -> Unit)? = null,
    /**
     * Групповой звонок (ПЛАН-(ГЗ)-ГРУППОВЫХ-ЗВОНКОВ ГЗ4): сетка участников вместо одного
     * собеседника. `null` — звонок на двоих.
     */
    group: GroupStage? = null,
    /**
     * Собеседника нет в книге (заказчик 2026-10-08): перед именем — «Незнакомый» светлым
     * оранжевым, как пропущенный; без имени и ника — одно это слово.
     */
    stranger: Boolean = false,
    /** Телефон собеседника — второй строкой под именем; `null` — не знаем (2026-10-08). */
    peerPhone: String? = null,
    /** Лицо собеседника — настоящий аватар в голосовом звонке; `null` — буквы. */
    peerFace: ImageBitmap? = null,
    /**
     * Свайп между окнами — на области картинки и аватара, как у других окон на их
     * содержимом, а не на всём окне (заказчик 2026-10-08): кнопки и переключатели внизу
     * свою горизонталь не отдают. Зону потом выставят точнее — она здесь одна.
     */
    swipeArea: Modifier = Modifier,
    /** Цвет имени собеседника: заблокирован — красный, незнакомый «цветом» — оранжевый; `null` — обычный. */
    peerTint: Color? = null,
) {
    val colors = Tima.colors
    val words = Tima.words.call
    var askHangUp by remember { mutableStateOf(false) }
    var benchJournal by remember { mutableStateOf(false) }
    // Своё окошко: поменяно ли местами с собеседником, куда его перенесли, несут ли сейчас.
    var swapped by remember { mutableStateOf(false) }
    var pipOffset by remember { mutableStateOf(Offset.Zero) }
    var pipDragging by remember { mutableStateOf(false) }
    var area by remember { mutableStateOf(IntSize.Zero) }
    // Высота затемнения с именем, временем и телефоном поверх видео: своё окошко стоит у его
    // нижней границы, на три точки выше (заказчик 2026-10-08) — время видно, места не тратим.
    var overlayPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val pipBelow = with(density) { (overlayPx.toDp() - TimaSpacing.about3 - PIP_ABOVE_SHADE).coerceAtLeast(0.dp) }
    val pipBelowPx = with(density) { pipBelow.roundToPx() }
    val pipSizePx = with(LocalDensity.current) {
        Offset((PIP_WIDTH + TimaSpacing.about3 * 2).toPx(), (PIP_HEIGHT + TimaSpacing.about3 * 2).toPx())
    }
    Column(
        modifier = modifier.fillMaxSize().background(colors.surface),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Полоса забега выше ленты событий: номер прогона — это про то, ЧТО сейчас
        // меряется, и без него лента про камеру и микрофон читается не о том. В групповом —
        // без разворачивания, с «Журналом стенда» по участникам (заказчик 2026-10-01, 5а).
        if (group != null) {
            bench?.let { GroupBenchStrip(it, onJournal = { benchJournal = true }) }
        } else {
            bench?.let { BenchStrip(it) }
        }
        if (group != null && benchJournal) {
            GroupBenchJournal(bench, group.tiles, onClose = { benchJournal = false }, modifier = Modifier.weight(1f))
            return@Column
        }

        // Полоса событий — в самом верху, над всем остальным: это то, что случилось, и
        // читается оно первым (ЗВ10). У идущего группового — кнопкой «развернуть» в его
        // верхней полосе и листом поверх окна (заказчик 2026-10-02).
        val groupLive = group != null && state.stage == CallStage.Connected
        if (!groupLive) CallEvents(events, onAction = onEventAction)

        // Групповой в разговоре — сетка участников, своя картинка — одной из клеток.
        if (group != null && state.stage == CallStage.Connected) {
            Column(Modifier.weight(1f).fillMaxWidth().then(swipeArea)) {
                GroupTopBar(group, group.view, groupPages(gridTiles(group.tiles, group.view), group.view.perPage).size, words.duration(seconds), events.lastOrNull())
                if (group.paused) {
                    Caption(
                        Tima.words.groupCall.pausedBanner,
                        modifier = Modifier.padding(horizontal = TimaSpacing.about4),
                        fontSize = TimaType.sz5,
                        weight = FontWeight.SemiBold,
                        color = colors.alarm,
                    )
                }
                // Телефон: страница и своё окошко здесь. ПК с тремя областями: здесь — себя
                // крупно, остальные — в области 3 (заказчик 2026-10-01).
                if (remoteHere) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        GroupCallBody(group, group.view, withSelf = true, modifier = Modifier.fillMaxSize())
                        // Лист событий — без своей «развернуть»: ею служит кнопка в верхней
                        // полосе (заказчик 2026-10-02).
                        if (group.view.eventsOpen) {
                            CallEvents(events, modifier = Modifier.align(Alignment.TopCenter), onAction = onEventAction, startExpanded = true, toggle = false)
                        }
                    }
                } else {
                    val self = group.tiles.firstOrNull { it.self }
                    if (self != null && group.view.showSelf) {
                        GroupCell(self, Modifier.weight(1f).fillMaxWidth().padding(TimaSpacing.about2))
                    } else {
                        Box(Modifier.weight(1f))
                    }
                }
            }
        } else
        Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { area = it }.then(swipeArea)) {
            // Картинка собеседника во весь кадр, если он себя показывает. Аватар и имя
            // под ней не рисуются: они отвечают на тот же вопрос «с кем говорю», и
            // повторять его поверх лица незачем.
            val remoteShown = remoteVideo?.takeIf { remoteHere }
            // Своя картинка — только пока камера включена. Выключенная камера в движке глушит
            // дорожку, а не убирает её, и окошко с замершим кадром висело (заказчик
            // 2026-10-08: «отключил камеру свою — окно не исчезает»).
            val mine = localVideo?.takeIf { state.cameraOn }
            // Нажали на своё окошко — меняется местами с собеседником (заказчик 2026-10-08).
            // Собеседник без видео — меняется с его аватаром: своё крупно, аватар в окошке.
            val swap = swapped && mine != null
            val big = if (swap) mine else remoteShown
            if (big != null) {
                CallVideo(big, Modifier.fillMaxSize())
            }

            if (big == null) {
            // Голосом: аватар ниже, крупно и настоящим лицом; под ним имя, телефон и то, что
            // со звонком сейчас (заказчик 2026-10-08).
            Column(
                Modifier.fillMaxSize().padding(horizontal = TimaSpacing.about4),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.weight(1f))
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
                ) {
                    if (group != null) {
                        val badge = Tima.words.groupCall.badge
                        Avatar(letters = badge, size = AvatarSize.Huge, image = group.creatorFace, overlay = badge)
                    } else if (!incoming && state.stage == CallStage.Connecting) {
                        // Дозваниваемся — волны от аватара (заказчик 2026-10-08), вместе с гудком.
                        Dialing(side = AvatarSize.Huge.side * 2) {
                            Avatar(letters = letters(peer), size = AvatarSize.Huge, image = peerFace)
                        }
                    } else {
                        Avatar(letters = letters(peer), size = AvatarSize.Huge, image = peerFace)
                    }
                    PeerName(peer, stranger, tint = peerTint)
                    peerPhone?.takeIf { group == null && it.isNotBlank() }?.let { Secondary(it) }
                    group?.creator?.let { Secondary(it) }
                    // Что со звонком — зелёным (заказчик 2026-10-08): «Соединяем…», «Звоним…», время.
                    Caption(under(state, incoming, seconds, peerRinging), fontSize = TimaType.sz5, weight = FontWeight.SemiBold, color = colors.navigation)

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
                Spacer(Modifier.weight(1.6f))
            }
            } else if (group == null) {
                // С видео: имя и время звонка поверх картинки, второй строкой телефон — как в
                // пробе (заказчик 2026-10-08).
                Column(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)))
                        .padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2)
                        .onSizeChanged { overlayPx = it.height }
                        .testTag(CALL_OVERLAY_TAG),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { PeerName(peer, stranger, onVideo = true, tint = peerTint) }
                        Caption(
                            under(state, incoming, seconds, peerRinging),
                            fontSize = TimaType.sz5,
                            weight = FontWeight.Bold,
                            color = colors.navigation,
                            lineOne = true,
                        )
                    }
                    peerPhone?.takeIf { it.isNotBlank() }?.let {
                        Caption(it, fontSize = TimaType.sz5, color = Color.White.copy(alpha = 0.85f), lineOne = true)
                    }
                }
            }

            // Своё изображение — плашкой в углу, как в макете (`[[ Вы (PIP) ]]`).
            // Маленькое и сверху справа: человек проверяет им, что он в кадре, а не
            // смотрит на себя. Нажатие меняет его местами с собеседником; зажали —
            // толстая зелёная рамка, и окошко переносится пальцем (заказчик 2026-10-08).
            val small = if (swap) remoteShown else mine
            // Поменялись, а у собеседника видео нет — в окошке его аватар.
            if (small != null || swap) {
                Pip(
                    video = small,
                    offset = pipOffset,
                    below = if (group == null && big != null) pipBelow else 0.dp,
                    dragging = pipDragging,
                    onTap = { if (mine != null) swapped = !swapped },
                    instead = { Avatar(letters = letters(peer), size = AvatarSize.Big, image = peerFace) },
                    onDrag = { moving -> pipDragging = moving },
                    onMove = { delta -> pipOffset = clampPip(pipOffset + delta, area.belowBy(pipBelowPx), pipSizePx) },
                    modifier = Modifier.align(Alignment.TopEnd),
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
        // Разговор — своя панель: три столбца «Звук · Камера · Завершить» с подписями внизу
        // (проба «а», заказчик 2026-10-08). Кнопки стоят на своих местах при любом
        // состоянии — прежнее правило ряда значков осталось в силе.
        val talking = state.stage == CallStage.Connected && !(askHangUp && group != null)
        if (talking) {
            TalkPanel(
                state = state,
                onMicrophone = onMicrophone,
                onCamera = onCamera,
                onSpeaker = onSpeaker,
                onSwitchCamera = onSwitchCamera,
                onRemoteVideo = onRemoteVideo,
                onParticipants = group?.onParticipants,
                onHangUp = { if (group?.mine == true) askHangUp = true else onHangUp() },
            )
            return@Column
        }
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
                    Button(label = Tima.words.groupCall.stopAll, kind = ButtonKind.Leave, onClick = {
                        askHangUp = false
                        group.onStopAll()
                    })
                    Button(label = words.cancel, kind = ButtonKind.Quiet, onClick = { askHangUp = false })
                }

                // Конец группового — своё окно (заказчик 2026-10-02): автор создаёт звонок
                // заново, остальные присоединяются, пока звонок в группе идёт.
                state.stage == CallStage.Ended && group != null -> {
                    if (group.mine) {
                        group.onCreateAgain?.let { Button(label = Tima.words.groupCall.createCall, onClick = it) }
                    } else {
                        group.onJoinAgain?.let { Button(label = Tima.words.groupCall.join, onClick = it, enabled = group.joinLive) }
                    }
                    onClose?.let { Button(label = words.close, kind = ButtonKind.Leave, onClick = it) }
                }

                // «Перезвонить» с переключателем — строкой выше, «Закрыть» светло-красной — под
                // ними (заказчик 2026-10-08): так вышло на телефоне шириной 360, и так оставили.
                state.stage == CallStage.Ended -> {
                    onCallAgain?.let { Button(label = words.callAgain, onClick = it) }
                    if (onCallAgain != null && onRedialKind != null) {
                        KindSwitch(video = redialVideo, onPick = onRedialKind)
                    }
                    onClose?.let {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Button(label = words.close, kind = ButtonKind.Leave, onClick = it)
                        }
                    }
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

                // Разговор — панель выше: сюда он не доходит.
                else -> Unit
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
    /** Вид: сколько на странице, страница, развёрнутая клетка, своё окошко (2026-10-01). */
    val view: GroupView = GroupView(),
    /** Закреплённый создателем (ключ клетки); `null` — никто. */
    val pinnedKey: String? = null,
    /** «Голос» под пузырём — запретить или разрешить говорить; `null` — я не создатель. */
    val onVoice: ((GroupTile) -> Unit)? = null,
    /** «📌» под пузырём — закрепить или открепить; `null` — я не создатель. */
    val onPin: ((GroupTile) -> Unit)? = null,
    /** Создатель группы звонка — имя отдельной строкой (заказчик 2026-10-02). */
    val creator: String? = null,
    /** Аватар создателя; на него ложится «ГЗ». */
    val creatorFace: androidx.compose.ui.graphics.ImageBitmap? = null,
    /** Конец звонка, я автор — «Создать звонок». */
    val onCreateAgain: (() -> Unit)? = null,
    /** Конец звонка, не автор — «Присоединиться»; активна, пока звонок в группе идёт. */
    val onJoinAgain: (() -> Unit)? = null,
    val joinLive: Boolean = false,
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
    /** Показывает ли себя — клетка в сетке; нет — строка в списке «голосом». */
    val cameraOn: Boolean = video != null,
    /** Чей это участник — для команд создателя. */
    val userId: String = "",
    /** Голос запрещён создателем — только смотрит. */
    val micForbidden: Boolean = false,
    /** Видео запрещено создателем (заказчик 2026-10-02, 6д). */
    val videoForbidden: Boolean = false,
    /** Аватар-картинка человека; `null` — буквы (2026-10-02: раньше буквы были всегда). */
    val face: androidx.compose.ui.graphics.ImageBitmap? = null,
    /** Видео участника не приходит или не раскодируется — словами на его клетке; `null` — всё в порядке. */
    val videoTrouble: String? = null,
    /** Его набор с его слов — для журнала стенда (5а); `null` — не сказал. */
    val bench: String? = null,
    /** Что я от него принимаю — для журнала стенда. */
    val incoming: io.tima.core.call.PeerIncoming? = null,
)

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
 *
 * Заказчик 2026-10-08: переключателем — в сером пузыре, с местом по бокам от «Перезвонить» и
 * «Закрыть»; кнопки внутри прежние. Меняется и нажатием, и свайпом влево-вправо.
 */
@Composable
private fun KindSwitch(video: Boolean, onPick: (Boolean) -> Unit) {
    Row(
        Modifier.padding(horizontal = TimaSpacing.about2)
            .background(Tima.colors.quiet, RoundedCornerShape(50))
            .flipBy(vertical = false, onFirst = { onPick(false) }, onSecond = { onPick(true) })
            .padding(horizontal = TimaSpacing.about2, vertical = TimaSpacing.about1)
            .testTag(CALL_KIND_SWITCH_TAG),
        horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
    ) {
        CallButton(glyph = "🎤", on = !video, onClick = { onPick(false) })
        CallButton(glyph = "📹", on = video, onClick = { onPick(true) })
    }
}

/** Метка переключателя «голос · видео» у «Перезвонить». */
const val CALL_KIND_SWITCH_TAG: String = "call:kind-switch"

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


/** Метка слова «Незнакомый» перед именем собеседника. */
const val CALL_STRANGER_TAG: String = "call:stranger"

/** Строка имени: «Незнакомый» оранжевым впереди, если человека нет в книге (2026-10-08). */
@Composable
private fun PeerName(peer: String, stranger: Boolean, onVideo: Boolean = false, tint: Color? = null) {
    val colors = Tima.colors
    val ink = tint ?: if (onVideo) Color.White else colors.text
    Row(
        horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (stranger) {
            Caption(
                Tima.words.call.stranger,
                modifier = Modifier.testTag(CALL_STRANGER_TAG),
                fontSize = TimaType.sz4,
                weight = FontWeight.Bold,
                color = colors.activity,
                lineOne = true,
            )
        }
        val name = peer.ifBlank { if (stranger) "" else Tima.words.chat.nameless }
        if (name.isNotEmpty()) Caption(name, fontSize = TimaType.sz4, weight = FontWeight.Bold, color = ink, lineOne = true)
    }
}

/** Кадр без полосы имени сверху: в нём окошко ходит, не закрывая время звонка. */
private fun IntSize.belowBy(px: Int): IntSize = IntSize(width, (height - px).coerceAtLeast(0))

/** На сколько своё окошко заходит на затемнение с именем и временем — чуть-чуть, три точки. */
private val PIP_ABOVE_SHADE = 3.dp

/** Метка строки имени и времени поверх видео. */
const val CALL_OVERLAY_TAG: String = "call:overlay"
