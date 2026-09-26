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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.tima.core.ui.Button
import io.tima.core.ui.ButtonKind
import io.tima.core.ui.CheckMark
import io.tima.core.ui.Field
import io.tima.core.ui.IconButton
import io.tima.core.ui.ListLine
import io.tima.core.ui.Name
import io.tima.core.ui.Secondary
import io.tima.core.ui.Tertiary
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.words
import io.tima.domain.chat.BookEntry
import io.tima.domain.chat.BookList
import io.tima.domain.chat.ChatPerson
import io.tima.domain.chat.Section

/**
 * Чем отбирать в журнале контактов — первая строка фильтров (ВЗ8).
 *
 * Четыре бывших подокна «Списков» переехали сюда: был подокном, стал фильтром. Сверх них —
 * «Со своей мелодией»: иначе, назначив мелодию десятку людей, их потом не найти.
 */
enum class LedgerList { All, Book, Tima, Removed, Blocked, OwnSound }

/**
 * Отбор журнала — чистое правило, отдельно от экрана.
 *
 * Фильтры **складываются**: «Работа» + «Заблокированные» — заблокированные из раздела
 * «Работа». [section] `null` — все разделы; `""` — «Общий».
 *
 * @param hasOwnSound есть ли у человека своя мелодия (ключ — в настройках устройства).
 */
fun ledgerRows(
    people: List<BookEntry>,
    list: LedgerList,
    section: String?,
    query: String,
    hasOwnSound: (BookEntry) -> Boolean,
): List<BookEntry> {
    val q = query.trim().lowercase()
    return people
        .filter { entry ->
            when (list) {
                LedgerList.All -> true
                LedgerList.Book -> BookRoster.Book.holds(entry)
                LedgerList.Tima -> BookRoster.Tima.holds(entry)
                LedgerList.Removed -> BookRoster.Removed.holds(entry)
                LedgerList.Blocked -> BookRoster.Blocked.holds(entry)
                LedgerList.OwnSound -> hasOwnSound(entry)
            }
        }
        .filter { section == null || it.sectionId == section }
        .filter { q.isEmpty() || (it.name?.lowercase()?.contains(q) == true) || it.phone.contains(q) }
        .sortedWith(compareBy({ it.name == null }, { it.name ?: it.phone }))
}

