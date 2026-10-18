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
private val PALETTE_ORDER = listOf("text", "surface", "accent", "accent-soft", "amber", "amber-soft", "green", "green-soft", "blue", "blue-soft", "red", "red-soft", "grey", "grey-soft")

/** Виджет на выбранном месте: во всю ширину или в ячейке ряда. */
private fun widgetAt(doc: ComposedDoc, at: Spot): ComposedWidget? = when (val item = doc.items.getOrNull(at.item)) {
    is ComposedItem.Widget -> item.widget.takeIf { at.cell == -1 }
    is ComposedItem.Row -> item.cells.getOrNull(at.cell)?.widgets?.getOrNull(at.widget)
    else -> null
}

private fun withWidget(doc: ComposedDoc, at: Spot, widget: ComposedWidget): ComposedDoc = doc.copy(
    items = doc.items.mapIndexed { i, item ->
        when {
            i != at.item -> item
            item is ComposedItem.Widget && at.cell == -1 -> item.copy(widget = widget)
            item is ComposedItem.Row -> item.copy(
                cells = item.cells.mapIndexed { c, cell ->
                    if (c != at.cell) cell else cell.copy(widgets = cell.widgets.mapIndexed { w, old -> if (w == at.widget) widget else old })
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
    fun set(prop: String, value: String?) = model.layout { d, s -> ComposedStyle.with(d, s, prop, value) to s }

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
            "align" -> {
                steps("align")
                // У ячейки — ещё и по вертикали.
                if (spot.cell >= 0) steps("valign")
            }
            "padding" -> steps("padding")
            "divider" -> {
                steps("divider")
                palette("divider_color")
            }
            "widget" -> WidgetOptions(model, spot)
            "json" -> JsonStyle(model, spot, style, problems)
        }
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
        is ComposedWidget.Link -> androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (mode in listOf("plain", "button", "card")) {
                Chip(w.linkMode(mode), kind = if (widget.mode == mode) ChipKind.Selected else ChipKind.Quiet,
                    onClick = { put(widget.copy(mode = mode)) }, modifier = Modifier.testTag("editor:style.link.$mode"))
            }
        }
        is ComposedWidget.Quoted -> androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (cut in listOf("full", "cut")) {
                Chip(w.cutName(cut), kind = if ((widget.cut.ifEmpty { "full" }) == cut) ChipKind.Selected else ChipKind.Quiet,
                    onClick = { put(widget.copy(cut = cut)) }, modifier = Modifier.testTag("editor:style.quoted.$cut"))
            }
            Chip(w.authorShown, kind = if (widget.author) ChipKind.Selected else ChipKind.Quiet,
                onClick = { put(widget.copy(author = !widget.author)) }, modifier = Modifier.testTag("editor:style.quoted.author"))
        }
        is ComposedWidget.Cloud -> androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (kind in listOf("file", "document", "audio", "video")) {
                Chip(Tima.words.chat.cloudKind(kind), kind = if (widget.kind == kind) ChipKind.Selected else ChipKind.Quiet,
                    onClick = { put(widget.copy(kind = kind)) }, modifier = Modifier.testTag("editor:style.cloud.$kind"))
            }
        }
        is ComposedWidget.Underlay -> androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (n in listOf(1, 2, 3, 5, 10)) {
                Chip(w.underlayLines(n), kind = if (widget.lines == n) ChipKind.Selected else ChipKind.Quiet,
                    onClick = { put(widget.copy(lines = n)) }, modifier = Modifier.testTag("editor:style.underlay.$n"))
            }
            Chip(w.authorShown, kind = if (widget.author) ChipKind.Selected else ChipKind.Quiet,
                onClick = { put(widget.copy(author = !widget.author)) }, modifier = Modifier.testTag("editor:style.underlay.author"))
        }
        else -> Tertiary(w.selected("widget"))
    }
}

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
                else model.layout { d, s -> ComposedStyle.setStyle(d, s, parsed) to s }
            }
        },
        modifier = Modifier.testTag("editor:style.json.apply"),
    )
}
