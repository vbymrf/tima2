package io.tima.feature.group

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.tima.core.ui.Caption
import io.tima.core.ui.Field
import io.tima.core.ui.ListLine
import io.tima.core.ui.ProvidePlace
import io.tima.core.ui.SubwindowHeader
import io.tima.core.ui.Tertiary
import io.tima.core.ui.TextPlace
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.words

/** Метки «Найти» — для сценариев. */
object EntitySearchTags {
    const val FIELD = "search:field"
    const val ROW = "search:row"
}

/** Где лежит найденное — туда и ведёт нажатие. */
enum class FoundIn {
    /** Переписка группы на этом телефоне: открыть на месте сообщения. */
    Chat,

    /** Сохранённая лента канала: запись с комментариями. */
    Post,

    /** Сообщение уровня 0 страницы: «Описание». */
    Zero,
}

/** Найденное сообщение: где, номер, когда, текст. */
data class FoundLine(val place: FoundIn, val number: Long, val atMs: Long, val text: String)

/** С какой длины запроса искать: одна буква находит почти всё и ничего не сужает. */
const val FIND_FROM = 2

/**
 * Отрывок вокруг найденного, без учёта регистра; `null` — в тексте нет. Длинное сообщение показывается с места
 * совпадения: найденное слово в конце третьей строки человек бы не увидел.
 */
fun foundSnippet(text: String, query: String, before: Int = 30): String? {
    val q = query.trim()
    if (q.length < FIND_FROM) return null
    val at = text.indexOf(q, ignoreCase = true)
    if (at < 0) return null
    val flat = text.replace('\n', ' ')
    return if (at <= before) flat else "…" + flat.substring(at - before).trimStart()
}

/**
 * «Найти» страницы сущности (кнопка после «Настройки», заказчик 2026-11-23): по сообщениям, которые есть на этом
 * телефоне, — переписка группы, сохранённая лента канала, — и по сообщениям уровня 0. Новые сверху; нажатие ведёт к
 * найденному. Текст — группой «Списки» «Шрифтов и размеров», шапка — «Шапки».
 */
@Composable
fun EntitySearchScreen(
    title: String,
    query: String,
    onQuery: (String) -> Unit,
    /** Где искать; `null` — сообщения ещё читаются. */
    found: List<FoundLine>?,
    time: (Long) -> String,
    onOpen: (FoundLine) -> Unit,
    onBack: () -> Unit,
) {
    val w = Tima.words.socium
    ProvidePlace(TextPlace.LISTS) {
        Column(Modifier.fillMaxSize().background(Tima.colors.surface)) {
            SubwindowHeader(title = w.findTitle, onBack = onBack, caption = title, avatar = title.take(2).uppercase())
            Column(
                Modifier.fillMaxWidth().padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about3),
                verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
            ) {
                Field(query, onQuery, modifier = Modifier.testTag(EntitySearchTags.FIELD), hint = w.findHint, lineOne = true, autoFocus = true)
                Tertiary(w.findAbout)
            }
            val q = query.trim()
            val hits = if (found == null || q.length < FIND_FROM) emptyList() else found.mapNotNull { f -> foundSnippet(f.text, q)?.let { f to it } }
            when {
                found == null -> Tertiary(w.findReading, Modifier.padding(TimaSpacing.about4))
                q.length < FIND_FROM -> Unit
                hits.isEmpty() -> Tertiary(w.findNothing, Modifier.padding(TimaSpacing.about4))
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(hits.size, key = { hits[it].first.place.name + ":" + hits[it].first.number }) { i ->
                        val (line, snippet) = hits[i]
                        ListLine(
                            modifier = Modifier.testTag(EntitySearchTags.ROW),
                            onClick = { onOpen(line) },
                            right = { Tertiary(time(line.atMs), lineOne = true) },
                            middle = { Caption(snippet, fontSize = TimaType.sz4, maxLines = 3) },
                        )
                    }
                }
            }
        }
    }
}
