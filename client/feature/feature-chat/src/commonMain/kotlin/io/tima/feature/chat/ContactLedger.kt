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
import io.tima.core.ui.Avatar
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
import io.tima.domain.chat.letter
import io.tima.domain.chat.line

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
    /**
     * «Вид» книги: разделы значками («Меню») или словами («Имена») и чем называть человека —
     * журнал показывает их так же, как вкладка «Контакты» (заказчик 2026-10-01).
     */
    view: BookView = BookView(),
    /** Аватар человека — тот же, что во вкладке «Контакты»; `null` — буква. */
    faceOf: (BookEntry) -> androidx.compose.ui.graphics.ImageBitmap? = { null },
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
        // ── ЕДИНЫЙ СТИЛЬ С «КОНТАКТАМИ» (заказчик 2026-10-01) ──────────────────
        //
        // Было: шесть списков и все разделы — крупными кнопками в два ряда, полэкрана до
        // первого человека. Теперь разделы — та же полоса, что во вкладке «Контакты», и в
        // том же исполнении («Меню» — значками, «Имена» — словами); списки — такой же
        // полосой словами. Обе тянутся пальцем, не переносятся.
        // Первая вкладка — «Все разделы», а не «Все»: под ней полоса списков со своей «Все
        // списки», и два одинаковых «Все» подряд путались.
        val sectionTabs = sectionTabs(sections, null, words).let { t ->
            listOf(t.first().copy(name = words.ledgerAllSections)) + t.drop(1)
        }
        SectionsRow(
            tabs = sectionTabs,
            chosen = when (section) {
                null -> ""
                "" -> COMMON_SECTION
                else -> section!!
            },
            icons = view.icons,
            onPick = { id ->
                section = when (id) {
                    "" -> null
                    COMMON_SECTION -> ""
                    else -> id
                }
            },
        )
        SectionsRow(
            tabs = LedgerList.entries.map { l ->
                SectionTab(
                    l.name,
                    when (l) {
                        LedgerList.All -> words.ledgerAll
                        LedgerList.Book -> words.listBook
                        LedgerList.Tima -> words.listTima
                        LedgerList.Removed -> words.listRemoved
                        LedgerList.Blocked -> words.listBlocked
                        LedgerList.OwnSound -> words.ledgerOwnSound
                    },
                    0,
                )
            },
            chosen = list.name,
            icons = false,
            onPick = { id -> list = LedgerList.valueOf(id) },
        )
        Column(
            Modifier.fillMaxWidth().padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2),
            verticalArrangement = Arrangement.spacedBy(TimaSpacing.about1),
        ) {
            Field(value = query, onChange = { query = it }, hint = words.ledgerSearch)
            // «Выделить все» и «Сбросить» — тихими надписями, а не кнопками: это служебное,
            // и крупные кнопки спорили со списком за внимание.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Tertiary(words.ledgerSelected(chosen.size), lineOne = true)
                Row(horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about3)) {
                    // «Все» — видимые под фильтром: выделить скрытых значило бы действовать
                    // над теми, кого человек не видит.
                    LedgerLink(words.ledgerSelectAll) { selected = selected + rows.map { it.id } }
                    LedgerLink(words.ledgerSelectNone) { selected = emptySet() }
                }
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
                    // Аватар и имя — как во вкладке «Контакты»: человек узнаёт «своего» Сашу.
                    left = { Avatar(letters = person.letter(), image = faceOf(entry)) },
                    middle = {
                        Column {
                            Name(person.line(view.look(), PERSON_FIRST_LINE) ?: entry.name ?: entry.phone)
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

/** Служебное действие журнала тихой надписью цветом акцента. */
@Composable
private fun LedgerLink(label: String, onClick: () -> Unit) {
    io.tima.core.ui.Caption(
        label,
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = TimaSpacing.about1),
        fontSize = io.tima.core.ui.TimaType.sz5,
        weight = androidx.compose.ui.text.font.FontWeight.Bold,
        color = Tima.colors.navigation,
        lineOne = true,
    )
}
