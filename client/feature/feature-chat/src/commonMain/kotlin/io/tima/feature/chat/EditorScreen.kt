package io.tima.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.tima.core.ui.Bubble
import io.tima.core.ui.Caption
import io.tima.core.ui.IconButton
import io.tima.core.ui.Tertiary
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.words
import io.tima.domain.chat.ComposedDoc
import io.tima.domain.chat.ComposedItem
import io.tima.domain.chat.InlineKind
import io.tima.domain.chat.MessageCircle
import io.tima.domain.chat.ParagraphKind
import io.tima.domain.chat.RichContent
import io.tima.domain.chat.TypingMarks

/** Метки окна редактора (СЦЕНАРИИ-ПО-ОКНАМ, «Редактор»). */
object EditorTags {
    const val WINDOW = "editor:window"
    const val BACK = "editor:back"
    const val TITLE = "editor:title"
    const val UNDO = "editor:undo"
    const val REDO = "editor:redo"
    const val LEVEL = "editor:level"
    const val SEND = "editor:send"
    const val PREVIEW = "editor:preview"
    const val SELECTION = "editor:selection"
    const val PROBLEMS = "editor:problems"
    const val SYMBOL_AREA = "editor:symbol.area"
    fun tab(key: String) = "editor:tab.$key"
    fun button(key: String) = "editor:$key"
    fun level(level: Int) = "editor:level.$level"
    fun paragraph(kind: ParagraphKind) = "editor:paragraph.${kind.name.lowercase()}"
    fun text(index: Int) = "editor:text.$index"
    fun item(index: Int) = "editor:item.$index"
}

/** Разделы редактора и кнопки ленты (Решения §10.2) — ключи подписей `EditorWords.button`. */
internal enum class EditorTab(val key: String, val buttons: List<String>) {
    TEXT("text", listOf("paragraph", "bold", "italic", "underline", "strike", "highlight", "code", "symbol", "paste", "marks")),
    INSERT("insert", listOf("picture", "link", "emoji", "picks", "underlay", "cloud", "place", "contact", "reply")),
    LAYOUT("layout", listOf("row", "table", "cellAdd", "cellRemove", "merge", "split", "up", "down", "delete")),
    STYLE("style", listOf("widget", "background", "color", "size", "align", "padding", "divider", "reset", "json")),
}

/** Значок кнопки ленты — постоянный; буквы оформления и подпись под значком — из словаря. */
internal fun editorGlyph(key: String): String = when (key) {
    "paragraph" -> "¶"
    "code" -> "</>"
    "symbol" -> "Ω"
    "paste" -> "📋"
    "marks" -> "md"
    "picture" -> "🖼"
    "link" -> "🔗"
    "emoji" -> "🙂"
    "picks" -> "★"
    "underlay" -> "▤"
    "cloud" -> "☁"
    "place" -> "📍"
    "contact" -> "👤"
    "reply" -> "@"
    "row" -> "▥"
    "table" -> "▦"
    "cellAdd" -> "+▯"
    "cellRemove" -> "−▯"
    "merge" -> "⬚"
    "split" -> "⫼"
    "up" -> "↑"
    "down" -> "↓"
    "delete" -> "🗑"
    "widget" -> "◳"
    "background" -> "◼"
    "color" -> "A"
    "size" -> "Aa"
    "align" -> "≡"
    "padding" -> "⇲"
    "divider" -> "―"
    "reset" -> "⟲"
    "json" -> "{ }"
    else -> "·"
}

/** Знаки области «Символ» (Решения §10.2). */
private val SYMBOLS: Map<String, List<String>> = mapOf(
    "signs" to listOf("*", "#", "+", "=", "~", "`", "|", "\\", "-", ">", "^", "<", "§", "№", "©", "®", "™", "°", "±", "×", "÷", "≈", "≠", "≤", "≥", "…", "—", "–", "«", "»", "•", "✓"),
    "arrows" to listOf("←", "→", "↑", "↓", "↔", "↕", "⇐", "⇒", "⇔", "↩", "↪", "⟵", "⟶", "➜", "▲", "▼", "◀", "▶"),
    "currency" to listOf("₽", "$", "€", "£", "¥", "₸", "₴", "₺", "₹", "¢", "₿"),
)

