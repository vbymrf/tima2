package io.tima.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.tima.core.ui.Caption
import io.tima.core.ui.IconButton
import io.tima.core.ui.ListLine
import io.tima.core.ui.Name
import io.tima.core.ui.Tertiary
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.TimaZones
import io.tima.core.ui.words

/** Метки меню сообщения для сценариев (СЦЕНАРИИ-ПО-ОКНАМ.md). */
object MessageMenuTags {
    const val SHEET = "msg:menu"
    const val REPLY = "msg:reply"
    const val CHANGE = "msg:change"
    const val EDIT = "msg:edit"
    const val DELETE_ME = "msg:delete.me"
    const val DELETE_ALL = "msg:delete.all"
    const val SHARE = "msg:share"
    const val COPY = "msg:copy"
    const val SAVE = "msg:save"
    const val PICK = "msg:pick"
    const val CARRY = "msg:carry"
    const val CLOSE = "msg:close"
}

/**
 * Меню отправленного сообщения (doc_mig/РЕДАКТОР-СООБЩЕНИЙ.md §11.1; заказчик 2026-10-18 —
 * сразу со всеми пунктами).
 *
 * **Чужое:** Ответить · Поделиться · Скопировать текст · Добавить в Подборку · Перенести себе.
 * **Своё:** вместо «Ответить» — одна кнопка с выбором: Изменить · Удалить у себя · Удалить у всех
 * («самому себе не отвечаем»). Пункта нет, когда делать им нечего (`null`): «Перенести себе» в
 * личной переписке, «Изменить» после трёх правок.
 */
@Composable
fun MessageMenuSheet(
    /** О чём речь — первые строки текста; `null` — текста нет. */
    text: String?,
    own: Boolean,
    onClose: () -> Unit,
    onReply: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onDeleteForMe: (() -> Unit)? = null,
    onDeleteForAll: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    onCopy: (() -> Unit)? = null,
    /**
     * «Сохранить как» — у сообщения с файлами и фото вместо «Скопировать текст» (заказчик 2026-10-22);
     * несколько файлов — группой в папку.
     */
    onSave: (() -> Unit)? = null,
    onPick: (() -> Unit)? = null,
    onCarry: (() -> Unit)? = null,
    /** «Закрепить» (ПЛАН-(ЗО) ЗО3); `null` — права нет. */
    onPin: (() -> Unit)? = null,
    /** Это сообщение и есть закреплённое — пункт «Открепить». */
    pinned: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = Tima.colors
    val words = Tima.words.chat
    // Выбор своей кнопки раскрывается на месте: три действия под одним пунктом, а не три пункта
    // в общем ряду — так в меню своего столько же строк, сколько у чужого.
    var choosing by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.text.copy(alpha = 0.45f))
            .clickable(onClick = onClose),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = TimaZones.zone1)
                .background(colors.surface)
                .clickable(enabled = false) {}
                .testTag(MessageMenuTags.SHEET),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2),
                horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (!text.isNullOrBlank()) Caption("«$text»", fontSize = TimaType.sz4, color = colors.text2, maxLines = 2)
                }
                IconButton(glyph = "✕", onClick = onClose, modifier = Modifier.testTag(MessageMenuTags.CLOSE))
            }
            if (own) {
                if (onEdit != null || onDeleteForMe != null || onDeleteForAll != null) {
                    Item(words.menuChange, MessageMenuTags.CHANGE, mark = if (choosing) "⌄" else "›") { choosing = !choosing }
                }
                if (choosing) {
                    onEdit?.let { Item(words.menuEdit, MessageMenuTags.EDIT, inner = true, onClick = it) }
                    onDeleteForMe?.let { Item(words.menuDeleteForMe, MessageMenuTags.DELETE_ME, inner = true, onClick = it) }
                    onDeleteForAll?.let { Item(words.menuDeleteForAll, MessageMenuTags.DELETE_ALL, inner = true, onClick = it) }
                }
            } else {
                onReply?.let { Item(words.menuReply, MessageMenuTags.REPLY, onClick = it) }
            }
            onPin?.let {
                Item(
                    if (pinned) Tima.words.showcase.menuUnpin else Tima.words.showcase.menuPin,
                    if (pinned) PinTags.MENU_UNPIN else PinTags.MENU_PIN,
                    onClick = it,
                )
            }
            onShare?.let { Item(words.menuShare, MessageMenuTags.SHARE, onClick = it) }
            onCopy?.let { Item(words.menuCopy, MessageMenuTags.COPY, onClick = it) }
            onSave?.let { Item(words.fileSaveAs, MessageMenuTags.SAVE, onClick = it) }
            onPick?.let { Item(words.menuPick, MessageMenuTags.PICK, onClick = it) }
            onCarry?.let { Item(words.menuCarry, MessageMenuTags.CARRY, onClick = it) }
        }
    }
}

@Composable
private fun Item(title: String, tag: String, inner: Boolean = false, mark: String = "›", onClick: () -> Unit) = ListLine(
    modifier = Modifier.testTag(tag).then(if (inner) Modifier.padding(start = TimaSpacing.about4) else Modifier),
    onClick = onClick,
    middle = { Name(title) },
    right = { Tertiary(mark, lineOne = true) },
)
