package io.tima.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.tima.core.ui.Caption
import io.tima.core.ui.Chip
import io.tima.core.ui.ChipKind
import io.tima.core.ui.MessagePalette
import io.tima.core.ui.Tertiary
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.paletteColor
import io.tima.core.ui.words
import io.tima.domain.chat.ComposedDoc
import io.tima.domain.chat.ComposedItem
import io.tima.domain.chat.ComposedStyle
import io.tima.domain.chat.ComposedWidget
import io.tima.domain.chat.Spot

/** Порядок цветов в образцах: яркий, затем мягкий того же цвета. */
/**
 * Цвета ссылки — только яркие, что не сольются с фоном (заказчик 2026-10-21): без белого, чёрного,
 * серых и подложек — среди них и цвет своих сообщений. Одни и в «Свойствах», и при вставке ссылки.
 */
val LinkColors = listOf("accent", "amber", "yellow", "green", "blue", "lilac", "pink", "red")

private val PALETTE_ORDER = listOf(
    "text", "surface", "accent", "accent-soft", "amber", "amber-soft", "yellow", "green", "green-soft",
    "blue", "blue-soft", "lilac", "pink", "red", "red-soft", "grey", "grey-soft",
)

/**
 * Выбор цвета из палитры сообщений: «Нет» и образцы. Им же выбирают цвет ссылки в «Вставить
 * ссылку» (заказчик 2026-10-19).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PaletteRow(selected: String?, tag: String, only: List<String>? = null, onPick: (String?) -> Unit) {
    val colors = Tima.colors
    val w = Tima.words.editor
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip(w.none, kind = if (selected.isNullOrEmpty()) ChipKind.Selected else ChipKind.Quiet, onClick = { onPick(null) },
            modifier = Modifier.testTag("$tag.none"))
        for (name in (only ?: PALETTE_ORDER).filter { it in MessagePalette.names }) {
            Box(
                Modifier.size(30.dp)
                    .background(paletteColor(name) ?: colors.functional, CircleShape)
                    .border(if (selected == name) 3.dp else 1.dp, if (selected == name) colors.navigation else colors.line, CircleShape)
                    .clickable { onPick(name) }
                    .testTag("$tag.$name"),
            )
        }
    }
    selected?.takeIf { it.isNotEmpty() }?.let { Tertiary(w.paletteName(it), lineOne = true) }
}

/** Стиль куска из редактора (имена — как в разметке) — для показа в самом редакторе. */
internal fun blockStyleOf(style: Map<String, String>): io.tima.domain.chat.BlockStyle =
    if (style.isEmpty()) {
        io.tima.domain.chat.BlockStyle.NONE
    } else {
        io.tima.domain.chat.BlockStyle(
            background = style["background"],
            color = style["color"],
            size = style["size"],
            align = style["align"],
            valign = style["valign"],
            padding = style["padding"],
            divider = style["divider"],
            dividerColor = style["divider_color"],
        )
    }

/** Виджет на выбранном месте: во всю ширину, в ячейке ряда или в пункте списка. */
private fun widgetAt(doc: ComposedDoc, at: Spot): ComposedWidget? = when (val item = doc.items.getOrNull(at.item)) {
    is ComposedItem.Widget -> item.widget.takeIf { at.cell == -1 }
    is ComposedItem.Row -> item.cells.getOrNull(at.cell)?.widgets?.getOrNull(at.widget)
    is ComposedItem.ItemList -> item.items.getOrNull(at.cell)?.widgets?.getOrNull(at.widget)
    else -> null
}

internal fun withWidget(doc: ComposedDoc, at: Spot, widget: ComposedWidget): ComposedDoc = doc.copy(
    items = doc.items.mapIndexed { i, item ->
        when {
            i != at.item -> item
            item is ComposedItem.Widget && at.cell == -1 -> item.copy(widget = widget)
            item is ComposedItem.Row -> item.copy(
                cells = item.cells.mapIndexed { c, cell ->
                    if (c != at.cell) cell else cell.copy(widgets = cell.widgets.mapIndexed { w, old -> if (w == at.widget) widget else old })
                },
            )
            item is ComposedItem.ItemList -> item.copy(
                items = item.items.mapIndexed { c, point ->
                    if (c != at.cell) point else point.copy(widgets = point.widgets.mapIndexed { w, old -> if (w == at.widget) widget else old })
                },
            )
            else -> item
        }
    },
)

