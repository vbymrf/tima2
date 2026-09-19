package io.tima.shared

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.tima.core.ui.Tima
import io.tima.core.ui.words
import io.tima.core.ui.IconButton
import io.tima.core.ui.ListLine
import io.tima.core.ui.Name
import io.tima.domain.chat.GroupInfo
import io.tima.domain.chat.Section
import io.tima.feature.chat.ALL_SECTION
import io.tima.feature.chat.BookView
import io.tima.feature.chat.COMMON_SECTION
import io.tima.feature.chat.SectionHeader
import io.tima.feature.chat.SectionTab
import io.tima.feature.chat.SectionsTiles
import io.tima.feature.chat.sectionTabs

/**
 * Разделы в «Каталоге» Социума — набор сообществ, четыре исполнения «Вида».
 *
 * Собрано здесь, а не в `feature-group`: плитка, гармошка и полоса живут в `feature-chat`,
 * а строка группы — в `feature-group`, и знакомить эти два модуля друг с другом ради одного
 * экрана не стоит. `shared` и так видит оба.
 *
 * Те же правила, что у книги (`разделы.md`):
 * - **А** плитка ярлычков, пока раздел не выбран; внутри — «назад» и список одного раздела;
 * - **Б** гармошка: все заведённые разделы, включая пустые, сворачиваются на месте;
 * - **В/Г** полоса над списком — она в шапке окна (второй ряд), здесь только сужение.
 *
 * «Всё» — [ALL_SECTION], «Общий» — [COMMON_SECTION] переводится в пустой раздел группы.
 * Раздел группы — из местной базы (`chats.section_id`), а не из сервера: сервер разделов не
 * знает (решение 2026-09-18).
 */
@Composable
fun CatalogSectioned(
    groups: List<GroupInfo>,
    line: @Composable (GroupInfo) -> Unit,
    view: BookView,
    sections: List<Section>,
    /** Раздел группы по её идентификатору; пусто — общий. */
    sectionOf: (String) -> String,
    chosen: String,
    onChoose: (String) -> Unit,
    collapsed: Set<String>,
    onToggle: (String) -> Unit,
    /** Сколько нового в разделе — по ключу полосы. */
    freshIn: (String) -> Int,
    /** Управление набором — «Добавить» в плитке. `null` — без плитки «Добавить». */
    onSections: (() -> Unit)?,
    /** Имя и значок «Общего» набора сообществ. */
    common: Section? = null,
) {
    val words = Tima.words.book
    val tabs = sectionTabs(sections, common, words, allKey = ALL_SECTION)
    fun inSection(key: String): List<GroupInfo> = when (key) {
        "", ALL_SECTION -> groups
        COMMON_SECTION -> groups.filter { sectionOf(it.groupId).isEmpty() }
        else -> groups.filter { sectionOf(it.groupId) == key }
    }
    val tiles = view.folders && view.icons
    val accordion = view.folders && !view.icons

    when {
        tiles && chosen.isEmpty() -> Column(Modifier.fillMaxSize()) {
            SectionsTiles(
                tabs = tabs,
                countOf = { inSection(it).size },
                onOpen = onChoose,
                onAdd = onSections,
                newIn = freshIn,
                size = view.tileSize,
            )
        }

        accordion -> LazyColumn(Modifier.fillMaxSize()) {
            // Гармошка — все разделы, включая пустые: их завели осознанно. «Всё» в гармошке
            // не нужно — всё и так на экране.
            for (tab in tabs.drop(1)) {
                val inside = inSection(tab.id)
                if (tab.id == COMMON_SECTION && inside.isEmpty()) continue
                item(key = "section-${tab.id}") {
                    SectionHeader(
                        tab = tab,
                        count = inside.size,
                        fresh = freshIn(tab.id),
                        open = tab.id !in collapsed,
                        onClick = { onToggle(tab.id) },
                    )
                }
                if (tab.id in collapsed) continue
                items(inside, key = { it.groupId }) { line(it) }
            }
        }

        else -> LazyColumn(Modifier.fillMaxSize()) {
            if (tiles) {
                // Внутри раздела плитки: «назад» к плитке и название — вместо полосы.
                item(key = "back") {
                    ListLine(
                        onClick = { onChoose("") },
                        left = { IconButton(glyph = "‹", onClick = { onChoose("") }, live = true) },
                        middle = { Name(tabs.firstOrNull { it.id == chosen }?.name ?: words.everyone) },
                    )
                }
            }
            items(inSection(chosen), key = { it.groupId }) { line(it) }
        }
    }
}
