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
 * Те же числа, что на экране стенда, и в том же порядке — человек, привыкший к одному, не
 * должен заново искать их в другом. `—` означает «мерить было нечем», и это не то же
 * самое, что ноль.
 *
 * ── ДВА ЧИСЛА В СТРОКЕ ─────────────────────────────────────────────────────
 *
 * Решение заказчика 2026-09-25: по одному в строке десять чисел закрывали пол-экрана
 * собеседника. Теперь по два, и **значение показывается целиком, а описание — сколько
 * влезло**: число и есть то, ради чего смотрят, а описание узнаётся по началу.
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
    val cells = listOf(
        words.up to stats?.upBitrate?.let { kbit(it) },
        words.down to stats?.downBitrate?.let { kbit(it) },
        words.codecNow to stats?.videoCodec,
        words.encoder to when (stats?.hardwareEncoder) {
            true -> words.hardware
            false -> words.software
            null -> null
        },
        words.framesUp to stats?.upFrames?.takeIf { it.isNotEmpty() }?.joinToString(", "),
        words.frameDown to stats?.downFrame,
        words.phoneSent to traffic?.sentBytes?.let { megabytes(it) },
        words.cpu to load?.cpuPercent?.let { percent(it) },
        words.heat to load?.temperatureC?.let { degrees(it) },
        words.battery to load?.batteryPercent?.let { "$it %" },
    )
    for (pair in cells.chunked(2)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
        ) {
            for ((label, value) in pair) Cell(label, value, Modifier.weight(1f))
            // Нечётное число — пустая половина, иначе последнее число растянулось бы на
            // всю строку и встало не под своим столбцом.
            if (pair.size == 1) Row(Modifier.weight(1f)) {}
        }
    }
}

/**
 * Половина строки: описание слева, значение справа.
 *
 * Значение меряется первым и не режется; описание получает остаток и обрезается
 * многоточием — это и есть «что вместилось».
 */
@Composable
private fun Cell(label: String, value: String?, modifier: Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Secondary(label, modifier = Modifier.weight(1f), lineOne = true)
        Caption(
            text = value ?: "—",
            fontSize = TimaType.sz6,
            weight = FontWeight.Bold,
            color = if (value == null) Tima.colors.text3 else Tima.colors.text,
            lineOne = true,
        )
    }
}
