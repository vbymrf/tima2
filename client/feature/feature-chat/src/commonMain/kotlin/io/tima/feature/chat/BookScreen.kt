package io.tima.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.tima.core.ui.Avatar
import io.tima.core.ui.ControlRow
import io.tima.core.ui.Field
import io.tima.core.ui.IconButton
import io.tima.core.ui.InCenter
import io.tima.core.ui.ListLine
import io.tima.core.ui.Name
import io.tima.core.ui.Secondary
import io.tima.core.ui.SectionTitle
import io.tima.core.ui.Tertiary
import io.tima.core.ui.TimaSpacing
import io.tima.domain.chat.BookEntry

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
    onSearch: (String) -> Unit,
    onOpen: (BookEntry) -> Unit,
    modifier: Modifier = Modifier,
    /** «＋» у строки поиска: завести контакт или раздел. */
    onAdd: (() -> Unit)? = null,
    /** «Пригласить» у того, кого нет в TIMa. */
    onInvite: ((BookEntry) -> Unit)? = null,
    onToggleSection: (String) -> Unit = {},
    /** Разрешение на чтение книги телефона просит платформа, а не этот экран. */
    onAllow: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxSize()) {
        if (state.view.showSearch) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2),
                horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Field(
                    value = state.search,
                    onChange = onSearch,
                    hint = "Поиск по имени, нику или номеру…",
                    modifier = Modifier.weight(1f),
                )
                if (onAdd != null) {
                    ControlRow { IconButton(glyph = "＋", onClick = onAdd, live = true) }
                }
            }
        }

        when {
            state.needPermission -> InCenter(Modifier.fillMaxSize()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
                    modifier = Modifier.padding(TimaSpacing.about5),
                ) {
                    Name("Контакты не прочитаны")
                    // Сказано, что будет и чего не будет: разрешение, о котором не
                    // объяснили, отклоняют — и правильно делают.
                    Secondary(
                        "Приложение возьмёт из телефонной книги имена и номера, чтобы " +
                            "показать, кто из них уже в TIMa. Номера уходят на сервер " +
                            "закрытыми: он сверяет их, не читая.",
                    )
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
                    Name("Здесь контакты добавляют вручную")
                    // Не «разрешите доступ»: на этой платформе разрешать нечего.
                    Secondary("Телефонной книги у настольной системы нет — добавьте по номеру.")
                }
            }

            state.notFoundNothing -> InCenter(Modifier.fillMaxSize()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Name("Никого не нашлось")
                    Secondary("По «${state.search}» в контактах совпадений нет")
                }
            }

            state.all.isEmpty() -> InCenter(Modifier.fillMaxSize()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
                    modifier = Modifier.padding(TimaSpacing.about5),
                ) {
                    Name("В контактах пока никого")
                    Secondary("Прочитаем телефонную книгу или добавьте человека по номеру.")
                }
            }

            else -> LazyColumn(Modifier.fillMaxSize()) {
                state.groups.forEach { group ->
                    // В виде «меню» разделы стоят вторым рядом вкладок, и полоса внутри
                    // списка была бы вторым способом сказать то же самое.
                    if (state.view.folders) {
                        item(key = "раздел-${group.name}") {
                            SectionHeader(
                                title = group.name,
                                count = group.people.size,
                                open = group.name !in state.collapsed,
                                onClick = { onToggleSection(group.name) },
                            )
                        }
                    }
                    if (state.view.folders && group.name in state.collapsed) return@forEach
                    items(group.people, key = { it.phone }) { person ->
                        ListLine(
                            onClick = { onOpen(person) },
                            left = { Avatar(letters = letters(person, state.view)) },
                            middle = {
                                Column {
                                    Name(shown(person, state.view) ?: "Без имени")
                                    val вторая = second(person, state.view)
                                    if (вторая != null) Tertiary(вторая, lineOne = true)
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
}

/**
 * Полоса раздела: название, число людей и шеврон.
 *
 * Число стоит у названия, а не считается глазами: в свёрнутом разделе список не виден,
 * и без числа непонятно, стоит ли его разворачивать.
 */
@Composable
private fun SectionHeader(title: String, count: Int, open: Boolean, onClick: () -> Unit) {
    ListLine(
        onClick = onClick,
        middle = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionTitle(title)
                Tertiary(count.toString(), lineOne = true)
            }
        },
        right = { Tertiary(if (open) "▾" else "▸", lineOne = true) },
    )
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

/**
 * Что показать первой строкой.
 *
 * Порядок задан и не переставляется: имя → имя пользователя → ник → телефон. **Ни одной
 * галки — тот же порядок**: выбор «ничего» означает «как обычно», а не пустую строку.
 * Своё имя перебивает книжное — это внутри [BookEntry.name].
 */
private fun shown(person: BookEntry, view: BookView): String? {
    if (view.showName) person.name?.let { return it }
    if (view.showPhone && !view.showName && !view.showNickname) return person.phone
    return person.name ?: person.phone
}

/**
 * Вторая строка — всё выбранное, кроме того, что уже стоит первой, через «·».
 *
 * Пустого поля здесь не бывает: у кого нет ника, у того нет и строки о нём, а не «@—».
 */
private fun second(person: BookEntry, view: BookView): String? {
    val первая = shown(person, view)
    val части = buildList {
        if (view.showPhone && person.phone != первая) add(person.phone)
    }
    return части.joinToString(" · ").ifBlank { null }
}

/** Буквы аватара: первая показанного имени, иначе первая номера. */
private fun letters(person: BookEntry, view: BookView): String =
    (shown(person, view) ?: person.phone).trimStart('+').take(1).uppercase()
