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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.gestures.animateScrollBy
import io.tima.core.media.fileDrop
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
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
    const val PROBLEMS = "editor:problems"
    const val SYMBOL_AREA = "editor:symbol.area"
    /** Место сообщения; нажатие на пустое под ним — писать после всего. */
    const val BODY = "editor:body"
    /** Пустое место под сообщением — прокручивается вместе с ним. */
    const val TAIL = "editor:tail"
    fun tab(key: String) = "editor:tab.$key"
    fun button(key: String) = "editor:$key"
    fun level(level: Int) = "editor:level.$level"
    fun paragraph(kind: ParagraphKind) = "editor:paragraph.${kind.name.lowercase()}"
    fun text(index: Int) = "editor:text.$index"
    fun item(index: Int) = "editor:item.$index"
    fun cell(index: Int, cell: Int) = "editor:cell.$index.$cell"
    fun cellText(index: Int, cell: Int) = "editor:cell.$index.$cell.text"
    /** Виджет: во всю ширину — `editor:widget.N`, в ячейке — `editor:widget.N.C.W`. */
    fun spot(s: io.tima.domain.chat.Spot) = if (s.cell < 0) "editor:widget.${s.item}" else "editor:widget.${s.item}.${s.cell}.${s.widget}"
    /** Рамка между столбцами ряда N: `editor:border.N.B`. */
    fun border(item: Int, boundary: Int) = "editor:border.$item.$boundary"
    /** Линейка рамок над выбранным рядом N. */
    fun ruler(item: Int) = "editor:ruler.$item"
    const val GRID = "editor:grid"
    /** «Список»: окно свойств списка. */
    const val LIST = "editor:list"
    /** Список N, точка пункта I (выбрать), заголовок и содержимое пункта (ADR-0049). */
    fun list(index: Int) = "editor:list.$index"
    fun listItem(index: Int, item: Int) = "editor:list.$index.$item"
    fun listTitle(index: Int, item: Int) = "editor:list.$index.$item.title"
    fun listText(index: Int, item: Int) = "editor:list.$index.$item.text"
}

/** Разделы редактора и кнопки ленты (Решения §10.2) — ключи подписей `EditorWords.button`. */
internal enum class EditorTab(val key: String, val buttons: List<String>) {
    TEXT("text", listOf("paragraph", "bold", "italic", "underline", "strike", "highlight", "code", "marks", "symbol", "paste")),
    INSERT("insert", listOf("picture", "link", "emoji", "picks", "underlay", "cloud", "place", "contact", "reply")),
    LAYOUT("layout", listOf("row", "list", "colAdd", "colRemove", "lineAdd", "lineRemove", "joinLeft", "joinUp", "split", "up", "down", "delete")),
    STYLE("style", listOf("widget", "background", "color", "size", "align", "padding", "divider", "reset", "json")),
}

/**
 * «Ряд»: сколько строк (до 20) и столбцов (до 7) — числа кнопками «−» и «+» (заказчик 2026-10-18).
 * Ряд встаёт с равными столбцами.
 */
@Composable
private fun GridAsk(onInsert: (lines: Int, columns: Int) -> Unit) {
    val colors = Tima.colors
    val w = Tima.words.editor
    var lines by remember { mutableStateOf(1) }
    var columns by remember { mutableStateOf(2) }
    Column(
        Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2)
            .testTag(EditorTags.GRID),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Stepper(w.gridLines, lines, 1..io.tima.domain.chat.ComposedLayout.MAX_LINES, "lines") { lines = it }
        Stepper(w.gridColumns, columns, 1..io.tima.domain.chat.ComposedLayout.MAX_COLUMNS, "columns") { columns = it }
        io.tima.core.ui.Chip(
            w.gridInsert,
            kind = io.tima.core.ui.ChipKind.Selected,
            onClick = { onInsert(lines, columns) },
            modifier = Modifier.testTag("editor:grid.ok"),
        )
    }
}

