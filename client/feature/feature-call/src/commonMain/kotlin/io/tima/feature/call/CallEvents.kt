package io.tima.feature.call

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import io.tima.core.call.CallEvent
import io.tima.core.ui.Caption
import io.tima.core.ui.Tertiary
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.words

/**
 * Лента событий звонка — полоса в самом верху окна 0 (ЗВ10).
 *
 * ── УСТРОЙСТВО, И ПОЧЕМУ ОНО ТАКОЕ ──────────────────────────────────────────
 *
 * Решение заказчика 2026-09-20: события **копятся списком, но списком не показываются**.
 *
 * | | Что видно |
 * |---|---|
 * | свёрнуто | последнее событие, одной строкой |
 * | развёрнуто | оно же целиком, плюс «назад» и «далее» по остальным |
 *
 * Список наружу не выворачивается потому, что под этой полосой — видео во весь кадр, и
 * лента отъедала бы у него экран каждый раз ради случая, который бывает редко: почти
 * всегда человеку хватает последней строки. Листание по одному стоит двух кнопок и не
 * стоит ни пикселя, пока свёрнуто.
 *
 * ── ПОЧЕМУ ЛЕНТА ВООБЩЕ НУЖНА ───────────────────────────────────────────────
 *
 * Состояние затирается следующим состоянием. Человек, отвернувшийся на десять секунд, не
 * узнает, что за это время связь пропадала и вернулась, а собеседник включал камеру. У
 * событий это и есть работа — пережить своё время.
 */
@Composable
internal fun CallEvents(events: List<CallEvent>, modifier: Modifier = Modifier) {
    if (events.isEmpty()) return
    val colors = Tima.colors
    val words = Tima.words.call

    var expanded by remember { mutableStateOf(false) }
    // Место в ленте. Считается от конца: пока событий не прибавилось, стоим где стояли, а
    // как прибавилось — показываем новое. Иначе свежее событие приходило бы молча, за
    // спиной у того, кто листает.
    var fromEnd by remember { mutableIntStateOf(0) }
    val at = (events.size - 1 - fromEnd).coerceIn(0, events.lastIndex)
    val event = events[at]

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.functional)
            .padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().testTag(CALL_EVENTS_TAG),
            horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Caption(
                text = event.text,
                modifier = Modifier.weight(1f),
                fontSize = TimaType.sz5,
                weight = FontWeight.SemiBold,
                color = colors.text2,
                // Свёрнуто — ровно одна строка; развёрнуто — сколько нужно. Ради этого
                // «развернуть» и заведено: длинное событие иначе обрезается на полуслове.
                lineOne = !expanded,
            )
            Caption(
                text = if (expanded) "⌃" else "⌄",
                modifier = Modifier
                    .clickable { expanded = !expanded }
                    .padding(horizontal = TimaSpacing.about2),
                fontSize = TimaType.sz4,
                weight = FontWeight.Bold,
                color = colors.text3,
            )
        }

        // Листание — только в развёрнутом виде: свёрнутая полоса показывает последнее, и
        // кнопки «назад» рядом с ним обещали бы ленту, которой человек не просил.
        if (expanded && events.size > 1) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = TimaSpacing.about2),
                horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Step(words.previousEvent, enabled = at > 0) { fromEnd += 1 }
                Tertiary(
                    words.ofTotal(at + 1, events.size),
                    modifier = Modifier.weight(1f),
                    lineOne = true,
                )
                Step(words.nextEvent, enabled = at < events.lastIndex) { fromEnd -= 1 }
            }
        }
    }
}

/**
 * Шаг по ленте.
 *
 * Упёршийся в край **гаснет, а не исчезает**: пропадающая кнопка дёргает строку и меняет
 * расположение соседней — палец попадает не туда, куда целился.
 */
@Composable
private fun Step(label: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = Tima.colors
    Caption(
        text = label,
        modifier = if (enabled) Modifier.clickable(onClick = onClick) else Modifier,
        fontSize = TimaType.sz5,
        weight = FontWeight.SemiBold,
        color = if (enabled) colors.navigation else colors.text3,
        lineOne = true,
    )
}

/** Метка полосы событий для живых сценариев. */
const val CALL_EVENTS_TAG: String = "call:events"
