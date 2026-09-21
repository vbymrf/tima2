package io.tima.feature.call

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.tima.core.call.BenchSample
import io.tima.core.ui.Caption
import io.tima.core.ui.IconButton
import io.tima.core.ui.Secondary
import io.tima.core.ui.Tertiary
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.words

/**
 * Что показывает окно звонка про идущий забег — решение заказчика 2026-09-21.
 *
 * @param at номер набора в забеге, с единицы. `0` — набора нет в списке, полосы не будет.
 * @param last последний снятый отсчёт. `null` — ещё не сняли ни одного.
 */
data class BenchLine(
    val at: Int,
    val total: Int,
    val preset: String,
    val last: BenchSample? = null,
)

/**
 * Полоса забега над лентой событий: **номер прогона строкой, числа — по развороту**.
 *
 * ── ЗАЧЕМ НОМЕР ВИДЕН В ОКНЕ ЗВОНКА ────────────────────────────────────────
 *
 * Забег идёт без сговора телефонов: каждый считает звонки сам и после каждого берёт
 * следующий набор. Списки одинаковы — значит идут в ногу. **Единственное, чем это
 * проверяется, — номер на экране у обоих**, и смотреть его надо там, где идёт звонок, а
 * не уходя в окно стенда.
 *
 * ── ПОЧЕМУ ЧИСЛА СПРЯТАНЫ ЗА РАЗВОРОТОМ ────────────────────────────────────
 *
 * Их восемь, и во время разговора человек смотрит не на них, а на собеседника. Разворот
 * тот же, что у ленты событий, — два разных способа раскрыть строку в одном окне человек
 * читает как поломку.
 */
@Composable
internal fun BenchStrip(line: BenchLine, modifier: Modifier = Modifier) {
    if (line.at <= 0 || line.total <= 0) return
    val colors = Tima.colors
    val words = Tima.words.bench
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.softAccent)
            .padding(horizontal = TimaSpacing.about3, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Caption(
                text = words.runAt(line.at, line.total) + " · " + line.preset,
                fontSize = TimaType.sz5,
                weight = FontWeight.Bold,
                lineOne = true,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                glyph = if (expanded) "▲" else "▼",
                onClick = { expanded = !expanded },
            )
        }
        if (expanded) Numbers(line.last)
    }
}

/**
 * Числа последнего отсчёта: что передаётся и чем платит телефон.
 *
 * Те же две группы, что на экране стенда, и в том же порядке — человек, привыкший к
 * одному, не должен заново искать их в другом. `—` означает «мерить было нечем», и это
 * не то же самое, что ноль.
 */
@Composable
private fun Numbers(last: BenchSample?) {
    val words = Tima.words.bench
    if (last == null) {
        Tertiary(words.runsBySelf)
        return
    }
    val stats = last.stats
    val load = last.load
    val traffic = last.traffic
    Line(words.up, stats?.upBitrate?.let { kbit(it) })
    Line(words.down, stats?.downBitrate?.let { kbit(it) })
    Line(words.codecNow, stats?.videoCodec)
    Line(
        words.encoder,
        when (stats?.hardwareEncoder) {
            true -> words.hardware
            false -> words.software
            null -> null
        },
    )
    Line(words.phoneSent, traffic?.sentBytes?.let { megabytes(it) })
    Line(words.cpu, load?.cpuPercent?.let { percent(it) })
    Line(words.heat, load?.temperatureC?.let { degrees(it) })
    Line(words.battery, load?.batteryPercent?.let { "$it %" })
}

@Composable
private fun Line(label: String, value: String?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Secondary(label)
        Caption(
            text = value ?: "—",
            fontSize = TimaType.sz6,
            weight = FontWeight.Bold,
            color = if (value == null) Tima.colors.text3 else Tima.colors.text,
        )
    }
}
