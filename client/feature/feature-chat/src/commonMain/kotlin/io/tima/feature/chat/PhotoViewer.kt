package io.tima.feature.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.tima.core.media.PhotoLayers
import io.tima.core.media.mediaUri
import io.tima.core.ui.Caption
import io.tima.core.ui.MessagePicture
import io.tima.core.ui.Tima
import io.tima.core.ui.words
import io.tima.core.ui.TimaType
import io.tima.domain.chat.RichMedia
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Фото переписки для просмотра: само фото, сообщение, в котором оно пришло, и кто прислал. */
class PhotoAt(val media: RichMedia, val line: io.tima.domain.chat.ChatLine, val from: String?)

/**
 * Что можно сделать с фото в просмотре — меню «≡»; `null` — пункта нет. Изменённое в этом просмотре
 * фото приходит вторым аргументом: «Сохранить как», «Переслать», «Поделиться» берут его, а не
 * пришедшее (заказчик 2026-10-22).
 */
class PhotoActions(
    val save: (PhotoAt, ImageBitmap?) -> Unit = { _, _ -> },
    val deleteForMe: ((PhotoAt) -> Unit)? = null,
    val deleteForAll: ((PhotoAt) -> Unit)? = null,
    val forward: ((PhotoAt, ImageBitmap?) -> Unit)? = null,
    val share: ((PhotoAt, ImageBitmap?) -> Unit)? = null,
    /** Пришедшее фото картинкой — с него начинаются поворот и редактор; `null` — не загрузилось. */
    val load: (suspend (PhotoAt) -> ImageBitmap?)? = null,
    /**
     * Почему правку этого фото уже не отправить — словами человека; `null` — можно: своё, отправлено,
     * правок меньше трёх, ключ тот же. Нельзя — «Изменить» заблокирована и по нажатию говорит это.
     */
    val refusal: (PhotoAt) -> String? = { "" },
    /**
     * Просмотр закрыт — сохранённое в редакторе уходит: одна правка на фото за весь просмотр (ADR-0052).
     * Поворот в просмотре остаётся только у человека (заказчик 2026-10-22).
     */
    val commit: (List<Pair<PhotoAt, ImageBitmap>>) -> Unit = {},
)

/** Метки окна просмотра фото (СЦЕНАРИИ-ПО-ОКНАМ, «Фото»). */
object PhotoTags {
    const val WINDOW = "msg:photo.full"
    const val CLOSE = "msg:photo.close"
    const val MENU = "msg:photo.menu"
    const val PREV = "msg:photo.prev"
    const val NEXT = "msg:photo.next"
    const val COUNT = "msg:photo.count"
    const val INFO = "msg:photo.info"
    const val ROTATE = "msg:photo.rotate"
    const val EDIT = "msg:photo.edit"
    const val IMAGE = "msg:photo.image"
    const val WHEN = "msg:photo.when"
    const val MENU_OUTSIDE = "msg:photo.menu.outside"
    const val BUBBLE = "msg:photo.bubble"
    fun item(key: String) = "msg:photo.menu.$key"
}

/** Наибольшее увеличение и шаг колеса мыши. */
private const val MAX_SCALE = 5f
private const val DOUBLE_TAP_SCALE = 2.5f
private const val WHEEL_STEP = 1.15f

/** Лента фото: доводка до соседнего, мс; сопротивление у края; скорость «быстрого» движения, dp/с. */
private const val SLIDE_MS = 220
private const val EDGE_RESIST = 0.3f
private const val FLING_DP_S = 800

/** Нажатие — не дольше этого; второе нажатие двойного — не позже этого после первого. */
private const val TAP_MS = 300L
private const val DOUBLE_TAP_MS = 400L