/** «Список»: «Развернуть по умолчанию», «Без отступа» — на весь список (ADR-0049 §3); нажата — включено. */
@Composable
private fun ListAsk(list: io.tima.domain.chat.ComposedItem.ItemList, onChange: (open: Boolean, flat: Boolean) -> Unit) {
    val colors = Tima.colors
    val w = Tima.words.editor
    Row(
        Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2)
            .testTag(EditorTags.LIST),
        horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
    ) {
        io.tima.core.ui.Chip(
            w.listOpen,
            kind = if (list.open) io.tima.core.ui.ChipKind.Selected else io.tima.core.ui.ChipKind.Neutral,
            onClick = { onChange(!list.open, list.flat) },
            modifier = Modifier.testTag("editor:list.open"),
        )
        io.tima.core.ui.Chip(
            w.listFlat,
            kind = if (list.flat) io.tima.core.ui.ChipKind.Selected else io.tima.core.ui.ChipKind.Neutral,
            onClick = { onChange(list.open, !list.flat) },
            modifier = Modifier.testTag("editor:list.flat"),
        )
    }
}

@Composable
private fun Stepper(label: String, value: Int, range: IntRange, key: String, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2)) {
        Caption(label, fontSize = TimaType.sz4, modifier = Modifier.width(110.dp))
        HeaderGlyph("−", value > range.first, "editor:grid.$key.minus") { onChange(value - 1) }
        Caption(value.toString(), fontSize = TimaType.sz4, weight = FontWeight.Bold, modifier = Modifier.testTag("editor:grid.$key"))
        HeaderGlyph("+", value < range.last, "editor:grid.$key.plus") { onChange(value + 1) }
    }
}

