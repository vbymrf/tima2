package io.tima.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.tima.core.ui.Avatar
import io.tima.core.ui.Caption
import io.tima.core.ui.ControlRow
import io.tima.core.ui.IconButton
import io.tima.core.ui.InCenter
import io.tima.core.ui.ListLine
import io.tima.core.ui.Name
import io.tima.core.ui.ProvidePlace
import io.tima.core.ui.Secondary
import io.tima.core.ui.SectionGlyph
import io.tima.core.ui.SectionTitle
import io.tima.core.ui.Tertiary
import io.tima.core.ui.TextPlace
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.words
import io.tima.domain.chat.PersonField
import io.tima.domain.chat.PersonLook
import io.tima.domain.chat.letter
import io.tima.domain.chat.line
import io.tima.domain.chat.BookEntry
import io.tima.domain.chat.ChatPerson

/**
 * Вкладка «Контакты» окна «Телефон» — ПЛАН-КОНТАКТОВ.md, Д5.
 *
 * ── ЧТО ЗДЕСЬ ЕСТЬ И ПОЧЕМУ ИМЕННО ТАК ──────────────────────────────────────
 *
 * **Раздел «Телефон» — последний и всегда последний.** В нём те, кого нет в TIMa, и у
 * них вместо звонка «Пригласить». Отметки «в TIMa» у строки нет: она повторяла бы одно
 * и то же на всём списке, а на вопрос «кому можно написать» отвечает место в списке.
 *
 * **Второй строкой номер, а не присутствие.** «В сети» и «была 5 минут назад» сервер не
 * отдаёт; нарисовать их значило бы показать выдуманное состояние живого человека.
 *
 * **Звонка из строки нет.** В макете он есть, но клиента LiveKit ещё нет (К7), и кнопка,
 * которая ничего не делает, обещает больше, чем есть. «Пригласить» — исключение: оно
 * ничего не обещает от нас, потому что и делается средствами телефона.
 */
@Composable
fun BookScreen(
    state: BookState,
    onOpen: (BookEntry) -> Unit,
    modifier: Modifier = Modifier,
    /** Выбор раздела на полосе и в плитке; пусто — «Всё» / назад к плитке. */
    onChooseSection: (String) -> Unit = {},
    /** Открыть управление разделами — «Добавить» в плитке. `null` — плитка без него. */
    onSections: (() -> Unit)? = null,
    /** Сколько людей раздела написали новое — янтарная цифра. По ключу полосы. */
    newIn: (String) -> Int = { 0 },
    /** «＋» в правом нижнем углу: завести контакт или раздел. */
    onAdd: (() -> Unit)? = null,
    /** «Пригласить» у того, кого нет в TIMa. */
    onInvite: ((BookEntry) -> Unit)? = null,
    /**
     * Нажали на аватар — личная страница человека (заказчик 2026-09-19).
     *
     * У строки две цели, и так в макете: аватар ведёт к человеку, всё остальное — к
     * действию со строкой. `null` — страницы нет, аватар не нажимается.
     */
    onFace: ((BookEntry) -> Unit)? = null,
    onToggleSection: (String) -> Unit = {},
    /** Разрешение на чтение книги телефона просит платформа, а не этот экран. */
    onAllow: (() -> Unit)? = null,
    /**
     * Человек за строкой книги: имя — из книги, имя пользователя и ник — со справочника.
     * По умолчанию — только то, что есть в книге: так собирают проверки без сети.
     */
    personOf: (BookEntry) -> ChatPerson = { ChatPerson(name = it.name, phone = it.phone) },
    /** Аватар человека за строкой, если он есть и уже доехал. */
    faceOf: (BookEntry) -> ImageBitmap? = { null },
) {
    val words = Tima.words.book
    val colors = Tima.colors
    // Книга — та же группа «списки», что и чаты: строка обрезается и обязана быть
    // одной высоты.
    ProvidePlace(TextPlace.LISTS) {
    // Box поверх колонки: «＋» без строки поиска становится плавающей кнопкой в правом
    // нижнем углу — там же, где «написать» в списке чатов.
    Box(modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        when {
            state.needPermission -> InCenter(Modifier.fillMaxSize()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
                    modifier = Modifier.padding(TimaSpacing.about5),
                ) {
                    Name(words.notRead)
                    // Сказано, что будет и чего не будет: разрешение, о котором не
                    // объяснили, отклоняют — и правильно делают.
                    Secondary(words.notReadAbout)
                    if (onAllow != null) {
                        ControlRow { IconButton(glyph = "✓", onClick = onAllow, live = true) }
                    }
                }
            }

            state.noBook && state.all.isEmpty() -> InCenter(Modifier.fillMaxSize()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
                    modifier = Modifier.padding(TimaSpacing.about5),
                ) {
                    Name(words.addByHand)
                    // Не «разрешите доступ»: на этой платформе разрешать нечего.
                    Secondary(words.noBookHere)
                }
            }

            state.notFoundNothing -> InCenter(Modifier.fillMaxSize()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Name(words.nobodyFound)
                    Secondary(words.nothingMatches(state.search))
                }
            }

            state.all.isEmpty() -> InCenter(Modifier.fillMaxSize()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
                    modifier = Modifier.padding(TimaSpacing.about5),
                ) {
                    Name(words.bookEmpty)
                    Secondary(words.bookEmptyAbout)
                }
            }

            // Исполнение А: плитка ярлычков, пока раздел не выбран. Выбор уводит внутрь —
            // ниже тот же список, только одного раздела, и строка «назад» над ним.
            state.tiles && state.chosen.isEmpty() && state.search.isBlank() -> Column(Modifier.fillMaxSize()) {
                SectionsTiles(
                    tabs = state.tiles(words),
                    countOf = state::countIn,
                    onOpen = onChooseSection,
                    onAdd = onSections,
                    newIn = newIn,
                    size = state.view.tileSize,
                )
            }

            else -> LazyColumn(Modifier.fillMaxSize()) {
                if (state.tiles && state.chosen.isNotEmpty()) {
                    // Внутри раздела плитки: «назад» к плитке и название — вместо ряда
                    // вкладок, как в `разделы.md` («вкладки заменяются на назад и название»).
                    item(key = "back") {
                        ListLine(
                            onClick = { onChooseSection("") },
                            left = { IconButton(glyph = "‹", onClick = { onChooseSection("") }, live = true) },
                            middle = { Name(state.tiles(words).firstOrNull { it.id == state.chosen }?.name ?: words.everyone) },
                        )
                    }
                }
                state.groups(words).forEach { group ->
                    // В исполнениях с полосой разделы стоят над списком, и заголовок внутри
                    // списка был бы вторым способом сказать то же самое. В плитке внутри
                    // раздела заголовок тоже лишний — раздел уже назван строкой «назад».
                    if (state.view.folders && !state.tiles) {
                        item(key = "section-${group.name}") {
                            SectionHeader(
                                tab = SectionTab(group.id.ifEmpty { COMMON_SECTION }, group.name, group.icon),
                                count = group.people.size,
                                fresh = newIn(group.id.ifEmpty { COMMON_SECTION }),
                                open = group.name !in state.collapsed,
                                onClick = { onToggleSection(group.name) },
                            )
                        }
                    }
                    if (state.view.folders && !state.tiles && group.name in state.collapsed) return@forEach
                    items(group.people, key = { it.phone }) { person ->
                        val who = personOf(person)
                        ListLine(
                            onClick = { onOpen(person) },
                            // Картинка, если человек поставил аватар; иначе буква.
                            left = {
                                Avatar(
                                    letters = who.letter(),
                                    image = faceOf(person),
                                    modifier = if (onFace != null) {
                                        Modifier.clickable { onFace(person) }
                                    } else {
                                        Modifier
                                    },
                                )
                            },
                            middle = {
                                Column {
                                    // Первая строка — имя, ник, имя пользователя по «Виду»
                                    // (галки через запятую, иначе первое, что есть); вторая
                                    // — всегда телефон (решение заказчика 2026-09-18).
                                    Name(who.line(state.view.look(), PERSON_FIRST_LINE) ?: words.nameless)
                                    // Телефон крупнее третьестепенной строки в 1,3 раза — заказчик
                                    // 2026-09-18: номер читают и набирают, ему нужен кегль.
                                    Caption(person.phone, fontSize = TimaType.sz6 * 1.3f, weight = FontWeight.SemiBold, color = colors.text3, lineOne = true)
                                }
                            },
                            right = if (group.outsiders && onInvite != null) {
                                { InviteButton(onClick = { onInvite(person) }) }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
    }
        // Завести контакт можно всегда — «＋» плавающей кнопкой в правом нижнем углу,
        // там же, где «написать» в списке чатов. До 2026-09-17 он жил ВНУТРИ строки
        // поиска, и снятая галочка «Показывать поиск» уносила вместе с ним единственный
        // способ добавить человека руками. Строки поиска в списке больше нет вовсе
        // (2026-09-19) — кнопка от неё и не зависит.
        if (onAdd != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(TimaSpacing.about5),
            ) {
                IconButton(glyph = "＋", onClick = onAdd, live = true)
            }
        }
    }
    }
}