/**
 * Просмотр фото переписки (заказчик 2026-10-18, 2026-10-22): полное фото — от почтальона, ключом из
 * тела. Слева сверху «≡» — Сохранить как, Удалить у себя, у всех, Переслать, Поделиться,
 * Информация; справа «✕»; посередине — когда отправлено. «‹» и «›» по краям и смахивание вбок — соседние
 * фото **лентой**: текущее уезжает за пальцем, соседнее приезжает; за последним и до первого — пружина
 * (заказчик 2026-10-22). Закрытия смахиванием вниз нет.
 *
 * - **Масштаб**: щипок двумя пальцами, двойное нажатие (увеличенное — к исходному), колесо мыши на ПК.
 *   Увеличенное двигается пальцем, а не листается, и край фото не уходит за край экрана.
 * - **Внизу «Повернуть» слева, «Изменить» справа**, между ними «3 из 12» (редактор: обрезка, кисть, стрелка). Изменённое
 *   показывается сразу, а уходит, **когда просмотр закрыт**, — одной правкой на фото, сколько бы раз
 *   его ни поворачивали и ни сохраняли в редакторе. Закрыт — значит покинул экран любым путём:
 *   «✕», смахивание, удаление, уход из переписки.
 *
 * Кнопки — на сплошном фоне: на светлом фото прозрачная не видна.
 */