/**
 * Панель свойства «Стиля» (Решения §10.2) под лентой: на выбранное — ряд, ячейку, кусок; виджет и
 * текст в ячейке берут стиль ячейки. JSON — стиль выбранного текстом, проверка до «Применить».
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StylePanel(key: String, model: EditorModel, problems: (ComposedDoc) -> List<String>) {
    val colors = Tima.colors
    val w = Tima.words.editor
    val spot = model.spot
    val style = ComposedStyle.styleAt(model.doc, spot)
    // В куске-тексте — блоку своего вида под курсором, как его увидит получатель (заказчик 2026-10-24).
    fun set(prop: String, value: String?) = model.styled { d, s -> ComposedStyle.with(d, s, prop, value) }

    @Composable
    fun palette(prop: String) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip(w.none, kind = if (style[prop] == null) ChipKind.Selected else ChipKind.Quiet, onClick = { set(prop, null) },
                modifier = Modifier.testTag("editor:style.$prop.none"))
            for (name in PALETTE_ORDER.filter { it in MessagePalette.names }) {
                Box(
                    Modifier.size(30.dp)
                        .background(paletteColor(name) ?: colors.functional, CircleShape)
                        .border(if (style[prop] == name) 3.dp else 1.dp, if (style[prop] == name) colors.navigation else colors.line, CircleShape)
                        .clickable { set(prop, name) }
                        .testTag("editor:style.$prop.$name"),
                )
            }
        }
        style[prop]?.let { Tertiary(w.paletteName(it), lineOne = true) }
    }

    @Composable
    fun steps(prop: String) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (value in ComposedStyle.STEPS.getValue(prop)) {
                Chip(
                    w.stepName(prop, value),
                    kind = if (style[prop] == value) ChipKind.Selected else ChipKind.Quiet,
                    onClick = { set(prop, if (style[prop] == value) null else value) },
                    modifier = Modifier.testTag("editor:style.$prop.$value"),
                )
            }
        }
    }

    Column(
        Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2)
            .testTag("editor:style.$key"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when (key) {
            "background" -> palette("background")
            "color" -> palette("color")
            "size" -> steps("size")
            "align" -> steps("align")
            "padding" -> steps("padding")
            "divider" -> {
                steps("divider")
                palette("divider_color")
            }
            // «Свойства» (ADR-0050 §2): то, что можно изменить у выбранного блока.
            "props" -> Props(model, spot) { prop, value -> set(prop, value) }
            "json" -> JsonStyle(model, spot, style, problems)
        }
    }
}

/**
 * «Стиль → Свойства» (ADR-0050 §2): только то, что можно изменить у выбранного блока. Список —
 * «Развернуть по умолчанию», «Без отступа»; виджет — свои; ячейка — по вертикали.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Props(model: EditorModel, spot: Spot, set: (String, String?) -> Unit) {
    val w = Tima.words.editor
    val item = model.doc.items.getOrNull(spot.item)
    when {
        widgetAt(model.doc, spot) != null -> WidgetOptions(model, spot)
        item is ComposedItem.ItemList -> ListAsk(item) { open, flat -> model.listProps(open, flat) }
        item is ComposedItem.Row && spot.cell >= 0 -> {
            val style = ComposedStyle.styleAt(model.doc, spot)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (value in ComposedStyle.STEPS.getValue("valign")) {
                    Chip(
                        w.stepName("valign", value),
                        kind = if (style["valign"] == value) ChipKind.Selected else ChipKind.Quiet,
                        onClick = { set("valign", if (style["valign"] == value) null else value) },
                        modifier = Modifier.testTag("editor:style.valign.$value"),
                    )
                }
            }
        }
        else -> Tertiary(w.noProps)
    }
}

/** Свойства самого виджета: вид ссылки, «полностью» или «обрезать» и имя владельца у вставленного. */
@Composable
private fun WidgetOptions(model: EditorModel, spot: Spot) {
    val w = Tima.words.editor
    val widget = widgetAt(model.doc, spot)
    if (widget == null) {
        Tertiary(w.nothingChosen)
        return
    }
    fun put(next: ComposedWidget) = model.layout { d, s -> withWidget(d, s, next) to s }
    when (widget) {
        // Ссылка (ADR-0050 §3): вид задаёт свойство — по умолчанию кнопка «Перейти»; карточка — из сети
        // по выбору, не сама; цвет — её свой.
        is ComposedWidget.Link -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (mode in listOf("plain", "button", "card")) {
                    Chip(w.linkMode(mode), kind = if (widget.mode == mode) ChipKind.Selected else ChipKind.Quiet,
                        onClick = {
                            put(widget.copy(mode = mode))
                            if (mode == "card" && widget.title.isEmpty() && widget.description.isEmpty()) model.onLink?.invoke(widget.url)
                        },
                        modifier = Modifier.testTag("editor:style.link.$mode"))
                }
            }
            // Название — правится здесь (заказчик 2026-10-21): у кнопки и текста им и показывается ссылка.
            BasicTextField(
                value = widget.title,
                onValueChange = { put(widget.copy(title = it.take(LINK_TITLE_MAX))) },
                textStyle = TextStyle(fontSize = TimaType.sz5, color = Tima.colors.text),
                cursorBrush = SolidColor(Tima.colors.navigation),
                singleLine = true,
                decorationBox = { inner ->
                    Box {
                        if (widget.title.isEmpty()) Caption(w.linkTitle, fontSize = TimaType.sz5, color = Tima.colors.text3)
                        inner()
                    }
                },
                modifier = Modifier.fillMaxWidth().background(Tima.colors.functional, RoundedCornerShape(8.dp)).padding(8.dp)
                    .testTag("editor:style.link.title"),
            )
            PaletteRow(widget.color.ifEmpty { null }, "editor:style.link.color", only = LinkColors) { c -> put(widget.copy(color = c.orEmpty())) }
        }
        is ComposedWidget.Quoted -> RefOptions("quoted", widget.author, widget.lines, widget.more, widget.moreText, widget.tap) { au, li, mo, mt, ta ->
            put(widget.copy(author = au, lines = li, more = mo, moreText = mt, tap = ta))
        }
        is ComposedWidget.Cloud -> androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (kind in listOf("file", "document", "audio", "video")) {
                Chip(Tima.words.chat.cloudKind(kind), kind = if (widget.kind == kind) ChipKind.Selected else ChipKind.Quiet,
                    onClick = { put(widget.copy(kind = kind)) }, modifier = Modifier.testTag("editor:style.cloud.$kind"))
            }
        }
        is ComposedWidget.Underlay -> RefOptions("underlay", widget.author, widget.lines, widget.more, widget.moreText, widget.tap) { au, li, mo, mt, ta ->
            put(widget.copy(author = au, lines = li, more = mo, moreText = mt, tap = ta))
        }
        // Метки — правятся окном меток; их не видно в сообщении — не видно и здесь, потому пункт и у видео,
        // и у аудио (заказчик 2026-10-24). Нет меток — то же окно их создаёт.
        is ComposedWidget.Video -> EditMarks(model, widget.description)
        is ComposedWidget.Audio -> EditMarks(model, widget.description)
        is ComposedWidget.Marks -> androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            EditMarks(model, widget.bind)
            Chip(w.marksHidden, kind = if (widget.hidden) ChipKind.Selected else ChipKind.Quiet,
                onClick = { put(widget.copy(hidden = !widget.hidden)) }, modifier = Modifier.testTag("editor:style.marks.hidden"))
        }
        else -> Tertiary(w.selected("widget"))
    }
}

