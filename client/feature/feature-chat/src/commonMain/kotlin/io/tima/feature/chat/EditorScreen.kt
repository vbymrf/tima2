package io.tima.feature.chat

import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.ime
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
import androidx.compose.ui.draw.drawWithContent
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
    /** Шапка: свойства блока и эмоджи (заказчик 2026-10-20) — те же, что в ленте, своей меткой. */
    const val HEAD_PROPS = "editor:head.props"
    const val HEAD_EMOJI = "editor:head.emoji"
    const val UNDO = "editor:undo"
    const val REDO = "editor:redo"
    const val LEVEL = "editor:level"
    /** Раздел «Уровень»: серое поле с описанием выбранного уровня (2026-10-21). */
    const val LEVEL_ABOUT = "editor:level.about"
    /** Невидимое поле при выбранном виджете: Enter и буквы — новая строка под ним. */
    const val WIDGET_KEYS = "editor:widget.keys"
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
    /** Шапка: удалить выбранное (заказчик 2026-10-19). */
    const val DELETE = "editor:delete"
    /** «Разметка → Виджет»: окно виджетов. */
    const val WIDGETS = "editor:widgets"
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
    // Заказчик 2026-10-20: «Уровень» — первым в «Тексте»; «Виджет» — первым во «Вставке», «С оформлением» —
    // последним там же; «Вид» (просмотр) и «Свойства» — первыми в «Стиле»; «Выше» и «Ниже» — первыми в
    // «Разметке». Маркер и Код — кнопками в «Тексте», как были (вернули 2026-10-20).
    // 2026-10-21: «Убрать» (ряд целиком) — сразу после «Ряд»; «md» — в конце «Стиля»; «Уровень» —
    // отдельным разделом в конце: кнопки уровней, под ними описание выбранного.
    TEXT("text", listOf("paragraph", "bold", "italic", "underline", "strike", "highlight", "code", "symbol")),
    INSERT("insert", listOf("widget", "picture", "link", "emoji", "picks", "underlay", "cloud", "place", "contact", "reply", "paste")),
    LAYOUT("layout", listOf("up", "down", "row", "delete", "colAdd", "colRemove", "lineAdd", "lineRemove", "joinLeft", "joinUp", "split")),
    STYLE("style", listOf("view", "props", "background", "color", "size", "align", "padding", "divider", "reset", "json")),
    LEVEL("level", emptyList()),
}

/**
 * «Ряд»: сколько строк (до 20) и столбцов (до 7) — числа кнопками «−» и «+» (заказчик 2026-10-18).
 * Ряд встаёт с равными столбцами.
 */
@Composable
private fun GridAsk(onInsert: (lines: Int, columns: Int) -> Unit) {
    // «Вставить» — один раз: второе нажатие до перерисовки вставило бы второй ряд (заказчик 2026-10-24).
    val once = io.tima.core.ui.rememberOnePress("editor:grid.ok")
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
            onClick = { once.press { onInsert(lines, columns) } },
            modifier = Modifier.testTag("editor:grid.ok"),
        )
    }
}

