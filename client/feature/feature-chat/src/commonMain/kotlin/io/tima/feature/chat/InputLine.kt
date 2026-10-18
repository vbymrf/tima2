package io.tima.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.tima.core.ui.Arrow
import io.tima.core.ui.ButtonCircle
import io.tima.core.ui.Caption
import io.tima.core.ui.IconButton
import io.tima.core.ui.Side
import io.tima.core.ui.Tertiary
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.TimaZones
import io.tima.core.ui.words

/** Пункты скрепки — девять плиток (Решения §9; ВЫБОР-РЕАЛИЗАЦИИ §11.2а). */
enum class AttachItem(val tag: String) {
    FILE("file"), DOCUMENT("document"), PICTURE("picture"), CAMERA("camera"), AUDIO("audio"),
    VIDEO("video"), CONTACT("contact"), PLACE("place"), LINK("link"),
}

/** Метки строки ввода для сценариев (СЦЕНАРИИ-ПО-ОКНАМ.md). */
object InputTags {
    const val EDITOR = "chat:editor"
    const val EMOJI = "chat:emoji"
    const val GALLERY = "chat:gallery"
    const val ATTACH = "chat:attach"
    const val AREA_CLOSE = "chat:area.close"
    const val EMOJI_AREA = "chat:emoji.area"
    const val ATTACH_AREA = "chat:attach.area"
    fun attach(item: AttachItem) = "attach:${item.tag}"
}

/** Что открыто на месте клавиатуры. */
private enum class InputArea { EMOJI, ATTACH }

/**
 * Собрано в редакторе (Решения §9): строка ввода показывает первую строку и не даёт печатать
 * в разметку; «отправить» шлёт собранное. Корзина сбрасывает редактор, 5 секунд — «Вернуть».
 */
data class ComposedLook(val firstLine: String, val rows: Int)

/**
 * Строка ввода (Решения §9): слева — редактор (серый; зелёный — собрано в редакторе), в пузыре
 * ввода серые кнопки Эмоджи, Галерея, Скрепка (набран символ — остаётся Эмоджи), справа —
 * «отправить». Выбора уровня над строкой нет: быстрая отправка — −1 или 2, другой — в редакторе.
 *
 * Эмоджи и Скрепка убирают клавиатуру и открывают свою область с крестиком. При пустой строке
 * вставленное уходит **отдельным сообщением** вне пузыря; внутрь сообщения — только редактором.
 *
 * Голос (четвёртая кнопка) — когда запись голосовых будет готова (ПЛАН-(ВМ), ВМ-В4); до тех пор
 * справа всегда «отправить», а не кнопка без действия.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InputLine(
    typed: String,
    onSet: (String) -> Unit,
    onSend: () -> Unit,
    typingMarks: io.tima.domain.chat.TypingMarks? = null,
    /** Редактор; `null` — кнопки нет (комментарии: только строка со знаками, Решения §9). */
    onEditor: (() -> Unit)? = null,
    composed: ComposedLook? = null,
    onComposedDrop: (() -> Unit)? = null,
    /** Сброшенное корзиной ещё можно вернуть (5 секунд); `null` — нечего. */
    onComposedRestore: (() -> Unit)? = null,
    /** Скрепка и Галерея; `null` — кнопок нет. */
    onAttach: ((AttachItem) -> Unit)? = null,
    /** Эмоджи при пустой строке — отдельным сообщением; `null` — только вставка в набранное. */
    onEmojiSend: ((String) -> Unit)? = null,
    /** Плитки скрепки, которые есть на этой платформе (на ПК нет камеры). */
    attachItems: List<AttachItem> = AttachItem.entries,
) {
    val colors = Tima.colors
    val keyboard = LocalSoftwareKeyboardController.current
    var area by remember { mutableStateOf<InputArea?>(null) }
    fun open(next: InputArea) {
        keyboard?.hide()
        area = if (area == next) null else next
    }
    Column(Modifier.fillMaxWidth().background(colors.functional)) {
        composed?.let { ComposedChip(it, onComposedDrop) }
        if (composed == null && onComposedRestore != null) RestoreChip(onComposedRestore)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = TimaZones.zone4)
                .padding(horizontal = TimaSpacing.about3, vertical = TimaSpacing.about2),
            horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onEditor != null) EditorButton(composed != null, onEditor)
            Row(
                modifier = Modifier
                    .weight(1f)
                    .background(colors.surface, CircleShape)
                    .padding(start = TimaSpacing.about4, end = TimaSpacing.about1, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f).padding(vertical = 4.dp)) {
                    if (typed.isEmpty()) {
                        Caption(composed?.firstLine ?: Tima.words.chat.messageHint, fontSize = TimaType.sz4, color = colors.text3, lineOne = true)
                    }
                    BasicTextField(
                        value = typed,
                        onValueChange = onSet,
                        // Собрано в редакторе — печатать в разметку нельзя (Решения §9).
                        readOnly = composed != null,
                        textStyle = TextStyle(fontSize = TimaType.sz4, color = colors.text),
                        cursorBrush = SolidColor(colors.navigation),
                        visualTransformation = rememberTypingView(typingMarks),
                        modifier = Modifier.fillMaxWidth().testTag(CHAT_INPUT_TAG)
                            .onFocusChanged { if (it.isFocused) area = null },
                    )
                }
                // Набран символ — остаётся Эмоджи: Галерея и Скрепка шлют отдельное сообщение.
                InBubble("🙂", InputTags.EMOJI) { open(InputArea.EMOJI) }
                if (typed.isEmpty() && composed == null && onAttach != null) {
                    InBubble("🖼", InputTags.GALLERY) { keyboard?.hide(); area = null; onAttach(AttachItem.PICTURE) }
                    InBubble("📎", InputTags.ATTACH) { open(InputArea.ATTACH) }
                }
            }
            ButtonCircle(onClick = onSend, live = true, modifier = Modifier.testTag(CHAT_SEND_TAG)) {
                Arrow(Side.Up, color = colors.onAccent)
            }
        }
        when (area) {
            InputArea.EMOJI -> EmojiArea(onClose = { area = null }) { e ->
                if (typed.isEmpty() && composed == null && onEmojiSend != null) onEmojiSend(e) else onSet(typed + e)
            }
            InputArea.ATTACH -> if (onAttach != null) AttachArea(attachItems, onClose = { area = null }) { item -> area = null; onAttach(item) }
            null -> Unit
        }
    }
}