/**
 * Полоса раздела: название, число людей и шеврон.
 *
 * Число стоит у названия, а не считается глазами: в свёрнутом разделе список не виден,
 * и без числа непонятно, стоит ли его разворачивать.
 */
@Composable
fun SectionHeader(tab: SectionTab, count: Int, fresh: Int, open: Boolean, onClick: () -> Unit) {
    val colors = Tima.colors
    // Строка раздела по макету `новости.html` («Каталог»), правило `.разд`: знак, зелёный
    // кружок «сколько внутри», имя; справа янтарный «сколько нового» и круглый шеврон.
    // Строка узкая — 7 точек поля сверху и снизу, — поэтому шеврон 28, а не 36, как у
    // кнопок подокна переходов: 36 раздул бы строку до строки списка, а она заголовок.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.functional)
            .clickable(onClick = onClick)
            .padding(start = TimaSpacing.about4, end = 14.dp, top = 7.dp, bottom = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Mark(tab, size = 16.dp, color = colors.text)
        Badge(count, amber = false)
        // Имя обычным регистром, кеглем строки (`.имя-раздела`: щ4, 800), а не капителью
        // заголовка списка: раздел — полка с именем от человека, а не служебная надпись.
        Box(Modifier.weight(1f)) { Name(tab.name) }
        if (fresh > 0) Badge(fresh, amber = true, small = true)
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(colors.quiet, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Caption(if (open) "▾" else "▸", fontSize = TimaType.sz5, weight = FontWeight.Black, color = colors.text2)
        }
    }
}

/**
 * «Пригласить» вместо звонка — в две строки: одной «Пригласить в TIMa» не влезает, на
 * строку контакта остаётся немного места.
 *
 * Звонить такому человеку можно телефоном, но это делает телефон, а не мы: «📞» здесь
 * обещал бы наш звонок.
 */
@Composable
private fun InviteButton(onClick: () -> Unit) {
    ControlRow { IconButton(glyph = "↗", onClick = onClick) }
}