/** «Редактировать метки» к видео или аудио с описанием [bind]; без описания меток не бывает (Р10). */
@Composable
private fun EditMarks(model: EditorModel, bind: String) {
    val w = Tima.words.editor
    if (bind.isBlank()) {
        Tertiary(w.marksNoTarget)
        return
    }
    Chip(w.marksEdit, kind = ChipKind.Quiet, onClick = { model.onEditMarks?.invoke(bind) }, modifier = Modifier.testTag("editor:style.marks.edit"))
}

/**
 * Свойства ссылки на сообщение — одни у подборки и подкладки (ADR-0050 §1): имя владельца; переход —
 * один из пяти (заказчик 2026-10-20): без перехода, само сообщение, «Продолжить чтение», свой текст,
 * стрелка; строк (целиком или N).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RefOptions(
    tag: String,
    author: Boolean,
    lines: Int,
    more: String,
    moreText: String,
    tap: Boolean,
    onChange: (author: Boolean, lines: Int, more: String, moreText: String, tap: Boolean) -> Unit,
) {
    val colors = Tima.colors
    val w = Tima.words.editor
    fun kindOf(on: Boolean) = if (on) ChipKind.Selected else ChipKind.Quiet
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip(w.authorShown, kind = kindOf(author), onClick = { onChange(!author, lines, more, moreText, tap) },
            modifier = Modifier.testTag("editor:style.$tag.author"))
    }
    // «Само сообщение» — поле `tap`, остальное — `more`; вместе не бывают.
    val jump = if (tap) "tap" else more
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (mode in listOf("", "tap", "default", "custom", "blank")) {
            Chip(
                w.moreMode(mode),
                kind = kindOf(jump == mode),
                onClick = { onChange(author, lines, if (mode == "tap") "" else mode, moreText, mode == "tap") },
                modifier = Modifier.testTag(if (mode == "tap") "editor:style.$tag.tap" else "editor:style.$tag.more.${mode.ifEmpty { "none" }}"),
            )
        }
    }
    if (more == "custom" && !tap) {
        BasicTextField(
            value = moreText,
            onValueChange = { onChange(author, lines, more, it.take(MORE_TEXT_MAX), tap) },
            textStyle = TextStyle(fontSize = TimaType.sz5, color = colors.text),
            cursorBrush = SolidColor(colors.navigation),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().background(colors.functional, RoundedCornerShape(8.dp)).padding(8.dp)
                .testTag("editor:style.$tag.more.text"),
        )
    }
    // Строк — последним рядом, только «Целиком», 1, 2, 3 (заказчик 2026-10-20).
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (n in listOf(0, 1, 2, 3)) {
            Chip(if (n == 0) w.linesAll else w.underlayLines(n), kind = kindOf(lines == n), onClick = { onChange(author, n, more, moreText, tap) },
                modifier = Modifier.testTag("editor:style.$tag.lines.$n"))
        }
    }
}

/** Название ссылки — строкой, не длиннее этого. */
private const val LINK_TITLE_MAX = 80