/** Значок кнопки ленты — постоянный; буквы оформления и подпись под значком — из словаря. */
internal fun editorGlyph(key: String): String = when (key) {
    "paragraph" -> "¶"
    "list" -> "≡"
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
    "colAdd" -> "+‖"
    "colRemove" -> "−‖"
    "lineAdd" -> "+═"
    "lineRemove" -> "−═"
    "joinLeft" -> "⇤"
    "joinUp" -> "⤒"
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
 * @param onDraft документ поменялся — черновик пишется сразу, а не только по «‹»: уход из
 *   приложения, системная «Назад», закрытый процесс его не теряют (заказчик 2026-10-18).
 */
@Composable
fun EditorScreen(
    model: EditorModel,
    levels: List<MessageCircle>,
    defaultLevel: Int,
    preview: (ComposedDoc) -> RichContent?,
    problems: (ComposedDoc) -> List<String>,
    onBack: (ComposedDoc) -> Unit,
    onSend: (ComposedDoc) -> Unit,
    /** Кнопки ленты, которых нет в первом срезе, — их обработчик; `null` — подсказка «следующим срезом». */
    onButton: ((String) -> Boolean)? = null,
    /** Сказать одно предложение в разделителе — «картинка не загрузилась» и подобное. */
    notice: String? = null,
    /** ПК: стрелки ленты в разделителе (§10.2). */
    desktop: Boolean = false,
    /** Файлы, перетащенные в редактор (ПК); `null` — не принимаем. */
    onFiles: ((List<io.tima.core.media.PickedFile>) -> Unit)? = null,
    onDraft: ((ComposedDoc) -> Unit)? = null,
    /** Название в шапке; `null` — «Редактор». Правка своего сообщения — «Изменение». */
    title: String? = null,
    /** «Знаки md» переключены — одна галочка на редактор и строку ввода (ADR-0048). */
    onMarks: ((Boolean) -> Unit)? = null,
) {
    val colors = Tima.colors
    val w = Tima.words.editor
    val keyboard = LocalSoftwareKeyboardController.current
    var tab by rememberSaveable { mutableStateOf(EditorTab.TEXT) }
    var showPreview by remember { mutableStateOf(false) }
    // Одно окно выбора за раз (заказчик 2026-10-19: «открыли Ряд, затем Абзац — ряд не закрылся»):
    // `level`, `paragraph`, `symbol`, `emoji`, `grid` (Ряд), `list` (Список), `style.<ключ>`.
    var panel by remember { mutableStateOf<String?>(null) }
    fun toggle(key: String) { panel = if (panel == key) null else key }
    val clipboardHtml = io.tima.core.media.rememberClipboardHtml()
    val clipboardImage = io.tima.core.media.rememberClipboardImage()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var hint by remember { mutableStateOf<String?>(null) }
    val recent = remember { mutableStateListOf<String>() }
    val doc = model.doc
    val ribbonScroll = rememberScrollState()
    val ribbonScope = androidx.compose.runtime.rememberCoroutineScope()
    var dropping by remember { mutableStateOf(false) }
    // Таблица шире пузыря — красная и просьба изменить; отправить нельзя (§10.3).
    val wide = remember(doc) { io.tima.domain.chat.TableFit.tooWide(doc) }
    val trouble = remember(doc, wide) {
        problems(doc) + wide.map { i ->
            when (doc.items.getOrNull(i)) {
                is ComposedItem.Row -> w.rowTooNarrow
                is ComposedItem.ItemList -> w.listTooNarrow
                else -> w.tableTooWide(io.tima.domain.chat.TableFit.maxColumns())
            }
        }.distinct()
    }
    val level = doc.level ?: defaultLevel
    // Поле без знаков (ADR-0048): упоминания и метки — цветом, открытый знак — серым.
    val typing: TypingMarks = model.source
    // Пункт ступени без родителя получает заголовок из словаря (ADR-0049 §4).
    model.untitled = w.untitled
    val active = model.activeInline().map { it.name.lowercase() }.toSet()

    // Черновик — через полсекунды после последней правки и при закрытии окна.
    if (onDraft != null) {
        val draft by rememberUpdatedState(onDraft)
        LaunchedEffect(model) {
            var first = true
            snapshotFlow { model.doc }.collectLatest { d ->
                if (first) { first = false; return@collectLatest }
                kotlinx.coroutines.delay(DRAFT_PAUSE_MS)
                draft(d)
            }
        }
        DisposableEffect(model) { onDispose { draft(model.doc) } }
    }

    fun press(key: String) {
        hint = null
        when (key) {
            "paragraph" -> toggle("paragraph")
            "bold" -> model.inline(InlineKind.BOLD)
            "italic" -> model.inline(InlineKind.ITALIC)
            "underline" -> model.inline(InlineKind.UNDERLINE)
            "strike" -> model.inline(InlineKind.STRIKE)
            "highlight" -> model.inline(InlineKind.HIGHLIGHT)
            "code" -> model.inline(InlineKind.CODE)
            // «Знаки md» (ADR-0048): включены — закрытая пара сразу оформление, выключены — знаки текстом.
            "marks" -> {
                model.toggleMarks()
                hint = if (model.doc.marks) w.marksOn else w.marksOff
                onMarks?.invoke(model.doc.marks)
            }
            "symbol" -> { keyboard?.hide(); toggle("symbol") }
            "emoji" -> { keyboard?.hide(); toggle("emoji") }
            // Из HTML — только текст и простое оформление (Решения §5); HTML нет — текст как есть.
            "paste" -> {
                // Картинка в буфере — вставляется картинкой, тем же путём, что перетащенный файл.
                val picture = onFiles?.let { clipboardImage() }
                if (picture != null) {
                    onFiles?.invoke(listOf(picture))
                    return
                }
                // HTML — оформление знаками («С оформл.»); простой текст — как набранный, знаками не оформляется.
                val marked = clipboardHtml()?.let(io.tima.domain.chat.HtmlToMarks::convert)?.takeIf { it.isNotBlank() }
                if (marked != null) model.paste(marked) else model.paste(clipboard.getText()?.text.orEmpty(), plain = true)
            }
            // Раздел «Разметка» (§10.2–§10.3; заказчик 2026-10-18): ряд строк × столбцов, слияние в оба
            // направления; таблицы в редакторе больше нет — она стала рядом.
            "row" -> toggle("grid")
            // Свойства всего списка (ADR-0049 §3): курсор должен стоять в списке.
            "list" -> if (model.listHere == null) hint = w.listChoose else toggle("list")
            "colAdd" -> model.layout(io.tima.domain.chat.ComposedLayout::addColumn)
            "colRemove" -> model.layout(io.tima.domain.chat.ComposedLayout::removeColumn)
            "lineAdd" -> model.layout(io.tima.domain.chat.ComposedLayout::addLine)
            "lineRemove" -> model.layout(io.tima.domain.chat.ComposedLayout::removeLine)
            "joinLeft" -> if (!model.join("left")) hint = w.joinRefused
            "joinUp" -> if (!model.join("up")) hint = w.joinRefused
            "split" -> model.layout(io.tima.domain.chat.ComposedLayout::split)
            "up" -> model.layout { d, s -> io.tima.domain.chat.ComposedLayout.shift(d, s, -1) }
            "down" -> model.layout { d, s -> io.tima.domain.chat.ComposedLayout.shift(d, s, 1) }
            "delete" -> model.layout(io.tima.domain.chat.ComposedLayout::removeAt)
            // Раздел «Стиль» (§10.2): на выбранное.
            "widget", "background", "color", "size", "align", "padding", "divider", "json" -> toggle("style.$key")
            "reset" -> model.layout { d, s -> io.tima.domain.chat.ComposedStyle.setStyle(d, s, emptyMap()) to s }
            else -> if (onButton?.invoke(key) != true) hint = w.soon
        }
    }

    Column(
        Modifier.fillMaxSize().background(colors.surface).imePadding().testTag(EditorTags.WINDOW)
            // Редактор — подокно поверх переписки. Нажатие мимо кнопок не уходит в переписку под
            // ним: раньше палец между кнопками шапки попадал в шапку переписки — и открывалась
            // страница собеседника (заказчик 2026-10-18).
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
            .onPreviewKeyEvent { e -> editorKeys(e, model, trouble.isEmpty(), onSend = { onSend(model.doc) }, onBack = { onBack(model.doc) }) },
    ) {
        // ── Шапка: ‹ · название · ↶ ↷ · цифра уровня · ➤ (§10.1) ──
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = TimaSpacing.about2),
            horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(glyph = "‹", onClick = { onBack(model.doc) }, live = true, modifier = Modifier.testTag(EditorTags.BACK))
            Column(
                Modifier.weight(1f).heightIn(min = 48.dp).clickable { showPreview = !showPreview }.testTag(EditorTags.TITLE),
                verticalArrangement = Arrangement.Center,
            ) {
                Caption(title ?: w.title, fontSize = TimaType.sz4, weight = FontWeight.Bold, lineOne = true)
                if (showPreview) Tertiary(w.previewOn, lineOne = true)
            }
            HeaderGlyph("↶", model.canUndo, EditorTags.UNDO) { model.undo() }
            HeaderGlyph("↷", model.canRedo, EditorTags.REDO) { model.redo() }
            LevelButton(level, enabled = levels.size > 1) { toggle("level") }
            HeaderGlyph("➤", !doc.isEmpty && trouble.isEmpty(), EditorTags.SEND) { onSend(model.doc) }
        }
        if (panel == "level") LevelList(levels, level) { picked -> model.level(picked); panel = null }

        // Общая прокрутка (заказчик 2026-10-19): разделы, лента, окно выбора, разделитель и сообщение —
        // одним столбцом. Открытое окно не закрывает сообщение на повёрнутом экране: всё ниже него
        // доезжает прокруткой.
        val drag = rememberEditorDrag()
        drag.forget(doc.items.size)
        var top by remember { mutableStateOf(0) }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val window = maxHeight
            val density = androidx.compose.ui.platform.LocalDensity.current
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState(), enabled = drag.from == null)) {
                Column(Modifier.fillMaxWidth().onGloballyPositioned { top = it.size.height }) {
                    // ── Разделы и лента (§10.2) ──
                    Row(Modifier.fillMaxWidth().padding(horizontal = TimaSpacing.about2), horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about1)) {
                        for (t in EditorTab.entries) {
                            io.tima.core.ui.Tab(
                                label = w.tab(t.key),
                                current = t == tab,
                                onClick = { tab = t; panel = null; hint = null },
                                modifier = Modifier.testTag(EditorTags.tab(t.key)),
                            )
                        }
                    }
                    Ribbon(
                        tab, marksOn = doc.marks, active = active, paragraph = w.paragraph(model.paragraphHere.name),
                        scroll = ribbonScroll, onPress = ::press,
                    )
                    if (panel == "paragraph") ParagraphList(model.paragraphHere) { kind -> model.paragraph(kind); panel = null }
                    panel?.takeIf { it.startsWith("style.") }?.let { StylePanel(it.removePrefix("style."), model, problems) }
                    if (panel == "list") model.listHere?.let { l -> ListAsk(l) { open, flat -> model.listProps(open, flat) } }
                    if (panel == "grid") {
                        GridAsk { lines, columns ->
                            panel = null
                            model.layout { d, s -> io.tima.domain.chat.ComposedLayout.addRow(d, s, lines, columns) }
                        }
                    }

                    // Разделитель: что выбрано (§10.2) и подсказка последнего нажатия.
                    Row(
                        Modifier.fillMaxWidth().background(colors.functional).padding(horizontal = TimaSpacing.about4, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.weight(1f)) { Tertiary(notice ?: hint ?: w.selected(selectedKind(model)), lineOne = true) }
                        // Стрелки ленты — только если кнопки раздела не помещаются (заказчик 2026-10-19).
                        // Длина ленты известна после разметки (до неё — «бесконечность»): прокрутки нет — стрелок нет.
                        if (desktop && ribbonScroll.maxValue in 1 until Int.MAX_VALUE) {
                            HeaderGlyph("‹", ribbonScroll.canScrollBackward, "editor:ribbon.back") {
                                ribbonScope.launch { ribbonScroll.animateScrollBy(-RIBBON_STEP) }
                            }
                            HeaderGlyph("›", ribbonScroll.canScrollForward, "editor:ribbon.forward") {
                                ribbonScope.launch { ribbonScroll.animateScrollBy(RIBBON_STEP) }
                            }
                        }
                    }
                }
                // Над выделенным полоски Ж К Ч нет (заказчик 2026-10-18): она ложилась на системное «Вырезать ·
                // Копировать · Выделить всё». Оформляют кнопки ленты — нажата, если выделенное уже такое.

                // ── Сообщение ── не ниже окна под лентой: под ним всегда есть пустое место.
                val rest = with(density) { (window - top.toDp()).coerceAtLeast(EDITOR_TAIL_MIN) }
                Box(
                    Modifier.fillMaxWidth().heightIn(min = rest)
                        .onGloballyPositioned { drag.origin = it.boundsInRoot().topLeft }
                        .then(if (onFiles != null) Modifier.fileDrop(onHover = { dropping = it }, onFiles = onFiles) else Modifier)
                        .then(if (dropping) Modifier.border(3.dp, colors.navigation) else Modifier)
                        // Пустое место под сообщением — писать после всего, как Enter в конце (заказчик
                        // 2026-10-18): после виджета и ряда иначе некуда было поставить курсор.
                        .pointerInput(showPreview) {
                            detectTapGestures { at -> if (!showPreview && drag.origin.y + at.y > drag.bottom()) model.writeAtEnd() }
                        }
                        .testTag(EditorTags.BODY),
                ) {
                    Column(Modifier.fillMaxWidth().padding(TimaSpacing.about3)) {
                        if (showPreview) {
                            Bubble(my = true, modifier = Modifier.testTag(EditorTags.PREVIEW)) {
                                preview(doc)?.let { RichBody(it) } ?: Caption(doc.firstLine, fontSize = TimaType.sz4)
                            }
                        } else {
                            doc.items.forEachIndexed { i, item ->
                                Box(Modifier.fillMaxWidth().onGloballyPositioned { drag.item(i, it.boundsInRoot()) }) {
                                    EditorItemView(model, i, item, typing, preview, drag, tooWide = i in wide, levels = levels)
                                }
                            }
                            // Пустое место под сообщением всегда есть (заказчик 2026-10-18): сообщение заняло окно
                            // целиком — прокрутка уводит его выше, и под ним можно нажать, чтобы писать дальше.
                            Box(Modifier.fillMaxWidth().height(maxOf(window / 3, EDITOR_TAIL_MIN)).testTag(EditorTags.TAIL))
                        }
                    }
                }
            }
            DragGhost(drag)
        }
        if (trouble.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().background(colors.alarm.copy(alpha = 0.12f)).padding(TimaSpacing.about3).testTag(EditorTags.PROBLEMS)) {
                Caption(w.cannotSend, fontSize = TimaType.sz5, weight = FontWeight.Bold, color = colors.alarm)
                for (p in trouble.take(3)) Caption(p, fontSize = TimaType.sz5, color = colors.alarm)
            }
        }
        if (panel == "emoji") EmojiArea(onClose = { panel = null }) { e ->
            model.insert(ComposedItem.Widget(io.tima.domain.chat.ComposedWidget.Emoji(e)))
            panel = null
        }
        if (panel == "symbol") SymbolArea(recent, onClose = { panel = null }) { s ->
            model.symbol(s)
            recent.remove(s)
            recent.add(0, s)
            if (recent.size > 24) recent.removeAt(recent.lastIndex)
        }
    }
}

