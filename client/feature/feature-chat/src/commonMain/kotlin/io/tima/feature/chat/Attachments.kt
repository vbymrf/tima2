package io.tima.feature.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.tima.core.media.decodeImage
import io.tima.core.media.mediaUri
import io.tima.core.ui.Caption
import io.tima.core.ui.Chip
import io.tima.core.ui.ChipKind
import io.tima.core.ui.MessagePicture
import io.tima.core.ui.Tertiary
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaType
import io.tima.core.ui.words
import io.tima.domain.chat.RichBlock
import io.tima.domain.chat.RichContent
import io.tima.domain.chat.RichMedia

/** Что делать с вложением — даёт переписка: открыть фото, сохранить файл. */
class AttachmentActions(
    val openPhoto: (RichMedia) -> Unit = {},
    val saveFile: (RichMedia) -> Unit = {},
    /** Получатель забрал файл у почтальона (Решения §6) — галочки у своего. */
    val fetched: (mediaId: String) -> Boolean = { false },
    /** Долгое нажатие на фото — выбор сообщения, в котором оно (заказчик 2026-10-19). */
    val selectPhoto: (RichMedia) -> Unit = {},
    /** Нажатие на файл — меню: открыть, сохранить, поделиться, переслать, удалить (заказчик 2026-10-20); `null` — сразу «Сохранить как». */
    val fileMenu: ((RichMedia) -> Unit)? = null,
)

/** Галочки «получатель забрал» у своего фото или файла вне пузыря (Решения §6). */
const val FETCHED_TAG = "msg:fetched"

val LocalAttachmentActions = staticCompositionLocalOf { AttachmentActions() }

/** Только вложения, без текста — рисуется вне пузыря (Решения §2). */
internal fun RichContent.isOutside(): Boolean =
    blocks.isNotEmpty() && blocks.all { it is RichBlock.Album || it is RichBlock.File }

@Composable
internal fun AttachmentsView(rich: RichContent) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (block in rich.blocks) when (block) {
            is RichBlock.Album -> AlbumView(block.media.mapNotNull { rich.media.getOrNull(it) })
            is RichBlock.File -> rich.media.getOrNull(block.media)?.let { FileView(it) }
            else -> Unit
        }
    }
}

/**
 * Фото без текста (Решения §3): превью из тела, полное — по нажатию. Превью нет или не читается —
 * заглушка с крестиком размером с фото: место известно до загрузки, лента не прыгает.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlbumView(photos: List<RichMedia>) {
    val actions = LocalAttachmentActions.current
    if (photos.size == 1) {
        val p = photos.single()
        Thumb(p, Modifier.widthIn(max = 260.dp).fillMaxWidth().aspectRatio(aspectOf(p)), { actions.selectPhoto(p) }) { actions.openPhoto(p) }
        return
    }
    FlowRow(Modifier.widthIn(max = 260.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (p in photos) Thumb(p, Modifier.size(126.dp), { actions.selectPhoto(p) }) { actions.openPhoto(p) }
    }
}

private fun aspectOf(m: RichMedia): Float = if (m.width > 0 && m.height > 0) (m.width.toFloat() / m.height).coerceIn(0.5f, 2f) else 1f

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Thumb(m: RichMedia, modifier: Modifier, onLongClick: () -> Unit, onClick: () -> Unit) {
    val bitmap = remember(m.mediaId) { if (m.preview.isNotEmpty()) decodeImage(m.preview) else null }
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Tima.colors.functional)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .testTag("msg:photo"),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Caption("✕", fontSize = 28.sp, color = Tima.colors.text3)
        }
    }
}

/** Файл вне пузыря (Решения §6): знак вида, имя, размер; «Сохранить как». */
@Composable
private fun FileView(file: RichMedia) {
    val actions = LocalAttachmentActions.current
    Row(
        Modifier.widthIn(max = 280.dp).background(Tima.colors.functional, RoundedCornerShape(10.dp))
            .clickable { actions.fileMenu?.invoke(file) ?: actions.saveFile(file) }.padding(10.dp).testTag("msg:file"),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Caption(fileGlyph(file.mime), fontSize = 26.sp)
        Column(Modifier.weight(1f, fill = false)) {
            Caption(file.name.ifBlank { file.mime }, fontSize = TimaType.sz4, weight = FontWeight.Bold, lineOne = true)
            Tertiary(sizeText(file.size, Tima.words.chat.sizeUnits), lineOne = true)
        }
        // Меню у файла — нажатием на него; без меню — «Сохранить как», как было.
        if (actions.fileMenu == null) Chip(Tima.words.chat.fileSaveAs, kind = ChipKind.Quiet, onClick = { actions.saveFile(file) })
    }
}

private fun fileGlyph(mime: String) = when {
    mime.startsWith("audio/") -> "🎵"
    mime.startsWith("video/") -> "🎬"
    mime.startsWith("image/") -> "🖼"
    mime == "application/pdf" || "word" in mime || "excel" in mime || "sheet" in mime || "presentation" in mime || mime.startsWith("text/") -> "📑"
    else -> "📄"
}

