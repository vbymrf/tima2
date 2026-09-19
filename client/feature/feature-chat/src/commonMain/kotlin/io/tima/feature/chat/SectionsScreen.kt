package io.tima.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.tima.core.ui.Button
import io.tima.core.ui.ButtonKind
import io.tima.core.ui.Caption
import io.tima.core.ui.EmptyArea
import io.tima.core.ui.Field
import io.tima.core.ui.IconButton
import io.tima.core.ui.ListLine
import io.tima.core.ui.Name
import io.tima.core.ui.SectionGlyph
import io.tima.core.ui.Secondary
import io.tima.core.ui.SubwindowHeader
import io.tima.core.ui.Tertiary
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaShapes
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.words
import io.tima.domain.chat.Section
import io.tima.domain.chat.SectionIcon

/**
 * Управление набором разделов — пункт «Разделы» кнопки «Вид» (ПЛАН-РАЗДЕЛОВ Р2).
 *
 * **Список и правка на одном экране.** Раздел — три поля и порядок; уводить за ними на
 * второй экран значит терять из виду список, относительно которого их и правят. Поэтому
 * строка раздела разворачивается в редактор на месте, а «Добавить» — та же карточка
 * снизу.
 *
 * **Порядок — стрелками, а не перетаскиванием.** Перетаскивание в списке, который сам
 * прокручивается, на телефоне промахивается; стрелка — нет.
 *
 * **«Общий» здесь не показывается.** Он не раздел, а отсутствие раздела: не
 * переименовывается, не убирается, стоит последним всегда (`разделы.md`, «Правила»).
 */