/**
 * Журнал контактов — ПЛАН-ВХОДЯЩЕГО-ЗВОНКА.md §8 (решения заказчика 2026-09-26).
 *
 * **Нажатие на строку — страница человека; выделение — квадратом справа.** Долгого нажатия
 * нет: его не видно, и о нём не догадаться.
 *
 * **Полоса действий внизу видна всегда**, как поле отправки в переписке. Пока не выделено
 * никого, она есть, но не действует: исчезающая полоса двигала бы список под пальцем.
 *
 * «Переименовать» здесь нет: имя правится на странице человека, а групповой смены имени не
 * бывает.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ContactLedgerPage(
    people: List<BookEntry>,
    sections: List<Section>,
    modifier: Modifier = Modifier,
    personOf: (BookEntry) -> ChatPerson = { ChatPerson(name = it.name, phone = it.phone) },
    /** Своя мелодия человека — имя для строки; `null` — своей нет, строки о ней нет. */
    soundTitleOf: (BookEntry) -> String? = { null },
    onOpenPerson: ((BookEntry) -> Unit)? = null,
    onList: (List<String>, BookList) -> Unit = { _, _ -> },
    onSection: (List<String>, String) -> Unit = { _, _ -> },
    /** Выбрать мелодию выделенным (панель выбора рисует тот, у кого выбор звука). */
    onSound: (List<BookEntry>) -> Unit = {},
) {
    val words = Tima.words.book
    var list by remember { mutableStateOf(LedgerList.All) }
    var section by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var pickingSection by remember { mutableStateOf(false) }
    val rows = ledgerRows(people, list, section, query) { soundTitleOf(it) != null }
    val chosen = people.filter { it.id in selected }
    val sectionName: (String) -> String = { id ->
        sections.firstOrNull { it.id == id }?.name ?: words.commonSection
    }

    Column(modifier.fillMaxWidth()) {
        // ── ДВА ФИЛЬТРА, ДВЕ СТРОКИ ──────────────────────────────────────────
        Column(
            Modifier.fillMaxWidth().padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2),
            verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
                verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
            ) {
                for (l in LedgerList.entries) {
                    Button(
                        label = when (l) {
                            LedgerList.All -> words.ledgerAll
                            LedgerList.Book -> words.listBook
                            LedgerList.Tima -> words.listTima
                            LedgerList.Removed -> words.listRemoved
                            LedgerList.Blocked -> words.listBlocked
                            LedgerList.OwnSound -> words.ledgerOwnSound
                        },
                        kind = if (list == l) ButtonKind.Action else ButtonKind.Quiet,
                        onClick = { list = l },
                    )
                }
            }
            // Разделы — те же, что во вкладке «Контакты»: там ими и группируют.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
                verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
            ) {
                Button(
                    label = words.ledgerAllSections,
                    kind = if (section == null) ButtonKind.Action else ButtonKind.Quiet,
                    onClick = { section = null },
                )
                Button(
                    label = words.commonSection,
                    kind = if (section == "") ButtonKind.Action else ButtonKind.Quiet,
                    onClick = { section = "" },
                )
                for (s in sections) {
                    Button(
                        label = s.name,
                        kind = if (section == s.id) ButtonKind.Action else ButtonKind.Quiet,
                        onClick = { section = s.id },
                    )
                }
            }
            Field(value = query, onChange = { query = it }, hint = words.ledgerSearch)
            Row(horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2)) {
                Button(
                    label = words.ledgerSelectAll,
                    kind = ButtonKind.Quiet,
                    // «Все» — видимые под фильтром: выделить скрытых значило бы действовать
                    // над теми, кого человек не видит.
                    onClick = { selected = selected + rows.map { it.id } },
                )
                Button(label = words.ledgerSelectNone, kind = ButtonKind.Quiet, onClick = { selected = emptySet() })
            }
        }

        LazyColumn(Modifier.weight(1f, fill = false)) {
            if (rows.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(TimaSpacing.about5), contentAlignment = Alignment.Center) {
                        Tertiary(words.listEmpty, lineOne = true)
                    }
                }
            }
            items(rows, key = { it.id }) { entry ->
                val person = personOf(entry)
                val on = entry.id in selected
                ListLine(
                    onClick = onOpenPerson?.let { open -> { open(entry) } },
                    middle = {
                        Column {
                            Name(person.name ?: entry.name ?: entry.phone)
                            // Раздел · список · ♪ мелодия — мелодия, только если своя.
                            val listName = when {
                                BookRoster.Blocked.holds(entry) -> words.listBlocked
                                BookRoster.Removed.holds(entry) -> words.listRemoved
                                BookRoster.Tima.holds(entry) -> words.listTima
                                else -> words.listBook
                            }
                            val line = listOfNotNull(
                                sectionName(entry.sectionId),
                                listName,
                                soundTitleOf(entry)?.let { "♪ $it" },
                            ).joinToString(" · ")
                            Tertiary(line, lineOne = true)
                        }
                    },
                    right = {
                        Box(
                            Modifier.clickable { selected = if (on) selected - entry.id else selected + entry.id }
                                .padding(TimaSpacing.about2),
                        ) { CheckMark(on) }
                    },
                )
            }
        }

        // ── ПОЛОСА ДЕЙСТВИЙ — ВИДНА ВСЕГДА ─────────────────────────────────────
        Column(
            Modifier.fillMaxWidth().background(Tima.colors.functional)
                .padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2),
            verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
        ) {
            if (pickingSection && chosen.isNotEmpty()) {
                Secondary(words.ledgerPickSection)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
                    verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
                ) {
                    val targets = listOf(Section(id = "", name = words.commonSection)) + sections
                    for (s in targets) {
                        Button(label = s.name, kind = ButtonKind.Quiet, onClick = {
                            onSection(chosen.map { it.id }, s.id)
                            pickingSection = false
                        })
                    }
                }
            }
            Tertiary(words.ledgerSelected(chosen.size), lineOne = true)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val active = chosen.isNotEmpty()
                LedgerAction("📁", words.ledgerToSection, active) { pickingSection = !pickingSection }
                LedgerAction("➖", words.ledgerRemove, active) { onList(chosen.map { it.id }, BookList.Removed) }
                LedgerAction("⛔", words.ledgerBlock, active) { onList(chosen.map { it.id }, BookList.Blocked) }
                LedgerAction("↩", words.ledgerRestore, active) { onList(chosen.map { it.id }, BookList.Usual) }
                LedgerAction("♪", words.ledgerSound, active) { onSound(chosen) }
            }
        }
    }
}

/** Значок действия с подписью: пока никого не выделено — виден, но не действует. */
@Composable
private fun LedgerAction(glyph: String, label: String, active: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(glyph = glyph, onClick = { if (active) onClick() }, live = active)
        Tertiary(label, lineOne = true)
    }
}
