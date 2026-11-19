package io.tima.feature.group

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.tima.core.ui.Avatar
import io.tima.core.ui.Button
import io.tima.core.ui.Caption
import io.tima.core.ui.CheckMark
import io.tima.core.ui.Counter
import io.tima.core.ui.Field
import io.tima.core.ui.ListLine
import io.tima.core.ui.Name
import io.tima.core.ui.Secondary
import io.tima.core.ui.SubwindowHeader
import io.tima.core.ui.Tertiary
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.words
import io.tima.domain.chat.EntityReach
import io.tima.domain.chat.EntityType
import io.tima.domain.chat.ThreadMessage
import io.tima.domain.chat.ThreadRow
import io.tima.domain.chat.ThreadStep

/** Метки переписки с сущностью — для сценариев. */
object ThreadTags {
    const val ROW = "answers:row"
    const val INPUT = "thread:input"
    const val SEND = "thread:send"
    const val APPEAL = "appeals:row"
    const val ACCEPT = "appeals:accept"
    const val BLOCK = "thread:block"
}

/**
 * «Общение → Ответы» (ПЛАН-(СЦ) §1.9, 5): строка — название сущности, две строки — сообщение; строки «от кого» нет.
 */
@Composable
fun AnswersList(rows: List<ThreadRow>?, time: (Long) -> String, onOpen: (ThreadRow) -> Unit) {
    val w = Tima.words.socium
    if (rows == null) return Box(Modifier.fillMaxSize().padding(TimaSpacing.about5)) { Secondary("…") }
    if (rows.isEmpty()) return Box(Modifier.fillMaxSize().padding(TimaSpacing.about5)) { Secondary(w.answersEmpty) }
    LazyColumn(Modifier.fillMaxSize()) {
        items(rows, key = { it.kind.wire + it.entityId }) { row ->
            EntityRow(
                title = row.title,
                type = EntityType(row.kind, EntityReach.Public),
                role = null,
                look = RowLook(preview = row.preview, atMs = row.lastAtMs, time = time(row.lastAtMs), fresh = row.unread,
                    muted = row.state != io.tima.domain.chat.ThreadState.On),
                onClick = { onOpen(row) },
                modifier = Modifier.testTag(ThreadTags.ROW),
            )
        }
    }
}

/**
 * Лента с сущностью (§1.9): у человека — сообщения сущности слева, свои справа; у команды — наоборот, и над
 * сообщением сущности — кто из команды писал. Ввод — строкой; содержимое уходит тем же телом, что у личной группы.
 */
@Composable
fun ThreadScreen(
    title: String,
    caption: String,
    messages: List<ThreadMessage>,
    /** Смотрит команда сущности: свои — сообщения стороны сущности. */
    asEntity: Boolean,
    text: (ThreadMessage) -> String,
    authorName: (String) -> String,
    time: (Long) -> String,
    input: String,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
    outcome: ThreadStep?,
    onBack: () -> Unit,
    /** Человек: «Уведомления» ленты — включено, выключено, заблокировано; команда: «Заблокировать человека». */
    extra: (@Composable () -> Unit)? = null,
) {
    val w = Tima.words.socium
    val colors = Tima.colors
    Column(Modifier.fillMaxSize().background(colors.surface)) {
        SubwindowHeader(title = title, onBack = onBack, caption = caption, avatar = title.take(2).uppercase())
        extra?.invoke()
        val list = rememberLazyListState()
        LaunchedEffect(messages.size) { if (messages.isNotEmpty()) list.scrollToItem(messages.size - 1) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = TimaSpacing.about3), state = list,
            verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2)) {
            items(messages, key = { it.messageId }) { m ->
                val mine = m.fromEntity == asEntity
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                    Column(
                        Modifier.widthIn(max = 300.dp)
                            .background(if (mine) colors.my else colors.softAccent, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        if (asEntity && m.fromEntity) m.authorId?.let { Tertiary(authorName(it)) }
                        Caption(text(m), fontSize = TimaType.sz4)
                        Tertiary(time(m.atMs))
                    }
                }
            }
        }
        val trouble = when (outcome) {
            ThreadStep.NotAccepting -> w.notAccepting
            ThreadStep.Blocked -> w.threadBlocked
            ThreadStep.PeopleLimit -> w.peopleLimit
            ThreadStep.Offline -> Tima.words.trouble.offline
            is ThreadStep.Refused -> outcome.reason
            else -> null
        }
        trouble?.let { Secondary(it, Modifier.padding(horizontal = TimaSpacing.about4)) }
        Row(
            Modifier.fillMaxWidth().padding(TimaSpacing.about3),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
        ) {
            Field(input, onInput, modifier = Modifier.weight(1f).testTag(ThreadTags.INPUT), hint = w.writeHint, sentences = true)
            Button(w.send, onClick = onSend, enabled = input.isNotBlank(), modifier = Modifier.testTag(ThreadTags.SEND))
        }
    }
}

/**
 * «Управление → Обращения» (§1.9, 6): «Принимать сообщения», сколько людям написали за сутки из предела, ленты с
 * людьми. Прочитано — общее для всей команды.
 */
@Composable
fun AppealsScreen(
    title: String,
    appeals: io.tima.domain.chat.Appeals?,
    nameOf: (String) -> String,
    time: (Long) -> String,
    onAccept: (Boolean) -> Unit,
    onOpen: (ThreadRow) -> Unit,
    onBack: () -> Unit,
) {
    val w = Tima.words.socium
    Column(Modifier.fillMaxSize().background(Tima.colors.surface)) {
        SubwindowHeader(title = w.manageAppeals, onBack = onBack, caption = title, avatar = title.take(2).uppercase())
        if (appeals == null) {
            Secondary(Tima.words.trouble.offline, Modifier.padding(TimaSpacing.about5))
            return
        }
        Row(
            Modifier.fillMaxWidth().clickable { onAccept(!appeals.accepts) }.padding(TimaSpacing.about4).testTag(ThreadTags.ACCEPT),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
        ) {
            CheckMark(appeals.accepts)
            Secondary(w.acceptMessages)
        }
        Tertiary(w.peopleToday(appeals.peopleToday, appeals.peopleLimit), Modifier.padding(horizontal = TimaSpacing.about4))
        LazyColumn(Modifier.fillMaxSize()) {
            items(appeals.rows, key = { it.userId }) { row ->
                ListLine(
                    modifier = Modifier.testTag(ThreadTags.APPEAL),
                    onClick = { onOpen(row) },
                    left = { Avatar(letters = nameOf(row.userId).take(2).uppercase()) },
                    middle = {
                        Name(nameOf(row.userId))
                        Caption(row.preview, fontSize = TimaType.sz5, color = Tima.colors.text2, maxLines = 2)
                    },
                    right = {
                        Tertiary(time(row.lastAtMs))
                        Counter(row.unread)
                        if (row.blocked) Caption(w.blockedMark, fontSize = TimaType.sz6, weight = FontWeight.Bold, color = Tima.colors.alarm)
                    },
                )
            }
        }
    }
}
