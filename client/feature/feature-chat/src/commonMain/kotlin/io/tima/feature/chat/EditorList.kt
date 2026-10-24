package io.tima.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.tima.core.ui.Caption
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaType
import io.tima.core.ui.words
import io.tima.domain.chat.ComposedDoc
import io.tima.domain.chat.ComposedItem
import io.tima.domain.chat.ListFit
import io.tima.domain.chat.RichContent
import io.tima.domain.chat.Spot
import io.tima.domain.chat.TypingMarks

/**
 * Список в редакторе (ADR-0049 §4) — как ячейки ряда: у пункта точка (нажатие — выбрать пункт, в
 * него встанет виджет), заголовок одной строкой и под ним содержимое — текст, код и виджеты пункта.
 * Поле содержимого видно, когда в нём есть текст или курсор в этом пункте. Перевод строки в
 * заголовке — новый пункт; в пустом — ступенью выше, на первой ступени — конец списка.
 */
@Composable
internal fun EditorList(
    list: ComposedItem.ItemList,
    index: Int,
    model: EditorModel,
    marks: TypingMarks?,
    drag: EditorDrag,
    preview: (ComposedDoc) -> RichContent?,
    tooWide: Boolean,
) {
    val colors = Tima.colors
    val w = Tima.words.editor
    val look = blockStyleOf(list.style)
    val keyboard = LocalSoftwareKeyboardController.current
    val textStyle = TextStyle(
        fontSize = sizeOf(look.size, TimaType.sz4),
        color = io.tima.core.ui.paletteColor(look.color) ?: colors.text,
    )
    // Рамка — видно, где список кончается (заказчик 2026-10-19); не помещается — красная.
    Column(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).styled(look)
            .border(if (tooWide) 2.dp else 1.dp, if (tooWide) colors.alarm else colors.line, RoundedCornerShape(6.dp))
            .padding(4.dp)
            .testTag(EditorTags.list(index)),
    ) {
        list.items.forEachIndexed { i, item ->
            val title = Spot(index, i, Spot.TITLE)
            val content = Spot(index, i, Spot.TEXT)
            val here = model.spot.item == index && model.spot.cell == i
            val chosen = here && model.spot.widget == -1
            // Свой стиль пункта — как у ячейки (заказчик 2026-10-20).
            val own = blockStyleOf(item.style).over(look.copy(background = null, padding = null, divider = null, dividerColor = null))
            val itemText = TextStyle(
                fontSize = sizeOf(own.size, TimaType.sz4),
                color = io.tima.core.ui.paletteColor(own.color) ?: colors.text,
                textAlign = alignOf(own.align),
            )
            Column(
                Modifier.fillMaxWidth()
                    .onGloballyPositioned { drag.listItem(index, i, it.boundsInRoot()) }
                    .padding(start = (ListFit.STEP_DP * (item.depth - 1)).dp)
                    .then(if (here) Modifier.background(colors.functional, RoundedCornerShape(4.dp)) else Modifier)
                    .styled(blockStyleOf(item.style)),
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Caption(
                        when (item.depth) { 1 -> "•"; 2 -> "◦"; else -> "▪" },
                        fontSize = TimaType.sz4,
                        color = if (chosen) colors.navigation else colors.text,
                        modifier = Modifier.width(ListFit.STEP_DP.dp).clickable { model.select(Spot(index, i)) }
                            .testTag(EditorTags.listItem(index, i)),
                    )
                    val shown = model.typedAt(title)
                    val focus = remember { FocusRequester() }
                    LaunchedEffect(model.focusAsk) {
                        if (model.focusAsk > 0 && model.spot == title) {
                            runCatching { focus.requestFocus() }
                            keyboard?.show()
                        }
                    }
                    // Заголовок — одна строка; Enter — кнопка действия клавиатуры, а не перевод строки:
                    // клавиатура Samsung присылала перевод строки так, что заголовок резался не там (2026-10-20).
                    BasicTextField(
                        value = if (model.spot == title) model.field else TextFieldValue(shown.text),
                        onValueChange = { v -> model.changedItem(index, i, Spot.TITLE, v) },
                        textStyle = itemText,
                        cursorBrush = SolidColor(colors.navigation),
                        visualTransformation = rememberTypedView(shown, marks, open = false, color = io.tima.core.ui.paletteColor(blockStyleOf(item.style).color)),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { model.enterItem(index, i) }),
                        modifier = Modifier.weight(1f).heightIn(min = 28.dp).focusRequester(focus)
                            .onFocusChanged { if (it.isFocused) model.focusedItem(index, i, Spot.TITLE) }
                            .onPreviewKeyEvent { e ->
                                val enter = e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)
                                if (enter) model.enterItem(index, i)
                                enter
                            }
                            .testTag(EditorTags.listTitle(index, i)),
                    )
                }
                Column(Modifier.fillMaxWidth().padding(start = ListFit.STEP_DP.dp)) {
                    if (item.text.isNotEmpty() || here) {
                        val shown = model.typedAt(content)
                        val focus = remember { FocusRequester() }
                        LaunchedEffect(model.focusAsk) {
                            if (model.focusAsk > 0 && model.spot == content) runCatching { focus.requestFocus() }
                        }
                        androidx.compose.foundation.layout.Box {
                            if (shown.text.isEmpty() && model.spot != content) Caption(w.listContent, fontSize = TimaType.sz5, color = colors.text3)
                            BasicTextField(
                                value = if (model.spot == content) model.field else TextFieldValue(shown.text),
                                onValueChange = { v -> model.changedItem(index, i, Spot.TEXT, v) },
                                textStyle = itemText,
                                cursorBrush = SolidColor(colors.navigation),
                                visualTransformation = rememberTypedView(shown, marks, open = false, color = io.tima.core.ui.paletteColor(blockStyleOf(item.style).color)),
                                modifier = Modifier.fillMaxWidth().heightIn(min = 24.dp).focusRequester(focus)
                                    .onFocusChanged { if (it.isFocused) model.focusedItem(index, i, Spot.TEXT) }
                                    .testTag(EditorTags.listText(index, i)),
                            )
                        }
                    }
                }
                // Виджет пункта — под его точкой (заказчик 2026-10-19).
                item.widgets.forEachIndexed { k, widget ->
                    EditorWidget(widget, Spot(index, i, k), model.spot == Spot(index, i, k), model, drag, preview)
                }
            }
        }
        DividerAfter(look)
    }
}
