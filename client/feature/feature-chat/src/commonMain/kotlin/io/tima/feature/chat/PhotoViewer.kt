package io.tima.feature.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.tima.core.media.PhotoSteps
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
    /** Можно ли досылать правку этого фото: своё, отправлено, правок меньше трёх, ключ тот же. */
    val editable: (PhotoAt) -> Boolean = { false },
    /**
     * Просмотр закрыт — изменённое уходит: одна правка на фото за весь просмотр (ADR-0052). Только
     * [editable]; остальное изменённое остаётся у человека до закрытия.
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
    fun item(key: String) = "msg:photo.menu.$key"
}

/** Наибольшее увеличение и шаг колеса мыши. */
private const val MAX_SCALE = 5f
private const val DOUBLE_TAP_SCALE = 2.5f
private const val WHEEL_STEP = 1.15f

/**
 * Просмотр фото переписки (заказчик 2026-10-18, 2026-10-22): полное фото — от почтальона, ключом из
 * тела. Слева сверху «≡» — Сохранить как, Удалить у себя, у всех, Переслать, Поделиться,
 * Информация; справа «✕». «‹» и «›» по краям и смахивание вбок — соседние фото, вниз — закрыть.
 *
 * - **Масштаб**: щипок двумя пальцами, двойное нажатие, колесо мыши на ПК. Увеличенное двигается
 *   пальцем, а не листается: листание и закрытие — только при обычном размере.
 * - **Слева внизу «Повернуть» и «Изменить»** (редактор: обрезка, кисть, стрелка). Изменённое
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
    var drag by remember { mutableStateOf(Offset.Zero) }
    var scale by remember(i) { mutableStateOf(1f) }
    var pan by remember(i) { mutableStateOf(Offset.Zero) }
    // Изменённое в этом просмотре — по номеру файла; уходит при закрытии.
    val edited = remember { mutableStateMapOf<String, ImageBitmap>() }
    var editing by remember { mutableStateOf<Pair<PhotoAt, ImageBitmap>?>(null) }
    var said by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val turn = with(density) { 80.dp.toPx() }
    val close = with(density) { 120.dp.toPx() }

    // Ушёл с экрана — изменённое своё уходит одной правкой на фото (ADR-0052).
    val now by rememberUpdatedState(actions)
    val all by rememberUpdatedState(photos)
    DisposableEffect(Unit) {
        onDispose {
            val mine = edited.mapNotNull { (id, bmp) -> all.firstOrNull { it.media.mediaId == id }?.takeIf(now.editable)?.let { it to bmp } }
            if (mine.isNotEmpty()) now.commit(mine)
        }
    }

    fun go(to: Int) {
        index = to.coerceIn(0, photos.lastIndex)
        menu = false
        info = false
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

    fun changed(p: PhotoAt, bmp: ImageBitmap) {
        val first = edited.isEmpty()
        edited[p.media.mediaId] = bmp
        if (first || !actions.editable(p)) said = if (actions.editable(p)) w.photoEditOnClose else w.photoEditForeign
    }

    Box(Modifier.fillMaxSize().background(colors.viewer).testTag(PhotoTags.WINDOW)) {
        Box(
            Modifier.fillMaxSize()
                .offset { IntOffset(drag.x.roundToInt(), drag.y.coerceAtLeast(0f).roundToInt()) }
                .pointerInput(i, photos.size) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var moved = Offset.Zero
                        var zoomed = false
                        do {
                            val event = awaitPointerEvent()
                            val zoom = event.calculateZoom()
                            val by = event.calculatePan()
                            if (event.changes.size > 1 || scale > 1f) {
                                if (zoom != 1f) {
                                    scale = (scale * zoom).coerceIn(1f, MAX_SCALE)
                                    zoomed = true
                                }
                                pan = if (scale > 1f) pan + by else Offset.Zero
                                if (zoom != 1f || by != Offset.Zero) event.changes.forEach { it.consume() }
                            } else if (by != Offset.Zero) {
                                moved += by
                                drag = moved
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                        val d = drag
                        drag = Offset.Zero
                        if (!zoomed && scale <= 1f) {
                            when {
                                d.y > close && d.y > abs(d.x) -> onClose()
                                d.x < -turn && i < photos.lastIndex -> go(i + 1)
                                d.x > turn && i > 0 -> go(i - 1)
                            }
                        }
                    }
                }
                .pointerInput(i) {
                    detectTapGestures(onDoubleTap = {
                        scale = if (scale > 1f) 1f else DOUBLE_TAP_SCALE
                        pan = Offset.Zero
                    })
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
                            if (scale == 1f) pan = Offset.Zero
                            e.changes.forEach { it.consume() }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            val shown = Modifier.padding(12.dp).graphicsLayer(scaleX = scale, scaleY = scale, translationX = pan.x, translationY = pan.y)
                .testTag(PhotoTags.IMAGE)
            val changedBmp = edited[photo.media.mediaId]
            if (changedBmp != null) {
                Image(changedBmp, contentDescription = null, modifier = shown.fillMaxWidth().aspectRatio(changedBmp.width.toFloat() / changedBmp.height))
            } else {
                MessagePicture(
                    mediaUri(photo.media.mediaId, photo.media.key),
                    if (photo.media.width > 0 && photo.media.height > 0) photo.media.width.toFloat() / photo.media.height else null,
                    w.fileGone,
                    shown,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SolidButton("≡", PhotoTags.MENU) { menu = !menu; info = false }
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
            SolidButton("✕", PhotoTags.CLOSE, onClose)
        }
        if (i > 0 && scale <= 1f) {
            Box(Modifier.align(Alignment.CenterStart).padding(12.dp)) { SolidButton("‹", PhotoTags.PREV) { go(i - 1) } }
        }
        if (i < photos.lastIndex && scale <= 1f) {
            Box(Modifier.align(Alignment.CenterEnd).padding(12.dp)) { SolidButton("›", PhotoTags.NEXT) { go(i + 1) } }
        }
        if (menu) {
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
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Слева внизу — «Повернуть» и «Изменить» (заказчик 2026-10-22).
            if (actions.load != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SolidLabel(w.photoRotate, PhotoTags.ROTATE) { withCurrent(photo) { changed(photo, PhotoSteps.turn(it)) } }
                    SolidLabel(w.photoEdit, PhotoTags.EDIT) { withCurrent(photo) { editing = photo to it } }
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
        editing?.let { (p, source) ->
            PhotoEditor(
                source = source,
                onSave = { result -> editing = null; changed(p, result) },
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
private fun SolidLabel(label: String, tag: String, onClick: () -> Unit) {
    val colors = Tima.colors
    Caption(
        label,
        fontSize = TimaType.sz4,
        weight = FontWeight.Bold,
        color = colors.text,
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