private fun selectedKind(model: EditorModel): String {
    val s = model.spot
    return when (model.doc.items.getOrNull(s.item)) {
        is ComposedItem.Row -> when {
            s.widget >= 0 -> "widget"
            s.cell >= 0 && s.widget == -1 -> "cell"
            s.cell >= 0 -> "text"
            else -> "row"
        }
        is ComposedItem.Widget -> "widget"
        is ComposedItem.ItemList -> if (s.widget >= 0) "widget" else if (s.widget == -1) "item" else "text"
        else -> "text"
    }
}

/** Пустое место под сообщением не меньше этого; обычно — треть окна сообщения. */
private val EDITOR_TAIL_MIN = 160.dp

/** Пауза набора, после которой документ пишется черновиком. */
private const val DRAFT_PAUSE_MS = 500L

/**
 * Ширина кнопки ленты — одна на телефоне, повёрнутом экране и ПК (заказчик 2026-10-19: при повороте
 * кнопки разбежались, как прежде на ПК); не помещаются — лента прокручивается.
 */
private val RIBBON_BUTTON = 56.dp

/** На сколько сдвигается лента стрелкой на ПК — три кнопки. */
private const val RIBBON_STEP = 180f

/**
 * Сочетания клавиш редактора (Решения §10.2, ПК): Ctrl+B, I, U — жирный, курсив, подчёркнутый;
 * Ctrl+Shift+X — зачёркнутый, Ctrl+Shift+H — маркер, Ctrl+E — код; Ctrl+1…3 — заголовки, Ctrl+0 —
 * абзац; Ctrl+Z, Ctrl+Y и Ctrl+Shift+Z — отменить и повторить; Ctrl+Enter — отправить; Esc — назад.
 */
