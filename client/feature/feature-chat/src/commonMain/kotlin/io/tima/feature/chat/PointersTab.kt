package io.tima.feature.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import io.tima.core.ui.IconButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.tima.core.ui.Avatar
import io.tima.core.ui.Button
import io.tima.core.ui.ButtonKind
import io.tima.core.ui.Caption
import io.tima.core.ui.Chip
import io.tima.core.ui.ChipKind
import io.tima.core.ui.ConfirmPlate
import io.tima.core.ui.Counter
import io.tima.core.ui.EmptyArea
import io.tima.core.ui.Field
import io.tima.core.ui.ListLine
import io.tima.core.ui.Name
import io.tima.core.ui.SubwindowHeader
import io.tima.core.ui.Tertiary
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.words
import io.tima.domain.chat.ChatLine
import io.tima.domain.chat.FoundMessage
import io.tima.domain.chat.MessageDisplay
import io.tima.domain.chat.Pointer
import io.tima.domain.chat.Section

/** Метки вкладки «Указатели» (СЦЕНАРИИ-ПО-ОКНАМ.md). */
object PointerTags {
    const val TAB = "pointers:list"
    const val ENTITY = "pointers:entity"
    const val TO_SECTION = "pointers:to-section"
    const val REMOVE = "pointers:remove"
    const val CHOSEN = "pointers:chosen"
    const val CHOSEN_CLOSE = "pointers:chosen.close"
    const val CHOSEN_CANCEL = "pointers:chosen.cancel"
    const val PREVIEW = "pointers:preview"
    const val PREVIEW_CLOSE = "pointers:preview.close"
    const val REPEAT = "pointers:back"
}

/**
 * Сущность первого уровня вкладки «Указатели» (ПЛАН-(УК) УК3): сообщество, группа, канал или личная
 * переписка, у которых есть указатель. [level] — ключ уровня подборки, [caption] — вторая строка, что
 * это: «чат», «группа открытая» и т. п.
 */
data class PointerEntity(
    val level: String,
    val title: String,
    val caption: String,
    val face: String? = null,
    val pointers: List<Pointer>,
)

/** Раздел указателя: пустой — «Общий». */
fun Pointer.sectionKey(): String = sectionId.ifEmpty { COMMON_SECTION }

/** Что видно в разделе [section] (пусто — «Все»): сущности с указателями этого раздела и только они. */
fun List<PointerEntity>.inSection(section: String): List<PointerEntity> =
    if (section.isEmpty()) this
    else mapNotNull { e -> e.pointers.filter { it.sectionKey() == section }.takeIf { it.isNotEmpty() }?.let { e.copy(pointers = it) } }

/** Ключ указателя — для ленты и выделения. */
fun Pointer.key(): String = "ptr:$level:$chatId:$serverId"

/**
 * Указатель — строкой ленты: найденное сообщение или заглушка «загружается», «недоступно». [found] —
 * то, что сказал поиск по номеру; `null` — ещё не спрашивали.
 */
fun Pointer.asLine(found: FoundMessage?, loading: String, unavailable: String): ChatLine {
    val line = (found as? FoundMessage.Found)?.line
    return line?.copy(dedupKey = key())
        ?: ChatLine(
            dedupKey = key(),
            chatId = chatId,
            display = MessageDisplay.SYSTEM,
            text = if (found == null || found is FoundMessage.Offline) loading else unavailable,
            outgoing = false,
            atMs = atMs,
            localId = 0,
            serverId = serverId,
        )
}