/** Свой текст «Продолжить» — не длиннее, чем принимает проверка разметки. */
private const val MORE_TEXT_MAX = 40

/** JSON стиля выбранного — только изменённое; «Применить» — после той же проверки, что на отправке. */
@Composable
private fun JsonStyle(model: EditorModel, spot: Spot, style: Map<String, String>, problems: (ComposedDoc) -> List<String>) {
    val colors = Tima.colors
    val w = Tima.words.editor
    var text by remember(spot, style) { mutableStateOf(ComposedStyle.toJson(style)) }
    var trouble by remember(spot) { mutableStateOf<String?>(null) }
    BasicTextField(
        value = text,
        onValueChange = { text = it; trouble = null },
        textStyle = TextStyle(fontSize = TimaType.sz5, color = colors.text, fontFamily = FontFamily.Monospace),
        cursorBrush = SolidColor(colors.navigation),
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).background(colors.functional, RoundedCornerShape(8.dp)).padding(8.dp)
            .testTag("editor:style.json.text"),
    )
    trouble?.let { Caption(it, fontSize = TimaType.sz5, color = colors.alarm) }
    Chip(
        w.apply,
        kind = ChipKind.Selected,
        onClick = {
            val parsed = ComposedStyle.fromJson(text)
            if (parsed == null) {
                trouble = w.jsonBad
            } else {
                val found = problems(ComposedStyle.setStyle(model.doc, spot, parsed))
                if (found.isNotEmpty()) trouble = found.first()
                else model.styled { d, s -> ComposedStyle.setStyle(d, s, parsed) }
            }
        },
        modifier = Modifier.testTag("editor:style.json.apply"),
    )
}