internal fun editorKeys(e: androidx.compose.ui.input.key.KeyEvent, model: EditorModel, canSend: Boolean, onSend: () -> Unit, onBack: () -> Unit): Boolean =
    e.type == KeyEventType.KeyDown && editorCommand(e.key, e.isCtrlPressed || e.isMetaPressed, e.isShiftPressed, model, canSend, onSend, onBack)

/** Сама команда по клавише — отдельно от события, чтобы проверять её без окна. */
internal fun editorCommand(key: Key, ctrl: Boolean, shift: Boolean, model: EditorModel, canSend: Boolean, onSend: () -> Unit, onBack: () -> Unit): Boolean {
    fun mark(kind: InlineKind): Boolean { model.inline(kind); return true }
    fun heading(kind: ParagraphKind): Boolean { model.paragraph(kind); return true }
    return when {
        key == Key.Escape -> { onBack(); true }
        !ctrl -> false
        key == Key.B -> mark(InlineKind.BOLD)
        key == Key.I -> mark(InlineKind.ITALIC)
        key == Key.U -> mark(InlineKind.UNDERLINE)
        shift && key == Key.X -> mark(InlineKind.STRIKE)
        shift && key == Key.H -> mark(InlineKind.HIGHLIGHT)
        key == Key.E -> mark(InlineKind.CODE)
        key == Key.One -> heading(ParagraphKind.HEADING1)
        key == Key.Two -> heading(ParagraphKind.HEADING2)
        key == Key.Three -> heading(ParagraphKind.HEADING3)
        key == Key.Zero -> heading(ParagraphKind.PARAGRAPH)
        shift && key == Key.Z -> { model.redo(); true }
        key == Key.Z -> { model.undo(); true }
        key == Key.Y -> { model.redo(); true }
        key == Key.Enter -> { if (canSend && !model.doc.isEmpty) onSend(); true }
        else -> false
    }
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

/**
 * Лента (§10.2): кнопки одной ширины [RIBBON_BUTTON]; не помещаются — прокрутка. «Абзац» — шире: «▾» и
 * название того, что включено под курсором, сколько вместится (заказчик 2026-10-19).
 */
@Composable
private fun Ribbon(tab: EditorTab, marksOn: Boolean, active: Set<String>, paragraph: String, scroll: androidx.compose.foundation.ScrollState, onPress: (String) -> Unit) {
    val colors = Tima.colors
    val w = Tima.words.editor
    val width = RIBBON_BUTTON
    Box(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(scroll).padding(vertical = 4.dp)) {
            for (key in tab.buttons) {
                if (key == "paragraph") {
                    Column(
                        Modifier.width(width * 2).clickable { onPress(key) }.padding(horizontal = 4.dp, vertical = 4.dp).testTag(EditorTags.button(key)),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier.fillMaxWidth().height(30.dp).background(colors.functional, RoundedCornerShape(8.dp)).padding(horizontal = 6.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Caption("▾ $paragraph", fontSize = 14.sp, weight = FontWeight.Bold, color = colors.text, lineOne = true)
                        }
                        Caption(w.button(key), fontSize = 10.sp, color = colors.text2, lineOne = true)
                    }
                    continue
                }
                // Нажата: «Знаки md» включены; Ж, К… — оформление под курсором или ждёт буквы (Word).
                val on = (key == "marks" && marksOn) || key in active
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

/** Кусок документа: текст набирается здесь же, остальное — как увидит получатель, нажатием выбирается. */
@Composable
private fun EditorItemView(
    model: EditorModel,
    index: Int,
    item: ComposedItem,
    marks: TypingMarks?,
    preview: (ComposedDoc) -> RichContent?,
    drag: EditorDrag,
    tooWide: Boolean = false,
    levels: List<MessageCircle> = emptyList(),
) {
    val colors = Tima.colors
    val chosen = model.spot == io.tima.domain.chat.Spot(index)
    // Стиль куска виден сразу, как у обычного оформления (заказчик 2026-10-18): фон, поля, цвет,
    // размер, выравнивание и разделитель — те же, что в сообщении.
    val look = blockStyleOf(item.style)
    when (item) {
        is ComposedItem.Text -> {
            val shown = model.typedAt(io.tima.domain.chat.Spot(index))
            val value = if (chosen) model.field else androidx.compose.ui.text.input.TextFieldValue(shown.text)
            val focus = remember { FocusRequester() }
            val keyboard = LocalSoftwareKeyboardController.current
            LaunchedEffect(model.focusAsk) {
                if (model.focusAsk > 0 && model.spot == io.tima.domain.chat.Spot(index)) {
                    runCatching { focus.requestFocus() }
                    keyboard?.show()
                }
            }
            BasicTextField(
                value = value,
                onValueChange = { v -> if (chosen) model.changed(index, v) else { model.focused(index); model.changed(index, v) } },
                textStyle = TextStyle(
                    fontSize = sizeOf(look.size, TimaType.sz4),
                    color = io.tima.core.ui.paletteColor(look.color) ?: colors.text,
                    textAlign = alignOf(look.align),
                ),
                cursorBrush = SolidColor(colors.navigation),
                visualTransformation = rememberTypedView(shown, marks, open = model.doc.marks),
                modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(vertical = 4.dp)
                    .styled(look)
                    .then(if (tooWide) Modifier.border(2.dp, colors.alarm, RoundedCornerShape(6.dp)).padding(4.dp) else Modifier)
                    .focusRequester(focus)
                    .onFocusChanged { if (it.isFocused) model.focused(index) }
                    .testTag(EditorTags.text(index)),
            )
            DividerAfter(look)
        }
        is ComposedItem.Widget -> Column(Modifier.fillMaxWidth().styled(look)) {
            EditorWidget(item.widget, io.tima.domain.chat.Spot(index), model.spot == io.tima.domain.chat.Spot(index), model, drag, preview)
            DividerAfter(look)
        }
        is ComposedItem.Row -> EditorRow(item, index, model, drag, marks, preview, tooWide)
        is ComposedItem.Continuation -> ContinuationMarker(item, index, model, levels)
        is ComposedItem.ItemList -> EditorList(item, index, model, marks, drag, preview, tooWide)
    }
}

/** Метка «Продолжение» (Решения §11.3): ниже — закрытая часть своего уровня; цифрой — сменить уровень. */
@Composable
private fun ContinuationMarker(item: ComposedItem.Continuation, index: Int, model: EditorModel, levels: List<MessageCircle>) {
    val colors = Tima.colors
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp)
            .background(colors.functional, RoundedCornerShape(8.dp))
            .clickable { model.select(index) }
            .padding(8.dp)
            .testTag("editor:continuation"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Caption(Tima.words.editor.continuationMarker(item.level), fontSize = TimaType.sz5, weight = FontWeight.Bold, color = colors.text2)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (c in levels.filter { it.level >= 0 }) {
                io.tima.core.ui.Chip(
                    c.level.toString(),
                    kind = if (c.level == item.level) io.tima.core.ui.ChipKind.Selected else io.tima.core.ui.ChipKind.Quiet,
                    onClick = { model.replace(index, ComposedItem.Continuation(c.level)) },
                    modifier = Modifier.testTag("editor:continuation.${c.level}"),
                )
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