/**
 * Редактор сообщения (Решения §10) — окно поверх переписки. Сообщение показано так, как его
 * увидит получатель; текст набирается прямо в нём, со знаками быстрого оформления.
 *
 * @param levels уровни, которые предлагает шапка; один — кнопка серая.
 * @param preview разбор документа тем же путём, что входящее, — «глазами получателя».
 * @param problems нарушения правил разметки словами; есть — отправить нельзя.
 * @param onBack назад: документ остаётся черновиком.
 * @param onSend отправить; `true` — ушло, окно закрывается.
 */
@Composable
fun EditorScreen(
    model: EditorModel,
    levels: List<MessageCircle>,
    defaultLevel: Int,
    typingMarks: TypingMarks?,
    preview: (ComposedDoc) -> RichContent?,
    problems: (ComposedDoc) -> List<String>,
    onBack: (ComposedDoc) -> Unit,
    onSend: (ComposedDoc) -> Unit,
    /** Кнопки ленты, которых нет в первом срезе, — их обработчик; `null` — подсказка «следующим срезом». */
    onButton: ((String) -> Boolean)? = null,
    /** Сказать одно предложение в разделителе — «картинка не загрузилась» и подобное. */
    notice: String? = null,
) {
    val colors = Tima.colors
    val w = Tima.words.editor
    val keyboard = LocalSoftwareKeyboardController.current
    var tab by rememberSaveable { mutableStateOf(EditorTab.TEXT) }
    var showPreview by remember { mutableStateOf(false) }
    var levelMenu by remember { mutableStateOf(false) }
    var paragraphMenu by remember { mutableStateOf(false) }
    var symbolArea by remember { mutableStateOf(false) }
    var emojiArea by remember { mutableStateOf(false) }
    val clipboardHtml = io.tima.core.media.rememberClipboardHtml()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var hint by remember { mutableStateOf<String?>(null) }
    val recent = remember { mutableStateListOf<String>() }
    val doc = model.doc
    val trouble = remember(doc) { problems(doc) }
    val level = doc.level ?: defaultLevel

    fun press(key: String) {
        hint = null
        when (key) {
            "paragraph" -> paragraphMenu = !paragraphMenu
            "bold" -> model.inline(InlineKind.BOLD)
            "italic" -> model.inline(InlineKind.ITALIC)
            "underline" -> model.inline(InlineKind.UNDERLINE)
            "strike" -> model.inline(InlineKind.STRIKE)
            "highlight" -> model.inline(InlineKind.HIGHLIGHT)
            "code" -> model.inline(InlineKind.CODE)
            "symbol" -> { keyboard?.hide(); emojiArea = false; symbolArea = !symbolArea }
            "emoji" -> { keyboard?.hide(); symbolArea = false; emojiArea = !emojiArea }
            // Из HTML — только текст и простое оформление (Решения §5); HTML нет — текст как есть.
            "paste" -> {
                val html = clipboardHtml()
                val text = html?.let(io.tima.domain.chat.HtmlToMarks::convert)?.takeIf { it.isNotBlank() }
                    ?: clipboard.getText()?.text.orEmpty()
                model.paste(text)
            }
            "marks" -> { model.toggleMarks(); hint = if (model.doc.marks) w.marksOn else w.marksOff }
            else -> if (onButton?.invoke(key) != true) hint = w.soon
        }
    }

    Column(Modifier.fillMaxSize().background(colors.surface).imePadding().testTag(EditorTags.WINDOW)) {
        // ── Шапка: ‹ · название · ↶ ↷ · цифра уровня · ➤ (§10.1) ──
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = TimaSpacing.about2),
            horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(glyph = "‹", onClick = { onBack(model.doc) }, live = true, modifier = Modifier.testTag(EditorTags.BACK))
            Column(Modifier.weight(1f).clickable { showPreview = !showPreview }.testTag(EditorTags.TITLE)) {
                Caption(w.title, fontSize = TimaType.sz4, weight = FontWeight.Bold, lineOne = true)
                if (showPreview) Tertiary(w.previewOn, lineOne = true)
            }
            HeaderGlyph("↶", model.canUndo, EditorTags.UNDO) { model.undo() }
            HeaderGlyph("↷", model.canRedo, EditorTags.REDO) { model.redo() }
            LevelButton(level, enabled = levels.size > 1) { levelMenu = !levelMenu }
            HeaderGlyph("➤", !doc.isEmpty && trouble.isEmpty(), EditorTags.SEND) { onSend(model.doc) }
        }
        if (levelMenu) LevelList(levels, level) { picked -> model.level(picked); levelMenu = false }

        // ── Разделы и лента (§10.2) ──
        Row(Modifier.fillMaxWidth().padding(horizontal = TimaSpacing.about2), horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about1)) {
            for (t in EditorTab.entries) {
                io.tima.core.ui.Tab(
                    label = w.tab(t.key),
                    current = t == tab,
                    onClick = { tab = t; paragraphMenu = false; hint = null },
                    modifier = Modifier.testTag(EditorTags.tab(t.key)),
                )
            }
        }
        Ribbon(tab, marksOn = doc.marks, onPress = ::press)
        if (paragraphMenu) ParagraphList(model.paragraphHere) { kind -> model.paragraph(kind); paragraphMenu = false }

        // Разделитель: что выбрано (§10.2) и подсказка последнего нажатия.
        Row(
            Modifier.fillMaxWidth().background(colors.functional).padding(horizontal = TimaSpacing.about4, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tertiary(notice ?: hint ?: w.selected(selectedKind(model)), lineOne = true)
        }
        if (model.hasSelection) SelectionStrip { kind -> model.inline(kind) }

        // ── Сообщение ──
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(TimaSpacing.about3)) {
                if (showPreview) {
                    Bubble(my = true, modifier = Modifier.testTag(EditorTags.PREVIEW)) {
                        preview(doc)?.let { RichBody(it) } ?: Caption(doc.firstLine, fontSize = TimaType.sz4)
                    }
                } else {
                    doc.items.forEachIndexed { i, item -> EditorItemView(model, i, item, typingMarks.takeIf { doc.marks }, preview) }
                }
            }
        }
        if (trouble.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().background(colors.alarm.copy(alpha = 0.12f)).padding(TimaSpacing.about3).testTag(EditorTags.PROBLEMS)) {
                Caption(w.cannotSend, fontSize = TimaType.sz5, weight = FontWeight.Bold, color = colors.alarm)
                for (p in trouble.take(3)) Caption(p, fontSize = TimaType.sz5, color = colors.alarm)
            }
        }
        if (emojiArea) EmojiArea(onClose = { emojiArea = false }) { e ->
            model.insert(ComposedItem.Widget(io.tima.domain.chat.ComposedWidget.Emoji(e)))
            emojiArea = false
        }
        if (symbolArea) SymbolArea(recent, onClose = { symbolArea = false }) { s ->
            model.symbol(s)
            recent.remove(s)
            recent.add(0, s)
            if (recent.size > 24) recent.removeAt(recent.lastIndex)
        }
    }
}