@Composable
fun PhotoViewer(
    photos: List<PhotoAt>,
    start: Int,
    actions: PhotoActions,
    onClose: () -> Unit,
    /** Одно предложение поверх фото — «не загрузилось», «переслано». */
    notice: String? = null,
) {
    if (photos.isEmpty()) {
        onClose()
        return
    }
    val colors = Tima.colors
    val w = Tima.words.chat
    var index by remember(start) { mutableStateOf(start.coerceIn(0, photos.lastIndex)) }
    val i = index.coerceIn(0, photos.lastIndex)
    val photo = photos[i]
    var menu by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    // Лента фото (заказчик 2026-10-22: «чтобы оно уезжало, новое приезжало»): [dragX] — пока ведёт палец,
    // [slide] — доводка после отпускания и листание кнопками.
    var dragX by remember { mutableStateOf(0f) }
    val slide = remember { Animatable(0f) }
    var scale by remember(i) { mutableStateOf(1f) }
    var pan by remember(i) { mutableStateOf(Offset.Zero) }
    var area by remember { mutableStateOf(IntSize.Zero) }
    // Высота кнопок сверху и снизу: увеличенное фото сдвигается до них, а не до края экрана.
    var topBar by remember { mutableStateOf(0) }
    var bottomBar by remember { mutableStateOf(0) }
    // Края самих кнопок (а не их полос с отступами) и верх области фото — в координатах окна.
    var buttonsTop by remember { mutableStateOf(0f) }
    var buttonsBottom by remember { mutableStateOf(0f) }
    var areaTop by remember { mutableStateOf(0f) }
    // Изменённое в этом просмотре — по номеру файла; уходит при закрытии.
    val edited = remember { mutableStateMapOf<String, ImageBitmap>() }
    var editing by remember { mutableStateOf<Pair<PhotoAt, ImageBitmap>?>(null) }
    // Сохранённое в редакторе — оно и уходит собеседнику; поворот в просмотре — только у себя.
    val toSend = remember { mutableStateMapOf<String, ImageBitmap>() }
    // Подсказка над кнопкой — как меню «≡», закрывается нажатием мимо: слева (поворот) или справа (изменить).
    var bubble by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var turnTold by remember { mutableStateOf(false) }
    var said by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    // Надписи редактора сводятся этим же набором текста.
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = LocalDensity.current
    // Быстрое движение — к соседнему фото, даже если сдвиг короче трети ширины.
    val flingSpeed = with(density) { FLING_DP_S.dp.toPx() }
    val pad = with(density) { 12.dp.toPx() }
    // Короткий сдвиг — ещё нажатие, а не движение (заказчик 2026-10-22: «два быстрых движения короткого
    // сдвига или короткий сдвиг и клик — двойной клик»).
    val tapSlop = with(density) { 24.dp.toPx() }
    val tapNear = with(density) { 80.dp.toPx() }

    // Ушёл с экрана — изменённое своё уходит одной правкой на фото (ADR-0052).
    val now by rememberUpdatedState(actions)
    val all by rememberUpdatedState(photos)
    DisposableEffect(Unit) {
        onDispose {
            val mine = toSend.mapNotNull { (id, bmp) -> all.firstOrNull { it.media.mediaId == id }?.takeIf { now.refusal(it) == null }?.let { it to bmp } }
            if (mine.isNotEmpty()) now.commit(mine)
        }
    }

    fun go(to: Int) {
        index = to.coerceIn(0, photos.lastIndex)
        menu = false
        info = false
    }

    /**
     * Доехать лентой до соседнего фото ([to] — его номер) или вернуться на место ([to] = текущий) — с
     * того сдвига, где отпустили палец. Соседнего нет — пружина назад.
     */
    fun settle(to: Int) {
        val from = dragX
        dragX = 0f
        scope.launch {
            slide.snapTo(from)
            if (to == i || to !in photos.indices || area.width == 0) {
                slide.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow))
            } else {
                slide.animateTo(if (to > i) -area.width.toFloat() else area.width.toFloat(), tween(SLIDE_MS))
                go(to)
                slide.snapTo(0f)
            }
        }
    }

    /** Картинка, с которой правим: уже изменённая или пришедшая. */
    fun withCurrent(p: PhotoAt, then: (ImageBitmap) -> Unit) {
        edited[p.media.mediaId]?.let { return then(it) }
        val load = actions.load ?: return
        scope.launch {
            val got = load(p)
            if (got == null) said = w.photoLoadFailed else then(got)
        }
    }

    /**
     * Сдвиг увеличенного фото (заказчик 2026-10-22). По ширине — не дальше края экрана. По высоте — до
     * кнопок: верх фото можно опустить до низа самих верхних кнопок, низ — поднять до верха самих нижних
     * (края кнопок, без отступов их полос), чтобы кнопки не закрывали фото. Фото ниже этой полосы — по
     * её середине.
     */
    fun limited(p: Offset, s: Float): Offset {
        val bmp = edited[photo.media.mediaId]
        val aspect = (bmp?.let { it.width.toFloat() / it.height }
            ?: photo.media.takeIf { it.width > 0 && it.height > 0 }?.let { it.width.toFloat() / it.height } ?: 1f).coerceIn(0.4f, 3f)
        val aw = area.width - 2 * pad
        val ah = area.height - 2 * pad
        if (aw <= 0f || ah <= 0f) return Offset.Zero
        val dw = minOf(aw, ah * aspect)
        val dh = dw / aspect
        val mx = maxOf(0f, (s * dw - area.width) / 2)
        val ih = s * dh
        val half = area.height / 2f
        // Граница — край кнопок: низ верхних, верх нижних (заказчик 2026-10-22: «по краю кнопок»).
        val upper = (buttonsTop - areaTop).coerceIn(0f, area.height.toFloat())
        val lower = (if (buttonsBottom > 0f) buttonsBottom - areaTop else area.height.toFloat()).coerceIn(upper, area.height.toFloat())
        val y = if (ih <= lower - upper) {
            (upper + lower) / 2f - half
        } else {
            p.y.coerceIn(lower - half - ih / 2, upper - half + ih / 2)
        }
        return Offset(p.x.coerceIn(-mx, mx), y)
    }

    /** Сохранено в редакторе: показать и отправить при закрытии просмотра. */
    fun saved(p: PhotoAt, bmp: ImageBitmap) {
        val first = toSend.isEmpty()
        edited[p.media.mediaId] = bmp
        toSend[p.media.mediaId] = bmp
        if (first) said = w.photoEditOnClose
    }

    /** Поворот в просмотре — только у себя (заказчик 2026-10-22); в первый раз — подсказка над кнопкой. */
    fun turned(p: PhotoAt, bmp: ImageBitmap) {
        edited[p.media.mediaId] = bmp
        if (!turnTold) {
            turnTold = true
            bubble = true to if (actions.refusal(p) == null) w.photoTurnLocal else w.photoTurnLocalOnly
        }
    }

    Box(
        Modifier.fillMaxSize().background(colors.viewer)
            // Подсказка над кнопкой закрывается любым касанием, но касание не забирает: второе нажатие
            // «Повернуть» и поворачивает, и убирает подсказку (заказчик 2026-10-22). Прежде поверх лежала
            // прозрачная подложка — она съедала нажатие целиком.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        if (e.type == PointerEventType.Press && bubble != null) bubble = null
                    }
                }
            }
            .testTag(PhotoTags.WINDOW),
    ) {
        Box(
            Modifier.fillMaxSize()
                .onSizeChanged { area = it }
                .onGloballyPositioned { areaTop = it.positionInRoot().y }
                .pointerInput(i, photos.size) {
                    // Одно касание разбирается целиком: короткое и быстрое — нажатие, два нажатия рядом и
                    // подряд — двойное; длиннее — листание (обычный размер) или сдвиг (увеличенное).
                    // Закрытия смахиванием вниз нет (заказчик 2026-10-22).
                    var lastTapUp = 0L
                    var lastTapAt = Offset.Zero
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val velocity = VelocityTracker()
                        velocity.addPosition(down.uptimeMillis, down.position)
                        var moved = Offset.Zero
                        var path = 0f
                        var zoomed = false
                        var fingers = 1
                        var upAt = down.uptimeMillis
                        do {
                            val event = awaitPointerEvent()
                            fingers = maxOf(fingers, event.changes.size)
                            val zoom = event.calculateZoom()
                            val by = event.calculatePan()
                            path += by.getDistance()
                            upAt = event.changes.maxOf { it.uptimeMillis }
                            if (event.changes.size > 1 || scale > 1f) {
                                if (zoom != 1f) {
                                    scale = (scale * zoom).coerceIn(1f, MAX_SCALE)
                                    zoomed = true
                                }
                                pan = if (scale > 1f) limited(pan + by, scale) else Offset.Zero
                                if (zoom != 1f || by != Offset.Zero) event.changes.forEach { it.consume() }
                            } else if (by != Offset.Zero) {
                                moved += by
                                event.changes.firstOrNull { it.id == down.id }?.let { velocity.addPosition(it.uptimeMillis, it.position) }
                                // Лента за пальцем; за последним и до первого — с сопротивлением (пружина).
                                if (path > tapSlop) {
                                    val edge = (moved.x > 0 && i == 0) || (moved.x < 0 && i == photos.lastIndex)
                                    dragX = if (edge) moved.x * EDGE_RESIST else moved.x
                                }
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                        val d = moved.x
                        val fling = runCatching { velocity.calculateVelocity().x }.getOrDefault(0f)
                        val tap = fingers == 1 && !zoomed && path <= tapSlop && upAt - down.uptimeMillis <= TAP_MS
                        if (tap) {
                            if (down.uptimeMillis - lastTapUp <= DOUBLE_TAP_MS && (down.position - lastTapAt).getDistance() <= tapNear) {
                                // Двойное: увеличенное — к исходному, исходное — увеличить.
                                scale = if (scale > 1f) 1f else DOUBLE_TAP_SCALE
                                pan = Offset.Zero
                                lastTapUp = 0L
                            } else {
                                lastTapUp = upAt
                                lastTapAt = down.position
                            }
                        } else {
                            lastTapUp = 0L
                            if (!zoomed && scale <= 1f && fingers == 1) {
                                // Дальше трети ширины или быстрым движением — к соседнему; иначе — на место.
                                val far = area.width / 3f
                                settle(
                                    when {
                                        (d < -far || fling < -flingSpeed) && i < photos.lastIndex -> i + 1
                                        (d > far || fling > flingSpeed) && i > 0 -> i - 1
                                        else -> i
                                    },
                                )
                            } else {
                                dragX = 0f
                            }
                        }
                    }
                }
                .pointerInput(i) {
                    // Колесо мыши на ПК — масштаб.
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent()
                            if (e.type != PointerEventType.Scroll) continue
                            val dy = e.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                            if (dy == 0f) continue
                            scale = (if (dy < 0) scale * WHEEL_STEP else scale / WHEEL_STEP).coerceIn(1f, MAX_SCALE)
                            pan = if (scale == 1f) Offset.Zero else limited(pan, scale)
                            e.changes.forEach { it.consume() }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            // Лента: текущее и соседние по краям, на ширину экрана. Соседние уже загружены — при въезде не
            // мелькает пустое место. Масштаб и сдвиг — только у текущего.
            for (k in -1..1) {
                val at = i + k
                if (at !in photos.indices) continue
                val p = photos[at]
                key(p.media.mediaId) {
                    Box(
                        Modifier.fillMaxSize().offset { IntOffset((k * area.width + dragX + slide.value).roundToInt(), 0) },
                        contentAlignment = Alignment.Center,
                    ) {
                        val shown = Modifier.padding(12.dp).then(
                            if (k == 0) {
                                Modifier.graphicsLayer(scaleX = scale, scaleY = scale, translationX = pan.x, translationY = pan.y).testTag(PhotoTags.IMAGE)
                            } else {
                                Modifier
                            },
                        )
                        val changedBmp = edited[p.media.mediaId]
                        if (changedBmp != null) {
                            Image(changedBmp, contentDescription = null, modifier = shown.fillMaxWidth().aspectRatio(changedBmp.width.toFloat() / changedBmp.height))
                        } else {
                            MessagePicture(
                                mediaUri(p.media.mediaId, p.media.key),
                                if (p.media.width > 0 && p.media.height > 0) p.media.width.toFloat() / p.media.height else null,
                                w.fileGone,
                                shown,
                            )
                        }
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().align(Alignment.TopCenter).onSizeChanged { topBar = it.height }.padding(12.dp)
                .onGloballyPositioned { buttonsTop = it.positionInRoot().y + it.size.height },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SolidButton("≡", PhotoTags.MENU) { menu = !menu; info = false }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                // Когда отправлено — дата и время (заказчик 2026-10-22).
                if (photo.line.atMs > 0) {
                    Caption(
                        dateTime(photo.line.atMs),
                        fontSize = TimaType.sz5,
                        weight = FontWeight.Bold,
                        color = colors.text,
                        modifier = Modifier.background(colors.surface, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp)
                            .testTag(PhotoTags.WHEN),
                    )
                }
            }
            SolidButton("✕", PhotoTags.CLOSE, onClose)
        }
        if (i > 0 && scale <= 1f) {
            Box(Modifier.align(Alignment.CenterStart).padding(12.dp)) { SolidButton("‹", PhotoTags.PREV) { settle(i - 1) } }
        }
        if (i < photos.lastIndex && scale <= 1f) {
            Box(Modifier.align(Alignment.CenterEnd).padding(12.dp)) { SolidButton("›", PhotoTags.NEXT) { settle(i + 1) } }
        }
        if (menu) {
            // Нажатие мимо меню — закрыть его (заказчик 2026-10-22).
            Box(
                Modifier.fillMaxSize()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { menu = false }
                    .testTag(PhotoTags.MENU_OUTSIDE),
            )
            val mine = edited[photo.media.mediaId]
            Column(
                Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 72.dp).widthIn(min = 220.dp, max = 300.dp)
                    .background(colors.surface, RoundedCornerShape(12.dp)).padding(vertical = 4.dp),
            ) {
                MenuRow("save", w.fileSaveAs) { menu = false; actions.save(photo, mine) }
                actions.deleteForMe?.let { act -> MenuRow("delete.me", w.menuDeleteForMe) { menu = false; act(photo) } }
                // У всех — только своё отправленное (Решения §8).
                actions.deleteForAll?.takeIf { photo.line.outgoing && photo.line.serverId > 0 }?.let { act ->
                    MenuRow("delete.all", w.menuDeleteForAll) { menu = false; act(photo) }
                }
                actions.forward?.let { act -> MenuRow("forward", w.photoForward) { menu = false; act(photo, mine) } }
                actions.share?.let { act -> MenuRow("share", w.menuShare) { menu = false; act(photo, mine) } }
                MenuRow("info", w.photoInfo) { menu = false; info = true }
            }
        }
        val bottom = when {
            info -> listOf(
                "${w.photoName}: ${photo.media.name.ifBlank { w.photoNoName }}",
                "${w.photoSize}: ${sizeText(photo.media.size, w.sizeUnits).ifBlank { "—" }}",
                "${w.photoPixels}: ${if (photo.media.width > 0) "${photo.media.width} × ${photo.media.height}" else "—"}",
                "${w.photoFrom}: ${photo.from ?: "—"}",
            )
            notice != null -> listOf(notice)
            said != null -> listOfNotNull(said)
            else -> emptyList()
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { bottomBar = it.height }.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Внизу: слева «Повернуть», справа «Изменить», между ними — «3 из 12» (заказчик 2026-10-22).
            Row(Modifier.fillMaxWidth().onGloballyPositioned { buttonsBottom = it.positionInRoot().y }, verticalAlignment = Alignment.CenterVertically) {
                if (actions.load != null) {
                    SolidLabel(w.photoRotate, PhotoTags.ROTATE) { withCurrent(photo) { turned(photo, PhotoLayers.turn(it)) } }
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (photos.size > 1) {
                        Caption(
                            w.photoOf(i + 1, photos.size),
                            fontSize = TimaType.sz5,
                            weight = FontWeight.Bold,
                            color = colors.text,
                            modifier = Modifier.background(colors.surface, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp)
                                .testTag(PhotoTags.COUNT),
                        )
                    }
                }
                if (actions.load != null) {
                    // Отправить правку уже нельзя — кнопка погашена, по нажатию — почему и что делать.
                    val no = actions.refusal(photo)
                    SolidLabel(w.photoEdit, PhotoTags.EDIT, dim = no != null) {
                        if (no != null) bubble = false to (no + " " + w.photoEditAdvice) else withCurrent(photo) { editing = photo to it }
                    }
                }
            }
            if (bottom.isNotEmpty()) {
                Column(
                    Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(12.dp))
                        .clickable { info = false; said = null }.padding(12.dp).testTag(PhotoTags.INFO),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (line in bottom) Caption(line, fontSize = TimaType.sz4, color = colors.text)
                }
            }
        }
        bubble?.let { (left, text) ->
            val above = with(density) { bottomBar.toDp() }
            Caption(
                text,
                fontSize = TimaType.sz4,
                color = colors.text,
                modifier = Modifier.align(if (left) Alignment.BottomStart else Alignment.BottomEnd)
                    .padding(start = 12.dp, end = 12.dp, bottom = above + 4.dp).widthIn(max = 300.dp)
                    .background(colors.surface, RoundedCornerShape(12.dp)).padding(14.dp).testTag(PhotoTags.BUBBLE),
            )
        }
        editing?.let { (p, source) ->
            PhotoEditor(
                source = source,
                // Правка отправленного сводится на том, что есть, — пришедшем фото (исходника здесь нет).
                onSave = { marks -> editing = null; saved(p, PhotoLayers.render(source, marks, measurer)) },
                onCancel = { editing = null },
            )
        }
    }
}

/** Кнопка поверх фото — на сплошном круге темы, без прозрачности: видна на любом фото. */
@Composable
private fun SolidButton(glyph: String, tag: String, onClick: () -> Unit) {
    val colors = Tima.colors
    Box(
        Modifier.size(48.dp).background(colors.surface, CircleShape).clickable(onClick = onClick).testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Caption(glyph, fontSize = 30.sp, weight = FontWeight.Bold, color = colors.text)
    }
}

/** Кнопка-слово поверх фото — так же на сплошном фоне. */
@Composable
private fun SolidLabel(label: String, tag: String, dim: Boolean = false, onClick: () -> Unit) {
    val colors = Tima.colors
    Caption(
        label,
        fontSize = TimaType.sz4,
        weight = FontWeight.Bold,
        color = if (dim) colors.text3 else colors.text,
        modifier = Modifier.background(colors.surface, RoundedCornerShape(24.dp)).clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp).testTag(tag),
    )
}

@Composable
private fun MenuRow(key: String, label: String, onClick: () -> Unit) {
    Caption(
        label,
        fontSize = TimaType.sz4,
        color = Tima.colors.text,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag(PhotoTags.item(key)),
    )
}