/**
 * Вкладка «Указатели» окна «Общение» (ПЛАН-(УК) УК3–УК4): разделы и «Вид» — в рядах окна, как у «Телефона»
 * и «Социума»; сущности — строкой как в «Социуме», второй строкой — что это; зашли — сами сообщения,
 * пузырём ленты: нажали — переход, нажали рядом или долго — выделение. [onRepeat] — кнопка «назад»
 * вкладки: повторить последний переход по указателю; `null` — переходов ещё не было. Кнопка стоит над
 * подокном выделения, а не поверх него (заказчик 2026-10-21).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PointersTab(
    entities: List<PointerEntity>,
    sections: List<Section>,
    common: Section?,
    section: String,
    opened: String?,
    onOpenEntity: (String?) -> Unit,
    lineOf: (Pointer) -> ChatLine,
    authorOf: (ChatLine) -> String?,
    onJump: (Pointer) -> Unit,
    onRemove: (List<Pointer>) -> Unit,
    onRemoveEntity: (String) -> Unit,
    onMove: (List<Pointer>, String) -> Unit,
    onRepeat: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val words = Tima.words.book
    val colors = Tima.colors
    val shown = entities.inSection(section)
    val entity = shown.firstOrNull { it.level == opened } ?: entities.firstOrNull { it.level == opened }?.takeIf { section.isEmpty() }
    var chosen by remember(entity?.level) { mutableStateOf(emptySet<String>()) }
    val byKey = entity?.pointers.orEmpty().associateBy { it.key() }
    Column(modifier.fillMaxSize().background(colors.surface).testTag(PointerTags.TAB)) {
        if (entity != null) SubwindowHeader(title = entity.title, onBack = { onOpenEntity(null) }, caption = entity.caption)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (entity == null) {
                if (shown.isEmpty()) {
                    Box(Modifier.padding(TimaSpacing.about4)) { EmptyArea(title = words.pointersEmpty, explanation = words.pointersEmptyAbout) }
                }
                var menuFor by remember { mutableStateOf<String?>(null) }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(shown, key = { it.level }) { e ->
                        Column {
                            ListLine(
                                modifier = Modifier
                                    .combinedClickable(onClick = { onOpenEntity(e.level) }, onLongClick = { menuFor = e.level })
                                    .testTag(PointerTags.ENTITY),
                                left = { Avatar(letters = e.title.take(2).uppercase(), picture = e.face) },
                                middle = {
                                    Column {
                                        Name(e.title)
                                        Tertiary(e.caption, lineOne = true)
                                    }
                                },
                                // Число указателей — зелёным (заказчик 2026-10-21): это не новое, а собранное.
                                right = { Counter(e.pointers.size, green = true) },
                            )
                            if (menuFor == e.level) {
                                EntityMenu(
                                    entity = e,
                                    sections = orderedSections(sections, common, words.commonSection),
                                    onMove = { to -> onMove(e.pointers, to); menuFor = null },
                                    onRemove = { onRemoveEntity(e.level); menuFor = null },
                                    onClose = { menuFor = null },
                                )
                            }
                        }
                    }
                }
            } else {
                PointerFeed(
                    lines = entity.pointers.sortedByDescending { it.atMs }.map(lineOf),
                    authorOf = authorOf,
                    chosen = chosen,
                    onOpen = { line -> byKey[line.dedupKey]?.let(onJump) },
                    onToggle = { line -> chosen = if (line.dedupKey in chosen) chosen - line.dedupKey else chosen + line.dedupKey },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // Возврат из переписки приводит в ту же сущность — кнопка видна и в ней; над подокном выделения.
            if (onRepeat != null) {
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(TimaSpacing.about3).size(42.dp)
                        .background(colors.surface, CircleShape)
                        .background(colors.functional.copy(alpha = 0.6f), CircleShape)
                        .combinedClickable(onClick = onRepeat)
                        .testTag(PointerTags.REPEAT),
                    contentAlignment = Alignment.Center,
                ) {
                    Caption("↩", fontSize = 20.sp, color = colors.text)
                }
            }
        }
        if (entity != null && chosen.isNotEmpty()) {
            val picked = chosen.mapNotNull { byKey[it] }
            ChosenSheet(
                count = picked.size,
                sections = orderedSections(sections, common, words.commonSection),
                onMove = { to -> onMove(picked, to); chosen = emptySet() },
                onRemove = { onRemove(picked); chosen = emptySet() },
                onCancel = { chosen = emptySet() },
            )
        }
    }
}

/** Долгое нажатие на сущность: в раздел — все её указатели; убрать — все, с подтверждением. */
@Composable
private fun EntityMenu(
    entity: PointerEntity,
    sections: List<Section>,
    onMove: (String) -> Unit,
    onRemove: () -> Unit,
    onClose: () -> Unit,
) {
    val words = Tima.words.book
    var asking by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(Tima.colors.functional).padding(TimaSpacing.about3), verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2)) {
        when {
            asking -> ConfirmPlate(
                title = words.pointerRemoveEntityAsk(entity.title),
                text = words.pointerRemoveEntityAbout,
                cancel = Tima.words.common.cancel,
                action = words.pointerRemove,
                danger = true,
                onCancel = { asking = false },
                onAction = onRemove,
            )
            moving -> SectionChoice(sections, onMove)
            else -> Row(horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2)) {
                Button(words.pointerToSection, onClick = { moving = true }, kind = ButtonKind.Quiet, modifier = Modifier.testTag(PointerTags.TO_SECTION))
                Button(words.pointerRemove, onClick = { asking = true }, kind = ButtonKind.Dangerous, modifier = Modifier.testTag(PointerTags.REMOVE))
                Button(Tima.words.common.cancel, onClick = onClose, kind = ButtonKind.Quiet)
            }
        }
    }
}