/** Размер словами: байты, КБ, МБ — с одной цифрой после запятой у МБ. */
internal fun sizeText(bytes: Long, units: List<String>): String = when {
    bytes <= 0 -> ""
    bytes < 1024 -> "$bytes ${units[0]}"
    bytes < 1024 * 1024 -> "${bytes / 1024} ${units[1]}"
    else -> {
        val tenths = bytes * 10 / (1024 * 1024)
        "${tenths / 10},${tenths % 10} ${units[2]}"
    }
}

/** Фото переписки для просмотра: само фото, сообщение, в котором оно пришло, и кто прислал. */
class PhotoAt(val media: RichMedia, val line: io.tima.domain.chat.ChatLine, val from: String?)

/** Что можно сделать с фото в просмотре — меню «≡»; `null` — пункта нет. */
class PhotoActions(
    val save: (PhotoAt) -> Unit = {},
    val deleteForMe: ((PhotoAt) -> Unit)? = null,
    val deleteForAll: ((PhotoAt) -> Unit)? = null,
    val forward: ((PhotoAt) -> Unit)? = null,
    val share: ((PhotoAt) -> Unit)? = null,
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
    fun item(key: String) = "msg:photo.menu.$key"
}

/**
 * Просмотр фото переписки (заказчик 2026-10-18): полное фото — от почтальона, ключом из тела; срок
 * вышел — место с подписью. Слева сверху «≡» — Сохранить как, Удалить у себя, у всех, Переслать,
 * Поделиться, Информация (имя, размер); справа «✕». «‹» и «›» по краям и смахивание вбок — соседние
 * фото переписки, вниз — закрыть. Кнопки — на сплошном фоне: на светлом фото прозрачная не видна.
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
    val density = LocalDensity.current
    val turn = with(density) { 80.dp.toPx() }
    val close = with(density) { 120.dp.toPx() }
    fun go(to: Int) {
        index = to.coerceIn(0, photos.lastIndex)
        menu = false
        info = false
    }
    Box(Modifier.fillMaxSize().background(colors.viewer).testTag(PhotoTags.WINDOW)) {
        Box(
            Modifier.fillMaxSize()
                .offset { IntOffset(drag.x.roundToInt(), drag.y.coerceAtLeast(0f).roundToInt()) }
                .pointerInput(i, photos.size) {
                    detectDragGestures(
                        onDragEnd = {
                            val d = drag
                            drag = Offset.Zero
                            when {
                                d.y > close && d.y > kotlin.math.abs(d.x) -> onClose()
                                d.x < -turn && i < photos.lastIndex -> go(i + 1)
                                d.x > turn && i > 0 -> go(i - 1)
                            }
                        },
                        onDragCancel = { drag = Offset.Zero },
                    ) { change, by ->
                        change.consume()
                        drag += by
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            MessagePicture(
                mediaUri(photo.media.mediaId, photo.media.key),
                if (photo.media.width > 0 && photo.media.height > 0) photo.media.width.toFloat() / photo.media.height else null,
                w.fileGone,
                Modifier.padding(12.dp),
            )
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
        if (i > 0) {
            Box(Modifier.align(Alignment.CenterStart).padding(12.dp)) { SolidButton("‹", PhotoTags.PREV) { go(i - 1) } }
        }
        if (i < photos.lastIndex) {
            Box(Modifier.align(Alignment.CenterEnd).padding(12.dp)) { SolidButton("›", PhotoTags.NEXT) { go(i + 1) } }
        }
        if (menu) {
            Column(
                Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 72.dp).widthIn(min = 220.dp, max = 300.dp)
                    .background(colors.surface, RoundedCornerShape(12.dp)).padding(vertical = 4.dp),
            ) {
                MenuRow("save", w.fileSaveAs) { menu = false; actions.save(photo) }
                actions.deleteForMe?.let { act -> MenuRow("delete.me", w.menuDeleteForMe) { menu = false; act(photo) } }
                // У всех — только своё отправленное (Решения §8).
                actions.deleteForAll?.takeIf { photo.line.outgoing && photo.line.serverId > 0 }?.let { act ->
                    MenuRow("delete.all", w.menuDeleteForAll) { menu = false; act(photo) }
                }
                actions.forward?.let { act -> MenuRow("forward", w.photoForward) { menu = false; act(photo) } }
                actions.share?.let { act -> MenuRow("share", w.menuShare) { menu = false; act(photo) } }
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
            else -> emptyList()
        }
        if (bottom.isNotEmpty()) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp)
                    .background(colors.surface, RoundedCornerShape(12.dp)).clickable { info = false }.padding(12.dp)
                    .testTag(PhotoTags.INFO),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (line in bottom) Caption(line, fontSize = TimaType.sz4, color = colors.text)
            }
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