@Composable
fun SectionsScreen(
    sections: List<Section>,
    /** Сколько людей в разделе — по идентификатору. */
    countOf: (String) -> Int,
    onAdd: (name: String, icon: Int) -> Unit,
    onRename: (id: String, name: String, icon: Int) -> Unit,
    onMove: (id: String, up: Boolean) -> Unit,
    onRemove: (id: String) -> Unit,
    onBack: () -> Unit,
    /** Имя и значок «Общего», если их меняли; `null` — как в словаре. */
    common: Section? = null,
    /** Сменить имя и значок «Общего». Убрать его нельзя — кнопки у него нет. */
    onRenameCommon: (name: String, icon: Int) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val colors = Tima.colors
    val words = Tima.words.book
    var editing by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().background(colors.surface)) {
        SubwindowHeader(title = words.sectionsScreen, onBack = onBack)

        // Список пустым не бывает: «Общий» есть всегда. Пояснение «разделов пока нет»
        // стоит НАД ним отдельной строкой, а не вместо списка (заказчик 2026-09-19).
        LazyColumn(Modifier.weight(1f)) {
            if (sections.isEmpty() && !adding) {
                item(key = "пусто") {
                    Box(Modifier.padding(TimaSpacing.about4)) {
                        EmptyArea(title = words.sectionsEmpty, explanation = words.sectionsEmptyAbout)
                    }
                }
            }
            run {
                items(sections, key = { it.id }) { section ->
                    if (editing == section.id) {
                        SectionEditor(
                            initialName = section.name,
                            initialIcon = section.icon,
                            onSave = { name, icon ->
                                onRename(section.id, name, icon)
                                editing = null
                            },
                            onCancel = { editing = null },
                            onRemove = {
                                onRemove(section.id)
                                editing = null
                            },
                        )
                    } else {
                        val first = sections.first().id == section.id
                        val last = sections.last().id == section.id
                        ListLine(
                            onClick = {
                                adding = false
                                editing = section.id
                            },
                            left = { SectionGlyph(index = section.icon, size = 24.dp) },
                            middle = {
                                Column {
                                    Name(section.name)
                                    Tertiary(words.peopleInSection(countOf(section.id)), lineOne = true)
                                }
                            },
                            right = {
                                Row(horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about1)) {
                                    // Крайние стрелки не рисуются, а не гаснут: погашенная
                                    // кнопка спрашивает «почему», отсутствующая — нет.
                                    if (!first) IconButton(glyph = "↑", onClick = { onMove(section.id, true) })
                                    if (!last) IconButton(glyph = "↓", onClick = { onMove(section.id, false) })
                                }
                            },
                        )
                    }
                }
            }
            // «Общий» — последней строкой и всегда: имя и значок правятся, убрать нельзя,
            // переставить некуда (решение заказчика 2026-09-19).
            item(key = COMMON_SECTION) {
                if (editing == COMMON_SECTION) {
                    SectionEditor(
                        initialName = common?.name ?: words.commonSection,
                        initialIcon = common?.icon ?: 0,
                        onSave = { name, icon ->
                            onRenameCommon(name, icon)
                            editing = null
                        },
                        onCancel = { editing = null },
                        onRemove = null,
                    )
                } else {
                    ListLine(
                        onClick = {
                            adding = false
                            editing = COMMON_SECTION
                        },
                        left = { SectionGlyph(index = common?.icon ?: 0, size = 24.dp) },
                        middle = {
                            Column {
                                Name(common?.name ?: words.commonSection)
                                Tertiary(
                                    words.peopleInSection(countOf(COMMON_SECTION)) + " · " + words.commonSectionAbout,
                                    lineOne = true,
                                )
                            }
                        },
                    )
                }
            }
        }

        if (adding) {
            SectionEditor(
                initialName = "",
                initialIcon = SectionIcon.NONE.index,
                onSave = { name, icon ->
                    onAdd(name, icon)
                    adding = false
                },
                onCancel = { adding = false },
                onRemove = null,
            )
        } else {
            Box(Modifier.fillMaxWidth().padding(TimaSpacing.about4)) {
                Button(
                    label = words.addSection,
                    onClick = {
                        editing = null
                        adding = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * Карточка правки раздела: имя, значок из набора, сохранить, убрать.
 *
 * Значки — плиткой, все двенадцать разом: выбирать из того, что видно целиком, а не
 * листать. «Без значка» — первая клетка, пустая: это законный выбор, а не отсутствие
 * выбора.
 */
@Composable
private fun SectionEditor(
    initialName: String,
    initialIcon: Int,
    onSave: (String, Int) -> Unit,
    onCancel: () -> Unit,
    onRemove: (() -> Unit)?,
) {
    val colors = Tima.colors
    val words = Tima.words.book
    var name by remember(initialName) { mutableStateOf(initialName) }
    var icon by remember(initialIcon) { mutableIntStateOf(initialIcon) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.functional)
            .padding(TimaSpacing.about4),
        verticalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
    ) {
        Caption(words.newSectionName, fontSize = TimaType.sz5, weight = FontWeight.Bold)
        Field(value = name, onChange = { name = it }, hint = Tima.words.chat.sectionExample)

        Caption(words.sectionIcon, fontSize = TimaType.sz5, weight = FontWeight.Bold)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
            verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
        ) {
            IconCell(index = SectionIcon.NONE.index, chosen = icon == SectionIcon.NONE.index) { icon = it }
            for (choice in SectionIcon.choices) {
                IconCell(index = choice.index, chosen = icon == choice.index) { icon = it }
            }
        }
        Secondary(SectionIcon.byIndex(icon)?.takeIf { it != SectionIcon.NONE }?.title ?: words.noIcon)

        Row(horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2)) {
            Button(
                label = words.save,
                onClick = { if (name.isNotBlank()) onSave(name.trim(), icon) },
                kind = if (name.isNotBlank()) ButtonKind.Action else ButtonKind.Quiet,
            )
            Button(label = Tima.words.common.cancel, kind = ButtonKind.Quiet, onClick = onCancel)
        }
        if (onRemove != null) {
            // Отдельной строкой и ниже: убрать — не то же, что отменить правку, и стоять
            // рядом с «Сохранить» ему нельзя — промах стоит раздела.
            ListLine(
                onClick = onRemove,
                middle = {
                    Column {
                        Name(words.removeSection)
                        Tertiary(words.removeSectionAbout, lineOne = true)
                    }
                },
            )
        }
    }
}

/** Клетка выбора значка: квадрат со скруглением, выбранная — салатовая. */
@Composable
private fun IconCell(index: Int, chosen: Boolean, onPick: (Int) -> Unit) {
    val colors = Tima.colors
    Box(
        modifier = Modifier
            .size(44.dp)
            .background(
                if (chosen) colors.navigation else colors.quiet,
                RoundedCornerShape(TimaShapes.smallSquare),
            )
            .clickable { onPick(index) },
        contentAlignment = Alignment.Center,
    ) {
        SectionGlyph(index = index, size = 24.dp, color = if (chosen) colors.onAccent else colors.text)
    }
}