/**
 * Подокно выделенных (заказчик 2026-10-21): снизу, своей панелью — сколько выделено и ✕ справа сверху;
 * «В раздел», «Убрать», «Отмена».
 */
@Composable
private fun ChosenSheet(count: Int, sections: List<Section>, onMove: (String) -> Unit, onRemove: () -> Unit, onCancel: () -> Unit) {
    val words = Tima.words.book
    val colors = Tima.colors
    var moving by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth()
            .background(colors.line, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .padding(top = 1.dp)
            .background(colors.functional, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .padding(horizontal = TimaSpacing.about3, vertical = TimaSpacing.about2)
            .testTag(PointerTags.CHOSEN),
        verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Name(words.pointerChosen(count), Modifier.weight(1f))
            IconButton(glyph = "✕", onClick = onCancel, modifier = Modifier.testTag(PointerTags.CHOSEN_CLOSE))
        }
        if (moving) {
            SectionChoice(sections) { onMove(it); moving = false }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2)) {
                Button(words.pointerToSection, onClick = { moving = true }, kind = ButtonKind.Quiet, modifier = Modifier.testTag(PointerTags.TO_SECTION))
                Button(words.pointerRemove, onClick = onRemove, kind = ButtonKind.Dangerous, modifier = Modifier.testTag(PointerTags.REMOVE))
                Button(Tima.words.common.cancel, onClick = onCancel, kind = ButtonKind.Quiet, modifier = Modifier.testTag(PointerTags.CHOSEN_CANCEL))
            }
        }
    }
}

/**
 * Сообщение указателя — подокном поверх журнала (заказчик 2026-10-21): пузырь ленты, ✕ справа сверху,
 * нажатие вне — закрыть; нажатие по сообщению — переход.
 */
@Composable
private fun PointerPreview(line: ChatLine, authorOf: (ChatLine) -> String?, onOpen: () -> Unit, onClose: () -> Unit) {
    val colors = Tima.colors
    Box(
        Modifier.fillMaxSize().background(colors.text.copy(alpha = 0.45f)).clickable(onClick = onClose),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.fillMaxWidth()
                .background(colors.surface, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .clickable(enabled = false, onClick = {})
                .padding(bottom = TimaSpacing.about3)
                .testTag(PointerTags.PREVIEW),
        ) {
            Row(Modifier.fillMaxWidth().padding(start = TimaSpacing.about4, end = TimaSpacing.about2, top = TimaSpacing.about2), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f))
                IconButton(glyph = "✕", onClick = onClose, modifier = Modifier.testTag(PointerTags.PREVIEW_CLOSE))
            }
            PointerFeed(
                lines = listOf(line),
                authorOf = authorOf,
                chosen = emptySet(),
                onOpen = { onOpen() },
                onToggle = {},
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                wrap = true,
            )
        }
    }
}

/** Куда положить: разделы кнопками, «Общий» — пустым ключом. */
@Composable
internal fun SectionChoice(sections: List<Section>, onPick: (String) -> Unit) {
    Tertiary(Tima.words.book.pointerWhereTo, lineOne = true)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2)) {
        for (s in sections) {
            Chip(s.name, kind = ChipKind.Neutral, onClick = { onPick(if (s.id == COMMON_SECTION) "" else s.id) })
        }
    }
}