/** «Список»: «Развернуть по умолчанию», «Без отступа» — на весь список (ADR-0049 §3); нажата — включено. */
@Composable
internal fun ListAsk(list: io.tima.domain.chat.ComposedItem.ItemList, onChange: (open: Boolean, flat: Boolean) -> Unit) {
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
    "props" -> "⚙"
    "view" -> "🖼"
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
    model.refTitle = Tima.words.chat.refItem
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
            // «Текст → Уровень» (заказчик 2026-10-20): один уровень — выбирать нечего.
            "level" -> if (levels.size > 1) toggle("level")
            // «Стиль → Вид»: сообщение так, как его увидит получатель.
            "view" -> showPreview = !showPreview
            "bold" -> model.inline(InlineKind.BOLD)
            "italic" -> model.inline(InlineKind.ITALIC)
            "underline" -> model.inline(InlineKind.UNDERLINE)
            "strike" -> model.inline(InlineKind.STRIKE)
            "highlight" -> model.inline(InlineKind.HIGHLIGHT)
            // Одна кнопка «Код» (ADR-0049 §5): в строке — код в строке, целые строки — блок кода.
            "code" -> model.code()
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
            "colAdd" -> model.layout(io.tima.domain.chat.ComposedLayout::addColumn)
            "colRemove" -> model.layout(io.tima.domain.chat.ComposedLayout::removeColumn)
            "lineAdd" -> model.layout(io.tima.domain.chat.ComposedLayout::addLine)
            "lineRemove" -> model.layout(io.tima.domain.chat.ComposedLayout::removeLine)
            "joinLeft" -> if (!model.join("left")) hint = w.joinRefused
            "joinUp" -> if (!model.join("up")) hint = w.joinRefused
            "split" -> model.layout(io.tima.domain.chat.ComposedLayout::split)
            "up" -> model.shift(-1)
            "down" -> model.shift(1)
            // «Убрать» — ряд целиком (заказчик 2026-10-21); корзина в шапке удаляет выбранное, как была.
            "delete" -> model.removeRow()
            // Раздел «Стиль» (§10.2): на выбранное.
            // «Вставка → Виджет» (ПЛАН-(ВД), ПЛАН-(ВМ)): видео, метки, аудио, поток, опрос, мероприятие.
            "widget" -> toggle("widgets")
            // «Стиль → Свойства» (ADR-0050 §2): свойства выбранного блока.
            "props" -> toggle("style.props")
            "background", "color", "size", "align", "padding", "divider", "json" -> toggle("style.$key")
            "reset" -> model.styled { d, s -> io.tima.domain.chat.ComposedStyle.setStyle(d, s, emptyMap()) }
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
        // ── Шапка (заказчик 2026-10-20): ‹ · свойства блока · удалить · эмоджи · ↶ ↷ · ➤ ──
        // «‹» зелёная; «➤» зелёная без ошибок, красная с ошибками. Просмотр — «Стиль → Вид», уровень — «Текст».
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = TimaSpacing.about2),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(glyph = "‹", onClick = { onBack(model.doc) }, live = true, modifier = Modifier.testTag(EditorTags.BACK))
            HeaderToggle("⚙", panel == "style.props", EditorTags.HEAD_PROPS) { press("props") }
            HeaderGlyph("🗑", true, EditorTags.DELETE) { model.layout(io.tima.domain.chat.ComposedLayout::removeAt) }
            HeaderToggle("🙂", panel == "emoji", EditorTags.HEAD_EMOJI) { press("emoji") }
            HeaderGlyph("↶", model.canUndo, EditorTags.UNDO) { model.undo() }
            HeaderGlyph("↷", model.canRedo, EditorTags.REDO) { model.redo() }
            SendButton(ready = !doc.isEmpty, broken = trouble.isNotEmpty()) { onSend(model.doc) }
        }

        // ── Разделы и лента (§10.2) — закреплены вверху (заказчик 2026-10-19: «Закрепляем меню вверху
        // обратно»); прокручивается сообщение. Окно выбора не выше [PANEL_MAX] — дальше листается само.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = TimaSpacing.about2),
            horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about1),
        ) {
            for (t in EditorTab.entries) {
                io.tima.core.ui.Tab(
                    label = w.tab(t.key),
                    current = t == tab,
                    onClick = { tab = t; panel = null; hint = null },
                    modifier = Modifier.testTag(EditorTags.tab(t.key)),
                )
            }
        }
        // В «Стиле» — только то, что действует на выбранное (doc_mig/РЕДАКТОР-СВОЙСТВА-И-ВЛОЖЕНИЕ.md).
        val styleKeys = io.tima.domain.chat.ComposedStyle.applicable(doc, model.spot)
        if (tab == EditorTab.LEVEL) {
            LevelRibbon(levels, level) { picked -> model.level(picked) }
        } else {
            Ribbon(
                tab, active = if (showPreview) active + "view" else active, paragraph = w.paragraph(model.paragraphHere.name),
                scroll = ribbonScroll, onPress = ::press,
                hidden = if (tab == EditorTab.STYLE) (io.tima.domain.chat.ComposedStyle.VISUAL - styleKeys.toSet()).toSet() else emptySet(),
            )
        }
        if (panel != null && panel != "level" && panel != "emoji" && panel != "symbol") {
            Box(Modifier.fillMaxWidth().heightIn(max = PANEL_MAX).verticalScroll(rememberScrollState())) {
                when {
                    panel == "paragraph" -> ParagraphList(model.paragraphHere) { kind -> model.paragraph(kind); panel = null }
                    panel == "widgets" -> WidgetsAsk { key ->
                        panel = null
                        // Видео, метки, аудио и поток (ПЛАН-(ВМ) §3.6–§3.8) — формой ведущего; прочее — заглушки.
                        if (key !in MEDIA_WIDGETS || onButton?.invoke(key) != true) hint = w.widgetSoon
                    }
                    panel == "grid" -> GridAsk { lines, columns ->
                        panel = null
                        model.insertRow(lines, columns)
                    }
                    panel?.startsWith("style.") == true -> StylePanel(panel!!.removePrefix("style."), model, problems)
                }
            }
        }

        // Разделитель: что выбрано (§10.2) и подсказка последнего нажатия; правка своего — «Изменение · …».
        Row(
            Modifier.fillMaxWidth().background(colors.functional).padding(horizontal = TimaSpacing.about4, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val said = notice ?: hint ?: ((title?.let { "$it · " } ?: "") + if (showPreview) w.previewOn else w.selected(selectedKind(model)))
            Box(Modifier.weight(1f)) { Tertiary(said, lineOne = true) }
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
        // Над выделенным полоски Ж К Ч нет (заказчик 2026-10-18): она ложилась на системное «Вырезать ·
        // Копировать · Выделить всё». Оформляют кнопки ленты — нажата, если выделенное уже такое.

        // Выбран виджет, а клавиатура открыта — Enter и буквы ловит невидимое поле: новая строка под
        // виджетом (заказчик 2026-10-21). Прежде нажатое уходило в прежний текст, где стоял курсор.
        WidgetKeys(model)

        // ── Сообщение ──
        val drag = rememberEditorDrag()
        drag.forget(doc.items.size)
        BoxWithConstraints(
            Modifier.weight(1f).fillMaxWidth()
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
            val window = maxHeight
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState(), enabled = drag.from == null).padding(TimaSpacing.about3)) {
                if (showPreview) {
                    // Как в ленте (заказчик 2026-10-20): тот же показ; переходы — окном, но заблокированы.
                    androidx.compose.runtime.CompositionLocalProvider(LocalGateBlocked provides true) {
                        val shown = preview(doc)
                        if (shown != null) {
                            MessageView(shown, my = true, modifier = Modifier.testTag(EditorTags.PREVIEW))
                        } else {
                            Bubble(my = true, modifier = Modifier.testTag(EditorTags.PREVIEW)) { Caption(doc.firstLine, fontSize = TimaType.sz4) }
                        }
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

/** Окно выбора (Абзац, Ряд, Стиль…) не выше этого — дальше листается; сообщение остаётся видно. */
private val PANEL_MAX = 220.dp

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
        // Enter при выбранном виджете — новая строка под ним (заказчик 2026-10-21).
        !ctrl && (key == Key.Enter || key == Key.NumPadEnter) && model.widgetChosen -> { model.lineAfterSelected(); true }
        !ctrl -> false
        key == Key.B -> mark(InlineKind.BOLD)
        key == Key.I -> mark(InlineKind.ITALIC)
        key == Key.U -> mark(InlineKind.UNDERLINE)
        shift && key == Key.X -> mark(InlineKind.STRIKE)
        shift && key == Key.H -> mark(InlineKind.HIGHLIGHT)
        key == Key.E -> { model.code(); true }
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

/** Кнопка шапки, которая бывает нажата: фон, пока открыто её окно («Свойства», эмоджи). */
@Composable
private fun HeaderToggle(glyph: String, on: Boolean, tag: String, onClick: () -> Unit) {
    val colors = Tima.colors
    Box(
        Modifier.size(40.dp).background(if (on) colors.functional else colors.surface, CircleShape).clickable(onClick = onClick).testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Caption(glyph, fontSize = 20.sp)
    }
}

/** «Отправить»: зелёная — можно; красная — есть ошибки (их список под сообщением); пусто — серая. */
@Composable
private fun SendButton(ready: Boolean, broken: Boolean, onClick: () -> Unit) {
    val colors = Tima.colors
    // Один раз: второе нажатие до закрытия окна отправило бы то же сообщение ещё раз (заказчик 2026-10-24).
    val once = io.tima.core.ui.rememberOnePress(EditorTags.SEND)
    val fill = when {
        broken -> colors.alarm
        ready -> colors.navigation
        else -> colors.functional
    }
    Box(
        Modifier.size(40.dp).background(fill, CircleShape).clickable(enabled = ready && !broken) { once.press(onClick) }.testTag(EditorTags.SEND),
        contentAlignment = Alignment.Center,
    ) {
        Caption("➤", fontSize = 18.sp, weight = FontWeight.Bold, color = if (ready || broken) colors.onAccent else colors.text3)
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun WidgetKeys(model: EditorModel) {
    if (!model.widgetChosen) return
    val catcher = remember { FocusRequester() }
    // Клавиатура на экране — снизу её отступ; на ПК его нет, там Enter ловит [editorCommand].
    val ime = androidx.compose.foundation.layout.WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
    LaunchedEffect(model.spot) { if (ime) runCatching { catcher.requestFocus() } }
    BasicTextField(
        value = "",
        onValueChange = { v -> if (v.isNotEmpty()) model.lineAfterSelected(v.replace("\n", "")) },
        modifier = Modifier.size(1.dp).alpha(0f).focusRequester(catcher).testTag(EditorTags.WIDGET_KEYS),
    )
}

/**
 * Раздел «Уровень» (заказчик 2026-10-21): кнопка на уровень — цифра в кружке, под ней название; под
 * лентой — серое поле: что даёт выбранный уровень. Один уровень (правка своего) — выбирать нечего.
 */
@Composable
private fun LevelRibbon(levels: List<MessageCircle>, current: Int, onPick: (Int) -> Unit) {
    val colors = Tima.colors
    val shown = levels.ifEmpty { MessageCircle.entries.filter { it.level == current } }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
            for (c in shown) {
                val on = c.level == current
                Column(
                    Modifier.width(RIBBON_BUTTON * 1.4f).clickable(enabled = levels.size > 1) { onPick(c.level) }
                        .padding(vertical = 4.dp).testTag(EditorTags.level(c.level)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.size(30.dp).background(if (on) colors.navigation else colors.functional, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Caption(c.level.toString(), fontSize = 15.sp, weight = FontWeight.Black, color = if (on) colors.onAccent else colors.text)
                    }
                    Caption(c.title, fontSize = 10.sp, color = if (on) colors.text else colors.text2, lineOne = true)
                }
            }
        }
        shown.firstOrNull { it.level == current }?.let { c ->
            Column(
                Modifier.fillMaxWidth().background(colors.functional).padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2)
                    .testTag(EditorTags.LEVEL_ABOUT),
            ) {
                Caption(c.title, fontSize = TimaType.sz5, weight = FontWeight.Bold)
                Tertiary(c.about)
            }
        }
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
private fun Ribbon(
    tab: EditorTab,
    active: Set<String>,
    paragraph: String,
    scroll: androidx.compose.foundation.ScrollState,
    onPress: (String) -> Unit,
    hidden: Set<String> = emptySet(),
    level: Int = 0,
    levelOn: Boolean = false,
) {
    val colors = Tima.colors
    val w = Tima.words.editor
    val width = RIBBON_BUTTON
    Box(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(scroll).padding(vertical = 4.dp)) {
            for (key in tab.buttons.filter { it !in hidden }) {
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
                if (key == "level") {
                    // Уровень — цифрой в кружке (§10.1); один уровень — серый.
                    Column(
                        Modifier.width(width).clickable(enabled = levelOn) { onPress(key) }.padding(vertical = 4.dp).testTag(EditorTags.LEVEL),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier.size(30.dp).background(if (levelOn) colors.navigation else colors.functional, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Caption(level.toString(), fontSize = 15.sp, weight = FontWeight.Black, color = if (levelOn) colors.onAccent else colors.text3)
                        }
                        Caption(w.button(key), fontSize = 10.sp, color = colors.text2, lineOne = true)
                    }
                    continue
                }
                // Нажата: Ж, К… — оформление под курсором или ждёт буквы (Word).
                val on = key in active
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

/**
 * «Абзац ▾» в два ряда (заказчик 2026-10-20): «Абзац» · «Заголовок: 1 2 3»; «Цитата» · «Список: 1 2 3
 * уровень». Цифра — в серой заливке шириной в четыре знака: пальцу есть куда попасть. Не влезло
 * (крупный шрифт, узкий экран) — ряд листается: за край уходит его конец.
 */
@Composable
internal fun ParagraphList(current: ParagraphKind, onPick: (ParagraphKind) -> Unit) {
    val colors = Tima.colors
    val w = Tima.words.editor
    @Composable
    fun item(kind: ParagraphKind, label: String) = Caption(
        label,
        fontSize = TimaType.sz5,
        weight = if (kind == current) FontWeight.Black else FontWeight.Normal,
        color = if (kind == current) colors.navigation else colors.text,
        lineOne = true,
        modifier = Modifier.clickable { onPick(kind) }.padding(vertical = 6.dp, horizontal = 2.dp).testTag(EditorTags.paragraph(kind)),
    )
    // Ширина заливки — четыре цифры этим кеглем, с учётом крупного шрифта.
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val scale = io.tima.core.ui.LocalTextScale.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val pill = remember(scale, density) {
        with(density) {
            measurer.measure("0000", androidx.compose.ui.text.TextStyle(fontSize = TimaType.sz4 * scale, fontWeight = FontWeight.Black)).size.width.toDp()
        }
    }
    @Composable
    fun number(kind: ParagraphKind, digit: String) = Box(
        Modifier.width(pill).background(colors.functional, RoundedCornerShape(8.dp)).clickable { onPick(kind) }
            .padding(vertical = 4.dp).testTag(EditorTags.paragraph(kind)),
        contentAlignment = Alignment.Center,
    ) {
        Caption(
            digit,
            fontSize = TimaType.sz4,
            weight = if (kind == current) FontWeight.Black else FontWeight.Bold,
            color = if (kind == current) colors.navigation else colors.text,
            lineOne = true,
        )
    }
    @Composable
    fun label(text: String) = Caption(text, fontSize = TimaType.sz5, color = colors.text2, lineOne = true)
    @Composable
    fun row(content: @Composable () -> Unit) = Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = TimaSpacing.about4, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
    Column(Modifier.fillMaxWidth().background(colors.surface).padding(vertical = 4.dp)) {
        row {
            item(ParagraphKind.PARAGRAPH, w.paragraph(ParagraphKind.PARAGRAPH.name))
            Box(Modifier.width(TimaSpacing.about2))
            label(w.headingRow)
            number(ParagraphKind.HEADING1, "1")
            number(ParagraphKind.HEADING2, "2")
            number(ParagraphKind.HEADING3, "3")
        }
        row {
            item(ParagraphKind.QUOTE, w.paragraph(ParagraphKind.QUOTE.name))
            Box(Modifier.width(TimaSpacing.about2))
            label(w.listRow)
            number(ParagraphKind.LIST, "1")
            number(ParagraphKind.LIST2, "2")
            number(ParagraphKind.LIST3, "3")
            label(w.levelWord)
        }
    }
}

/** Виджеты медиа по ссылке (ПЛАН-(ВМ) §3.6–§3.8): вставляет форма ведущего редактор. */
private val MEDIA_WIDGETS = setOf("video", "marks", "audio", "stream")

/**
 * «Вставка → Виджет»: видео, метки, аудио, поток (ПЛАН-(ВМ)); опрос, мероприятие — пока заглушки (ПЛАН-(ВД)).
 * «Видеоклипы» убраны (заказчик 2026-10-24: «он не нужен»).
 */
@Composable
private fun WidgetsAsk(onPick: (String) -> Unit) {
    val once = io.tima.core.ui.rememberOnePress("editor:widgets")
    val colors = Tima.colors
    val w = Tima.words.editor
    Row(
        Modifier.fillMaxWidth().background(colors.surface).horizontalScroll(rememberScrollState())
            .padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2).testTag(EditorTags.WIDGETS),
        horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
    ) {
        val items = listOf(
            "video" to w.widgetVideo, "marks" to w.widgetMarks, "audio" to w.widgetAudio, "stream" to w.widgetStream,
            "poll" to w.widgetPoll, "event" to w.widgetEvent,
        )
        for ((key, label) in items) {
            io.tima.core.ui.Chip(label, kind = io.tima.core.ui.ChipKind.Neutral, onClick = { once.press { onPick(key) } }, modifier = Modifier.testTag("editor:widgets.$key"))
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
            val view = rememberTypedView(shown, marks, open = false, color = io.tima.core.ui.paletteColor(look.color))
            // Кусок со стилем, а в нём блоки разного вида (абзац, заголовок, цитата…) — у получателя это
            // разные плашки; здесь между ними черта (заказчик 2026-10-24: «разделитель появляется между
            // типами, если задан стиль»). Без стиля зазора у получателя не видно — и черты нет.
            val starts = if (item.style.isEmpty()) emptyList() else remember(shown) { io.tima.domain.chat.ComposedBlocks.starts(shown) }
            var laid by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
            BasicTextField(
                value = value,
                onValueChange = { v -> if (chosen) model.changed(index, v) else { model.focused(index); model.changed(index, v) } },
                keyboardOptions = io.tima.core.ui.TextKeyboard,
                textStyle = TextStyle(
                    fontSize = sizeOf(look.size, TimaType.sz4),
                    color = io.tima.core.ui.paletteColor(look.color) ?: colors.text,
                    textAlign = alignOf(look.align),
                ),
                cursorBrush = SolidColor(colors.navigation),
                visualTransformation = view,
                onTextLayout = { laid = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(vertical = 4.dp)
                    .styled(look)
                    .then(
                        if (starts.isEmpty()) {
                            Modifier
                        } else {
                            Modifier.drawWithContent {
                                drawContent()
                                val layout = laid ?: return@drawWithContent
                                val shownText = value.text
                                val map = view.filter(androidx.compose.ui.text.AnnotatedString(shownText)).offsetMapping
                                for (line in starts) {
                                    val at = shown.lineStart(line).coerceAtMost(shownText.length)
                                    val off = runCatching { map.originalToTransformed(at) }.getOrNull() ?: continue
                                    if (off > layout.layoutInput.text.length) continue
                                    val y = layout.getLineTop(layout.getLineForOffset(off))
                                    drawLine(colors.line, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), 1.dp.toPx())
                                }
                            }
                        },
                    )
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