private fun selectedKind(model: EditorModel): String = when (model.doc.items.getOrNull(model.focus)) {
    is ComposedItem.Row -> "row"
    is ComposedItem.Widget -> "widget"
    else -> "text"
}

@Composable
private fun HeaderGlyph(glyph: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    val colors = Tima.colors
    Box(
        Modifier.size(40.dp).clickable(enabled = enabled, onClick = onClick).testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Caption(glyph, fontSize = 22.sp, weight = FontWeight.Bold, color = if (enabled) colors.text else colors.text3)
    }
}

/** На кнопке — только цифра уровня (§10.1); один уровень — серая. */
@Composable
private fun LevelButton(level: Int, enabled: Boolean, onClick: () -> Unit) {
    val colors = Tima.colors
    Box(
        Modifier.size(34.dp)
            .background(if (enabled) colors.navigation else colors.functional, CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .testTag(EditorTags.LEVEL),
        contentAlignment = Alignment.Center,
    ) {
        Caption(level.toString(), fontSize = TimaType.sz5, weight = FontWeight.Black, color = if (enabled) colors.onAccent else colors.text3)
    }
}

/** Список уровней: описание перед цифрой (§10.1). */
@Composable
private fun LevelList(levels: List<MessageCircle>, current: Int, onPick: (Int) -> Unit) {
    val colors = Tima.colors
    Column(Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2)) {
        Tertiary(Tima.words.editor.levelTitle, lineOne = true)
        for (c in levels) {
            Row(
                Modifier.fillMaxWidth().clickable { onPick(c.level) }.padding(vertical = 6.dp).testTag(EditorTags.level(c.level)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
            ) {
                Column(Modifier.weight(1f)) {
                    Caption(c.title, fontSize = TimaType.sz4, weight = if (c.level == current) FontWeight.Bold else FontWeight.Normal)
                    Tertiary(c.about)
                }
                Caption(c.level.toString(), fontSize = TimaType.sz4, weight = FontWeight.Black, color = colors.text2)
            }
        }
    }
}

/** Лента: шесть кнопок и половина седьмой — видно, что дальше есть ещё (§10.2). */
@Composable
private fun Ribbon(tab: EditorTab, marksOn: Boolean, onPress: (String) -> Unit) {
    val colors = Tima.colors
    val w = Tima.words.editor
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = maxWidth / 6.5f
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
            for (key in tab.buttons) {
                val on = key == "marks" && marksOn
                Column(
                    Modifier.width(width).clickable { onPress(key) }.padding(vertical = 4.dp).testTag(EditorTags.button(key)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.size(30.dp).background(if (on) colors.navigation else colors.functional, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Caption(
                            w.letter(key) ?: editorGlyph(key),
                            fontSize = 15.sp,
                            weight = if (key == "bold") FontWeight.Black else FontWeight.Bold,
                            color = if (on) colors.onAccent else colors.text,
                        )
                    }
                    // Подпись постоянного размера: лента не должна прыгать от настройки шрифта (§10.2).
                    Caption(w.button(key), fontSize = 10.sp, color = colors.text2, lineOne = true)
                }
            }
        }
    }
}

