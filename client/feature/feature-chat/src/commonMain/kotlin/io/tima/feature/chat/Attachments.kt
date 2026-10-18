package io.tima.feature.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import io.tima.core.ui.IconButton
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
        Thumb(p, Modifier.widthIn(max = 260.dp).fillMaxWidth().aspectRatio(aspectOf(p))) { actions.openPhoto(p) }
        return
    }
    FlowRow(Modifier.widthIn(max = 260.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (p in photos) Thumb(p, Modifier.size(126.dp)) { actions.openPhoto(p) }
    }
}

private fun aspectOf(m: RichMedia): Float = if (m.width > 0 && m.height > 0) (m.width.toFloat() / m.height).coerceIn(0.5f, 2f) else 1f

@Composable
private fun Thumb(m: RichMedia, modifier: Modifier, onClick: () -> Unit) {
    val bitmap = remember(m.mediaId) { if (m.preview.isNotEmpty()) decodeImage(m.preview) else null }
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Tima.colors.functional)
            .clickable(onClick = onClick)
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
            .clickable { actions.saveFile(file) }.padding(10.dp).testTag("msg:file"),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Caption(fileGlyph(file.mime), fontSize = 26.sp)
        Column(Modifier.weight(1f, fill = false)) {
            Caption(file.name.ifBlank { file.mime }, fontSize = TimaType.sz4, weight = FontWeight.Bold, lineOne = true)
            Tertiary(sizeText(file.size, Tima.words.chat.sizeUnits), lineOne = true)
        }
        Chip(Tima.words.chat.fileSaveAs, kind = ChipKind.Quiet, onClick = { actions.saveFile(file) })
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

/**
 * Полное фото поверх переписки (Решения §3: «полное — по нажатию»): грузится от почтальона и
 * открывается ключом из тела. Срок у почтальона вышел — место с подписью, превью остаётся в ленте.
 */
@Composable
fun FullPhoto(photo: RichMedia, onClose: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Tima.colors.text.copy(alpha = 0.85f)).clickable(onClick = onClose).testTag("msg:photo.full"),
        contentAlignment = Alignment.Center,
    ) {
        MessagePicture(
            mediaUri(photo.mediaId, photo.key),
            if (photo.width > 0 && photo.height > 0) photo.width.toFloat() / photo.height else null,
            Tima.words.chat.fileGone,
            Modifier.padding(12.dp),
        )
        Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.TopEnd) {
            IconButton(glyph = "✕", onClick = onClose, modifier = Modifier.testTag("msg:photo.close"))
        }
    }
}
