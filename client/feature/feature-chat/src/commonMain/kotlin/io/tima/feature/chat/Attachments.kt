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
import io.tima.core.ui.PhotoTile
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
    /** Фото, которого нет и уже не будет (срок почтальона вышел, прислать некому) — ADR-0052. */
    val gone: (mediaId: String) -> Boolean = { false },
    /** Фото ждёт Wi-Fi: качать по мобильной сети выключено (заказчик 2026-10-22) — сказать на месте. */
    val waitsWifi: (mediaId: String) -> Boolean = { false },
)

/**
 * Сообщение только из фото, которых нет и уже не будет (заказчик 2026-10-22): не показывается, как
 * нечитаемое. Файл рядом или текст — сообщение остаётся.
 */
fun AttachmentActions.photosGone(rich: RichContent?): Boolean {
    if (rich == null || !rich.isOutside() || rich.blocks.any { it is RichBlock.File }) return false
    val ids = rich.blocks.filterIsInstance<RichBlock.Album>().flatMap { it.media }.mapNotNull { rich.media.getOrNull(it)?.mediaId }
    return ids.isNotEmpty() && ids.all(gone)
}

/** Место фото, которое ждёт Wi-Fi (заказчик 2026-10-22). */
const val PHOTO_WAITS_WIFI_TAG = "msg:photo.wifi"

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
 * Фото без текста (Решения §3, ADR-0052): само фото, забранное у почтальона сразу, — загрузчик
 * ужимает его под место. Одно — во всю ширину места, в своём качестве; несколько — квадратами. Пока
 * едет — пустое место размером с фото: размеры в теле, лента не прыгает. Фото, которого уже не
 * будет, не показывается.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlbumView(all: List<RichMedia>) {
    val actions = LocalAttachmentActions.current
    val photos = all.filterNot { actions.gone(it.mediaId) }
    if (photos.isEmpty()) return
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
    val shape = RoundedCornerShape(10.dp)
    val base = modifier.clip(shape).background(Tima.colors.functional).combinedClickable(onClick = onClick, onLongClick = onLongClick)
    if (LocalAttachmentActions.current.waitsWifi(m.mediaId)) {
        Box(base.padding(8.dp).testTag(PHOTO_WAITS_WIFI_TAG), contentAlignment = Alignment.Center) {
            Tertiary(Tima.words.chat.photoWaitsWifi)
        }
        return
    }
    PhotoTile(mediaUri(m.mediaId, m.key), base.testTag("msg:photo"))
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