@Composable
private fun ParagraphList(current: ParagraphKind, onPick: (ParagraphKind) -> Unit) {
    val colors = Tima.colors
    Column(Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = TimaSpacing.about4, vertical = 4.dp)) {
        for (kind in ParagraphKind.entries) {
            val size = when (kind) {
                ParagraphKind.HEADING1 -> TimaType.sz3
                ParagraphKind.HEADING2, ParagraphKind.HEADING3 -> TimaType.sz4
                else -> TimaType.sz5
            }
            Caption(
                Tima.words.editor.paragraph(kind.name),
                fontSize = size,
                weight = if (kind == current) FontWeight.Black else if (kind.name.startsWith("HEADING")) FontWeight.Bold else FontWeight.Normal,
                color = if (kind == current) colors.navigation else colors.text,
                modifier = Modifier.fillMaxWidth().clickable { onPick(kind) }.padding(vertical = 6.dp).testTag(EditorTags.paragraph(kind)),
            )
        }
    }
}

/** Чёрная полоска над выделенным: Ж, К, Ч, З, М, код (§10.2). */
@Composable
private fun SelectionStrip(onMark: (InlineKind) -> Unit) {
    val colors = Tima.colors
    Row(
        Modifier.fillMaxWidth().background(colors.text).padding(horizontal = TimaSpacing.about3, vertical = 2.dp).testTag(EditorTags.SELECTION),
        horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
    ) {
        val marks = listOf(
            InlineKind.BOLD to TextStyle(fontWeight = FontWeight.Black),
            InlineKind.ITALIC to TextStyle(fontStyle = FontStyle.Italic),
            InlineKind.UNDERLINE to TextStyle(textDecoration = TextDecoration.Underline),
            InlineKind.STRIKE to TextStyle(textDecoration = TextDecoration.LineThrough),
            InlineKind.HIGHLIGHT to TextStyle(),
            InlineKind.CODE to TextStyle(),
        )
        for ((kind, look) in marks) {
            val glyph = Tima.words.editor.letter(kind.name.lowercase()) ?: "</>"
            Box(Modifier.size(36.dp).clickable { onMark(kind) }.testTag("editor:selection.${kind.name.lowercase()}"), contentAlignment = Alignment.Center) {
                androidx.compose.foundation.text.BasicText(glyph, style = look.merge(TextStyle(color = colors.surface, fontSize = 16.sp)))
            }
        }
    }
}

