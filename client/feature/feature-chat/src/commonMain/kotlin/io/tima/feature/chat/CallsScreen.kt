package io.tima.feature.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import io.tima.core.ui.Avatar
import io.tima.core.ui.Caption
import io.tima.core.ui.ControlRow
import io.tima.core.ui.EmptyArea
import io.tima.core.ui.IconButton
import io.tima.core.ui.ListLine
import io.tima.core.ui.Name
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaType
import io.tima.core.ui.words
import io.tima.domain.chat.CallOutcome
import io.tima.domain.chat.CallRecord
import io.tima.domain.chat.ChatPerson
import io.tima.domain.chat.PersonLook
import io.tima.domain.chat.letter
import io.tima.domain.chat.line
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Вкладка «Звонки» окна «Телефон» — ПЛАН-ЖУРНАЛА-ЗВОНКОВ.md, Ж2.
 *
 * ── ОДНА ЗАПИСЬ, ДВА РАЗНЫХ РАССКАЗА ────────────────────────────────────────
 *
 * Главное здесь не вёрстка. Строка в базе **одна на обоих** участников, а читается
 * по-разному: `missed` у звонившего означает «не дозвонился», у вызываемого —
 * «пропущенный». Поэтому сервер отдаёт строку сырой, а слово складывает
 * [CallRecord.outcome] — от лица того, кто смотрит.
 *
 * ── ИМЯ ПРИХОДИТ ИЗ КНИГИ, А НЕ ИЗ ЖУРНАЛА ──────────────────────────────────
 *
 * Сервер знает `user_id`, и только его. «Аня Борисова» — из книги, тем же способом, что
 * везде ([personOf], [faceOf]). Положи имя в журнал — и у него станет два источника
 * правды: человека переименовали в книге, а в журнале он остался прежним.
 *
 * ── КНОПКА В СТРОКЕ ОДНА ────────────────────────────────────────────────────
 *
 * Та, которой звонили (Ж6, решение заказчика 2026-09-23). Видео звонили — видео и
 * перезвонит. Журнал отвечает на «что было», и повтор продолжает то же самое, а не
 * предлагает выбор заново.
 */
@Composable
fun CallsScreen(
    records: List<CallRecord>,
    /** Кто я. От этого зависит каждое слово в строке: запись одна, а читателей двое. */
    me: String,
    personOf: (CallRecord) -> ChatPerson,
    modifier: Modifier = Modifier,
    look: PersonLook = PersonLook(),
    faceOf: (CallRecord) -> ImageBitmap? = { null },
    /** Перезвонить — **тем же видом**, каким звонили тогда. `null` — звонить нечем. */
    onCallAgain: ((CallRecord) -> Unit)? = null,
    /** Открыть переписку с этим человеком. */
    onOpen: ((CallRecord) -> Unit)? = null,
    /**
     * Журнал пуст **потому, что нет связи**, а не потому, что звонков не было.
     *
     * Два пустых экрана выглядят одинаково, а значат разное: первому сказать нечего,
     * второму надо сказать про связь — иначе он решит, что журнал потерялся.
     */
    offline: Boolean = false,
) {
    val words = Tima.words.callLog
    if (records.isEmpty()) {
        EmptyArea(
            title = words.nothingYet,
            explanation = if (offline) words.noConnection else words.nothingYetAbout,
            modifier = modifier,
        )
        return
    }
    LazyColumn(modifier.fillMaxSize().testTag(CALLS_LIST_TAG)) {
        items(records, key = { it.callId }) { record ->
            val who = personOf(record)
            val outcome = record.outcome(me)
            ListLine(
                onClick = onOpen?.let { { it(record) } },
                left = { Avatar(letters = who.letter(), image = faceOf(record)) },
                middle = {
                    Column {
                        Name(who.line(look, PERSON_FIRST_LINE) ?: Tima.words.book.nameless)
                        // Вторая строка — «↗ видео · не дозвонился · 3:05». Стрелка
                        // отвечает на «кто кому», слово — на «чем кончилось»,
                        // длительность бывает не всегда и молчит, когда её нет.
                        Caption(
                            secondLine(record, outcome, me, words),
                            fontSize = TimaType.sz6,
                            // Пропущенные выделяются. Иначе журнал — ровный список, в
                            // котором главное (кому я не ответил) ищут глазами.
                            //
                            // Цвет — `activity`, тот же янтарь, что у непрочитанных
                            // сообщений. Своего цвета для пропущенных не заводим: он
                            // значил бы ровно то же самое — «сюда надо посмотреть», — а
                            // два цвета с одним смыслом человек читает как два разных.
                            weight = if (outcome == CallOutcome.Missed) FontWeight.Bold else FontWeight.Normal,
                            color = if (outcome == CallOutcome.Missed) Tima.colors.activity else Tima.colors.text3,
                            lineOne = true,
                        )
                    }
                },
                right = {
                    ControlRow {
                        Caption(whenOf(record.createdAt, words), fontSize = TimaType.sz6, color = Tima.colors.text3)
                        if (onCallAgain != null) {
                            IconButton(
                                glyph = if (record.video) VIDEO_GLYPH else VOICE_GLYPH,
                                onClick = { onCallAgain(record) },
                                modifier = Modifier.testTag(CALL_AGAIN_TAG),
                            )
                        }
                    }
                },
            )
        }
    }
}

