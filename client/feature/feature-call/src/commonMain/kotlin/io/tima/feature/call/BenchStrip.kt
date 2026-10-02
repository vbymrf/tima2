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
                live = true,
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
/**
 * Полоса стенда группового звонка (заказчик 2026-10-01, 5а): номер прогона и набор, без
 * разворачивания — вместо него «Журнал стенда»: все участники, у каждого — его набор и
 * что я от него принимаю.
 */
@Composable
internal fun GroupBenchStrip(line: BenchLine, onJournal: () -> Unit, modifier: Modifier = Modifier) {
    if (line.at <= 0 || line.total <= 0) return
    Row(
        modifier = modifier.fillMaxWidth().background(Tima.colors.softAccent)
            .padding(horizontal = TimaSpacing.about3, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
    ) {
        Caption(
            text = Tima.words.bench.runAt(line.at, line.total) + " · " + line.preset,
            fontSize = TimaType.sz5,
            weight = FontWeight.Bold,
            lineOne = true,
            modifier = Modifier.weight(1f),
        )
        io.tima.core.ui.Button(label = Tima.words.groupCall.benchJournal, onClick = onJournal, kind = io.tima.core.ui.ButtonKind.Quiet)
    }
}

/**
 * Журнал стенда группового: список участников в виде нашего журнала; нажал — его карточка.
 * Своя карточка — те же числа, что в окне 0 при развёрнутом стенде.
 */
@Composable
internal fun GroupBenchJournal(line: BenchLine?, tiles: List<GroupTile>, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val words = Tima.words.groupCall
    var open by remember { mutableStateOf<String?>(null) }
    val chosen = tiles.firstOrNull { it.key == open }
    Column(modifier.fillMaxWidth().background(Tima.colors.surface)) {
        Row(
            Modifier.fillMaxWidth().background(Tima.colors.functional).padding(horizontal = TimaSpacing.about3, vertical = TimaSpacing.about1),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
        ) {
            IconButton(glyph = "‹", onClick = { if (chosen != null) open = null else onClose() }, live = true)
            Caption(
                chosen?.name ?: words.benchJournal,
                modifier = Modifier.weight(1f),
                fontSize = TimaType.sz4,
                weight = FontWeight.Bold,
                lineOne = true,
            )
        }
        if (chosen == null) {
            for (tile in tiles) {
                io.tima.core.ui.ListLine(
                    onClick = { open = tile.key },
                    left = { io.tima.core.ui.Avatar(letters = tile.letters) },
                    // Строка 1 — имя и набор; строка 2 — «H.264 А 720×1280» или «Видео не
                    // приходит» (заказчик 2026-10-02).
                    middle = {
                        val preset = if (tile.self) line?.preset else tile.bench?.split(" · ")?.firstOrNull()
                        io.tima.core.ui.Name(listOfNotNull(tile.name, preset?.takeIf { it != "—" }).joinToString(" · "))
                        val trouble = tile.videoTrouble
                        Caption(
                            trouble ?: benchSecondLine(tile, line) ?: words.benchUnknown,
                            fontSize = TimaType.sz6,
                            weight = FontWeight.SemiBold,
                            color = if (trouble != null) Tima.colors.alarm else Tima.colors.text3,
                            lineOne = true,
                        )
                    },
                    right = { Tertiary("›", lineOne = true) },
                )
            }
        } else if (chosen.self) {
            Column(Modifier.padding(TimaSpacing.about3)) { Numbers(line?.last) }
        } else {
            PeerNumbers(chosen)
        }
    }
}

/**
 * Вторая строка журнала стенда: кодек вверх, аппаратный или программный, кадр — «H.264 А
 * 720×1280». Свой — по своим числам; участника — с его слов (атрибут `tima.bench`).
 */
@Composable
private fun benchSecondLine(tile: GroupTile, line: BenchLine?): String? {
    val bench = Tima.words.bench
    if (tile.self) {
        val st = line?.last?.stats ?: return null
        val codec = st.videoCodec ?: return null
        val who = when (st.hardwareEncoder) {
            true -> bench.hardwareShort
            false -> bench.softwareShort
            null -> ""
        }
        return listOf(prettyCodec(codec), who, st.upFrames.lastOrNull().orEmpty()).filter { it.isNotBlank() }.joinToString(" ")
    }
    val parts = tile.bench?.split(" · ") ?: return null
    val codecSize = parts.getOrNull(1)?.split(" ") ?: return null
    val who = when {
        parts.getOrNull(2)?.startsWith("апп") == true -> bench.hardwareShort
        parts.getOrNull(2)?.startsWith("прог") == true -> bench.softwareShort
        else -> ""
    }
    return listOf(prettyCodec(codecSize.getOrNull(0).orEmpty()), who, codecSize.getOrNull(1).orEmpty())
        .filter { it.isNotBlank() && it != "—" }.joinToString(" ").ifBlank { null }
}

/** Кодек словом, как его пишут люди: H264 → H.264. */
private fun prettyCodec(codec: String): String = when (codec.uppercase()) {
    "H264" -> "H.264"
    "H265" -> "H.265"
    else -> codec.uppercase()
}

/** Карточка участника: его набор с его слов и что я от него принимаю. */
@Composable
private fun PeerNumbers(tile: GroupTile) {
    val words = Tima.words.groupCall
    Column(Modifier.fillMaxWidth().padding(TimaSpacing.about3), verticalArrangement = Arrangement.spacedBy(TimaSpacing.about1)) {
        Secondary(words.benchSet)
        Caption(tile.bench ?: words.benchUnknown, fontSize = TimaType.sz5, weight = FontWeight.Bold)
        Secondary(words.benchReceive)
        val n = tile.incoming
        if (n == null) {
            Tertiary(words.benchNothing)
            return
        }
        val rows: List<List<Pair<String, String?>>> = listOf(
            listOf(words.benchFrame to n.frame, words.benchCodec to n.codec),
            listOf(words.benchKbit to n.kbit?.toString(), Tima.words.bench.codecDown to n.decoder),
            listOf(words.benchDecodeMs to n.decodeMs?.let { oneDecimal(it) }, words.benchDropped to n.dropped?.toString()),
            listOf(words.benchFps to n.fps?.let { oneDecimal(it) }, words.benchQp to n.qp?.let { oneDecimal(it) }),
            listOf(words.benchFreezes to n.freezes.toString()),
        )
        for (pair in rows) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about3)) {
                for ((label, value) in pair) Cell(label, value, Modifier.weight(1f))
                if (pair.size == 1) Row(Modifier.weight(1f)) {}
            }
        }
    }
}

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
    // Строки задаются явно, а не делятся по два подряд (заказчик 2026-09-27): «вверх» и
    // «вниз» одного показателя — в одной строке, одно под другим не ищут. Деление по
    // счёту сдвигало пары от любой вставки: «Кадр/с вверх» оказался рядом с «QP вверх».
    val rows: List<List<Pair<String, String?>>> = listOf(
        listOf(words.up to stats?.upBitrate?.let { kbit(it) }, words.down to stats?.downBitrate?.let { kbit(it) }),
        listOf(
            words.framesUp to stats?.upFrames?.takeIf { it.isNotEmpty() }?.joinToString(", "),
            words.frameDown to stats?.downFrame,
        ),
        listOf(words.fpsUp to stats?.upFps?.let { oneDecimal(it) }, words.fpsDown to stats?.downFps?.let { oneDecimal(it) }),
        listOf(words.qpUp to stats?.upQp?.let { oneDecimal(it) }, words.qpDown to stats?.downQp?.let { oneDecimal(it) }),
        // «Кодек вверх VP8 П», «Кодек вниз VP9 А» — что ушло и пришло и кто обработал
        // (заказчик 2026-09-30, 2а).
        listOf(
            words.codecUp to codecWho(stats?.videoCodec, stats?.hardwareEncoder),
            words.codecDown to codecWho(stats?.downCodec, stats?.hardwareDecoder),
        ),
        listOf(words.phoneSent to traffic?.sentBytes?.let { megabytes(it) }, words.cpu to load?.cpuPercent?.let { percent(it) }),
        listOf(words.heat to load?.temperatureC?.let { degrees(it) }, words.battery to battery(load?.batteryPercent, load?.charging)),
        listOf(words.current to load?.currentMa?.let { milliAmps(it) }),
    )
    for (pair in rows) {
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

/**
 * «VP8 П» — кодек и кто его обработал: «А» — аппаратный, «П» — программный (заказчик
 * 2026-09-30, 2а). Кодек не известен — `null`, и ячейка покажет прочерк.
 */
@Composable
internal fun codecWho(codec: String?, hardware: Boolean?): String? {
    val words = Tima.words.bench
    codec ?: return null
    val who = when (hardware) {
        true -> words.hardwareShort
        false -> words.softwareShort
        null -> return codec
    }
    return "$codec $who"
}