/** Кусок документа: текст набирается здесь же, остальное — как увидит получатель, нажатием выбирается. */
@Composable
private fun EditorItemView(model: EditorModel, index: Int, item: ComposedItem, marks: TypingMarks?, preview: (ComposedDoc) -> RichContent?) {
    val colors = Tima.colors
    val chosen = index == model.focus
    when (item) {
        is ComposedItem.Text -> {
            val value = if (chosen) model.field else androidx.compose.ui.text.input.TextFieldValue(item.source)
            BasicTextField(
                value = value,
                onValueChange = { v -> if (chosen) model.changed(index, v) else { model.focused(index); model.changed(index, v) } },
                textStyle = TextStyle(fontSize = TimaType.sz4, color = colors.text),
                cursorBrush = SolidColor(colors.navigation),
                visualTransformation = rememberTypingView(marks),
                modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(vertical = 4.dp)
                    .onFocusChanged { if (it.isFocused) model.focused(index) }
                    .testTag(EditorTags.text(index)),
            )
        }
        else -> {
            Box(
                Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    .then(if (chosen) Modifier.border(2.dp, colors.navigation, RoundedCornerShape(8.dp)) else Modifier)
                    .clickable { model.select(index) }
                    .padding(4.dp)
                    .testTag(EditorTags.item(index)),
            ) {
                preview(ComposedDoc(listOf(item), marks = model.doc.marks))?.let { RichBody(it) }
            }
        }
    }
}

/** «Символ» — область на месте клавиатуры: Недавние, Знаки, Стрелки, Валюта; неразрывный пробел. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SymbolArea(recent: List<String>, onClose: () -> Unit, onPick: (String) -> Unit) {
    val colors = Tima.colors
    val w = Tima.words.editor
    var group by remember { mutableStateOf(if (recent.isEmpty()) "signs" else "recent") }
    Column(Modifier.fillMaxWidth().height(240.dp).background(colors.functional).padding(TimaSpacing.about2).testTag(EditorTags.SYMBOL_AREA)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (g in listOf("recent", "signs", "arrows", "currency")) {
                    io.tima.core.ui.Tab(label = w.symbols(g), current = g == group, onClick = { group = g }, modifier = Modifier.testTag("editor:symbol.$g"))
                }
            }
            IconButton(glyph = "✕", onClick = onClose, modifier = Modifier.testTag("editor:symbol.close"))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 6.dp)) {
            val list = if (group == "recent") recent else SYMBOLS[group].orEmpty()
            if (list.isEmpty()) Tertiary(w.recentEmpty)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (s in list) {
                    Box(Modifier.size(42.dp).background(colors.surface, RoundedCornerShape(8.dp)).clickable { onPick(s) }, contentAlignment = Alignment.Center) {
                        Caption(s, fontSize = 20.sp)
                    }
                }
            }
        }
        Caption(
            w.nbsp,
            fontSize = TimaType.sz5,
            color = colors.text2,
            modifier = Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(8.dp)).clickable { onPick(" ") }.padding(8.dp).testTag("editor:symbol.nbsp"),
        )
    }
}