/** «↗ видео · не дозвонился · 3:05» — направление, вид, исход и длительность. */
private fun secondLine(
    record: CallRecord,
    outcome: CallOutcome,
    me: String,
    words: io.tima.core.words.CallLogWords,
): String = buildString {
    append(if (record.outgoing(me)) OUT_ARROW else IN_ARROW)
    append(' ')
    append(if (record.video) words.video else words.voice)
    append(DOT)
    append(
        when (outcome) {
            CallOutcome.Ringing -> words.ringing
            CallOutcome.Outgoing -> words.outgoing
            CallOutcome.Incoming -> words.incoming
            CallOutcome.NotAnswered -> words.notAnswered
            CallOutcome.Missed -> words.missed
            CallOutcome.Cancelled -> words.cancelled
            CallOutcome.Declined -> words.declined
            CallOutcome.Busy -> words.busy
            CallOutcome.Lost -> words.lost
        },
    )
    // Длительность только когда она есть и когда она правда: у `lost` время конца
    // поставил уборщик сервера, и минуты по нему были бы выдумкой.
    val duration = record.durationMs
    if (duration > 0) {
        append(DOT)
        append(lasted(duration))
    }
}

/** «3:05» или «1:02:30». Цифры, а не слова: кегль строки мал, а число читают глазом. */
private fun lasted(ms: Long): String {
    val total = ms / 1000
    val seconds = (total % 60).toString().padStart(2, '0')
    val minutes = (total / 60) % 60
    val hours = total / 3600
    return if (hours > 0) {
        "$hours:${minutes.toString().padStart(2, '0')}:$seconds"
    } else {
        "$minutes:$seconds"
    }
}

/**
 * «14:32», «вчера», «21.09» — когда это было.
 *
 * Часы показываются только у сегодняшних: вчерашнее время суток человеку уже не нужно,
 * ему нужен день. Тот же приём, что в списке переписок.
 */
private fun whenOf(atMs: Long, words: io.tima.core.words.CallLogWords): String {
    val zone = TimeZone.currentSystemDefault()
    val at = Instant.fromEpochMilliseconds(atMs).toLocalDateTime(zone)
    val now = kotlinx.datetime.Clock.System.now().toLocalDateTime(zone)
    val sameDay = at.year == now.year && at.dayOfYear == now.dayOfYear
    if (sameDay) return time(atMs)
    if (at.year == now.year && at.dayOfYear == now.dayOfYear - 1) return words.yesterday
    return at.dayOfMonth.toString().padStart(2, '0') + "." + at.monthNumber.toString().padStart(2, '0')
}

/** Метка списка журнала. */
const val CALLS_LIST_TAG: String = "calls:list"

/** Метка кнопки «перезвонить» в строке журнала. */
const val CALL_AGAIN_TAG: String = "calls:again"

private const val OUT_ARROW = "↗"
private const val IN_ARROW = "↙"
private const val DOT = " · "
private const val VOICE_GLYPH = "📞"
private const val VIDEO_GLYPH = "📹"