/** Кнопка редактора: три полосы; серая, зелёная — сообщение собрано в редакторе. */
@Composable
private fun EditorButton(composed: Boolean, onClick: () -> Unit) {
    val colors = Tima.colors
    val bar = if (composed) colors.onAccent else colors.text2
    Box(
        Modifier.size(40.dp)
            .background(if (composed) colors.navigation else colors.surface, CircleShape)
            .clickable(onClick = onClick)
            .testTag(InputTags.EDITOR),
        contentAlignment = Alignment.Center,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(3) { Box(Modifier.width(16.dp).height(2.dp).background(bar)) }
        }
    }
}

@Composable
private fun InBubble(glyph: String, tag: String, onClick: () -> Unit) {
    Box(
        Modifier.size(32.dp).clickable(onClick = onClick).testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        // Серые: кнопка не спорит с набранным текстом (Решения §9).
        Caption(glyph, fontSize = 18.sp, color = Tima.colors.text3)
    }
}

/** Чип «Оформлено в редакторе · N рядов» с корзиной (Решения §9). */
@Composable
private fun ComposedChip(look: ComposedLook, onDrop: (() -> Unit)?) {
    val colors = Tima.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = TimaSpacing.about3, vertical = 4.dp)
            .background(colors.softAccent, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("chat:composed"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Caption(Tima.words.chat.composedInEditor(look.rows), fontSize = TimaType.sz5, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (onDrop != null) IconButton(glyph = "🗑", onClick = onDrop, modifier = Modifier.testTag("chat:composed.drop"))
    }
}

/** «Вернуть» — 5 секунд после корзины (Решения §9, В36). */
@Composable
private fun RestoreChip(onRestore: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = TimaSpacing.about3, vertical = 4.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        io.tima.core.ui.Chip(Tima.words.chat.undo, kind = io.tima.core.ui.ChipKind.Selected, onClick = onRestore, modifier = Modifier.testTag("chat:composed.undo"))
    }
}

/** Самые частые эмоджи — набор на месте клавиатуры (Решения §9). */
private val EMOJI = listOf(
    "😀", "😂", "😊", "😍", "😘", "😉", "😎", "🤔", "😅", "😭", "😡", "😴",
    "👍", "👎", "👏", "🙏", "💪", "👌", "✌️", "🤝", "👋", "🙌", "🤷", "🤦",
    "❤️", "💔", "🔥", "⭐", "🎉", "🎂", "🎁", "☕", "🍕", "🍺", "🌹", "🌞",
    "✅", "❌", "⚠️", "❓", "💯", "🚀", "🏠", "🚗", "✈️", "📷", "📞", "⏰",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmojiArea(onClose: () -> Unit, onPick: (String) -> Unit) {
    AreaFrame(Tima.words.chat.emojiArea, onClose, InputTags.EMOJI_AREA) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (e in EMOJI) {
                Box(Modifier.size(44.dp).clickable { onPick(e) }, contentAlignment = Alignment.Center) {
                    Caption(e, fontSize = 26.sp)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AttachArea(items: List<AttachItem>, onClose: () -> Unit, onPick: (AttachItem) -> Unit) {
    val words = Tima.words.chat
    AreaFrame(words.attachArea, onClose, InputTags.ATTACH_AREA) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (item in items) {
                // Ярлычок и подпись — одна нажимаемая плитка (Решения §9).
                Column(
                    Modifier.width(96.dp).background(Tima.colors.surface, RoundedCornerShape(10.dp))
                        .clickable { onPick(item) }.padding(vertical = 10.dp).testTag(InputTags.attach(item)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Caption(glyphOf(item), fontSize = 24.sp)
                    Tertiary(words.attachItem(item.tag), lineOne = true)
                }
            }
        }
    }
}

private fun glyphOf(item: AttachItem) = when (item) {
    AttachItem.FILE -> "📄"
    AttachItem.DOCUMENT -> "📑"
    AttachItem.PICTURE -> "🖼"
    AttachItem.CAMERA -> "📷"
    AttachItem.AUDIO -> "🎵"
    AttachItem.VIDEO -> "🎬"
    AttachItem.CONTACT -> "👤"
    AttachItem.PLACE -> "📍"
    AttachItem.LINK -> "🔗"
}

@Composable
private fun AreaFrame(title: String, onClose: () -> Unit, tag: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Tima.colors.functional).padding(horizontal = TimaSpacing.about3, vertical = 6.dp).testTag(tag),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tertiary(title, lineOne = true, modifier = Modifier.weight(1f))
            IconButton(glyph = "✕", onClick = onClose, modifier = Modifier.testTag(InputTags.AREA_CLOSE))
        }
        content()
    }
}