/**
 * Журнал указателей (ПЛАН-(УК) УК5) — как журнал контактов: фильтры по разделу и виду сущности, поиск
 * по тексту, галочки; «В раздел», «Убрать», «Перейти» (одному выделенному).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PointersLedgerPage(
    entities: List<PointerEntity>,
    sections: List<Section>,
    common: Section?,
    lineOf: (Pointer) -> ChatLine,
    onMove: (List<Pointer>, String) -> Unit,
    onRemove: (List<Pointer>) -> Unit,
    onJump: (Pointer) -> Unit,
    modifier: Modifier = Modifier,
    authorOf: (ChatLine) -> String? = { null },
) {
    val words = Tima.words.book
    var section by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf(emptySet<String>()) }
    var moving by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<Pointer?>(null) }
    val kinds = listOf("" to words.pointerAllKinds, "personal:" to words.pointerKindsChats, "group:" to words.pointerKindsGroups, "community:" to words.pointerKindsCommunities)
    val rows = entities.inSection(section)
        .filter { kind.isEmpty() || it.level.startsWith(kind) }
        .flatMap { e -> e.pointers.map { e to it } }
        .map { (e, p) -> Triple(e, p, lineOf(p)) }
        .filter { (_, _, line) -> query.isBlank() || line.text.orEmpty().contains(query.trim(), ignoreCase = true) }
        .sortedByDescending { it.second.atMs }
    val sectionNames = orderedSections(sections, common, words.commonSection).associate { it.id to it.name }
    val toggle: (Pointer) -> Unit = { p -> chosen = if (p.key() in chosen) chosen - p.key() else chosen + p.key() }
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            SectionsRow(tabs = sectionTabs(sections, common, words), chosen = section, icons = false, onPick = { section = it })
            // Вид сущности — своим рядом с зазорами сверху и снизу (заказчик 2026-10-21: «вплотную»).
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = TimaSpacing.about3, vertical = TimaSpacing.about3),
                horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
            ) {
                for ((key, label) in kinds) Chip(label, kind = if (kind == key) ChipKind.Selected else ChipKind.Quiet, onClick = { kind = key })
            }
            Box(Modifier.padding(start = TimaSpacing.about3, end = TimaSpacing.about3, bottom = TimaSpacing.about3)) {
                Field(value = query, onChange = { query = it }, hint = words.pointerSearch)
            }
            LazyColumn(Modifier.weight(1f)) {
                items(rows, key = { it.second.key() }) { (e, p, line) ->
                    val on = p.key() in chosen
                    ListLine(
                        // Нажатие — сообщение подокном; долгое и галочка — выделение.
                        modifier = Modifier.combinedClickable(onClick = { preview = p }, onLongClick = { toggle(p) }),
                        middle = {
                            Column {
                                Caption(line.text.orEmpty(), maxLines = 2)
                                Tertiary("${sectionNames[p.sectionKey()].orEmpty()} · ${e.title}", lineOne = true)
                            }
                        },
                        right = { SelectMark(on) { toggle(p) } },
                    )
                }
            }
            Column(Modifier.fillMaxWidth().background(Tima.colors.functional).padding(TimaSpacing.about3), verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2)) {
                val picked = rows.map { it.second }.filter { it.key() in chosen }
                Tertiary(words.pointerChosen(picked.size), lineOne = true)
                if (moving) {
                    SectionChoice(orderedSections(sections, common, words.commonSection)) { to -> onMove(picked, to); moving = false; chosen = emptySet() }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2)) {
                        Button(words.pointerToSection, onClick = { if (picked.isNotEmpty()) moving = true }, kind = ButtonKind.Quiet, modifier = Modifier.testTag(PointerTags.TO_SECTION))
                        Button(words.pointerRemove, onClick = { if (picked.isNotEmpty()) { onRemove(picked); chosen = emptySet() } }, kind = ButtonKind.Dangerous, modifier = Modifier.testTag(PointerTags.REMOVE))
                        if (picked.isNotEmpty()) Button(Tima.words.common.cancel, onClick = { chosen = emptySet() }, kind = ButtonKind.Quiet, modifier = Modifier.testTag(PointerTags.CHOSEN_CANCEL))
                    }
                }
            }
        }
        preview?.let { p ->
            PointerPreview(lineOf(p), authorOf, onOpen = { preview = null; onJump(p) }, onClose = { preview = null })
        }
    }
}
