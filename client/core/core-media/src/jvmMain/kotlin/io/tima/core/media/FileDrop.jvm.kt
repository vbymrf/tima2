package io.tima.core.media

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import java.awt.datatransfer.DataFlavor
import java.io.File

/** Больше этого с диска в редактор не читается: файл себе — до 50 МБ (Решения §6). */
private const val MAX_DROP_BYTES = 50L * 1024 * 1024

/** ПК: список файлов проводника (`javaFileListFlavor`); папки и большое пропускаются. */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
actual fun Modifier.fileDrop(onHover: (Boolean) -> Unit, onFiles: (List<PickedFile>) -> Unit): Modifier {
    val target = remember(onHover, onFiles) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) = onHover(true)
            override fun onExited(event: DragAndDropEvent) = onHover(false)
            override fun onEnded(event: DragAndDropEvent) = onHover(false)
            override fun onDrop(event: DragAndDropEvent): Boolean {
                onHover(false)
                val files = runCatching {
                    val t = event.awtTransferable
                    if (!t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return false
                    (t.getTransferData(DataFlavor.javaFileListFlavor) as List<*>).filterIsInstance<File>()
                }.getOrNull().orEmpty()
                val picked = files.filter { it.isFile && it.length() in 1..MAX_DROP_BYTES }
                    .mapNotNull { f -> runCatching { PickedFile(f.readBytes(), FileRules.mimeOf(f.name), f.name) }.getOrNull() }
                if (picked.isEmpty()) return false
                onFiles(picked)
                return true
            }
        }
    }
    return dragAndDropTarget(shouldStartDragAndDrop = { true }, target = target)
}
