package io.tima.feature.call

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.tima.core.call.AudioPreset
import io.tima.core.call.BenchSample
import io.tima.core.call.BenchSummary
import io.tima.core.call.Degradation
import io.tima.core.call.LayerMode
import io.tima.core.call.PublishPreset
import io.tima.core.call.VideoCodec
import io.tima.core.call.VideoPreset
import io.tima.core.ui.Button
import io.tima.core.ui.ButtonKind
import io.tima.core.ui.Caption
import io.tima.core.ui.Chip
import io.tima.core.ui.ChipKind
import io.tima.core.ui.Field
import io.tima.core.ui.IconButton
import io.tima.core.ui.Name
import io.tima.core.ui.Secondary
import io.tima.core.ui.Tertiary
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.words

/**
 * Испытательный стенд звонков — [ПЛАН-СТЕНДА-ЗВОНКОВ](../../../../../../../../doc_mig/ПЛАН-СТЕНДА-ЗВОНКОВ.md),
 * §3: «как звоним», «что передаётся», «чем платим».
 *
 * **Экран чистый, как и экран звонка.** Он получает пресет и числа, зовёт обратные вызовы
 * и ничего не считает сам: ни LiveKit, ни `/proc`, ни настроек он не видит. Поэтому его
 * можно снять в проверках целиком, не поднимая ни одного звонка.
 *
 * ── ПОЧЕМУ ВСЁ НА ОДНОМ ЭКРАНЕ, А НЕ ВКЛАДКАМИ ─────────────────────────────
 *
 * Настройки и числа нужны **одновременно**: смысл прогона в том, чтобы менять одно и
 * смотреть на другое. Разведённые по вкладкам, они заставляли бы переключаться между
 * причиной и следствием — а это ровно та пара, которую надо видеть рядом.
 *
 * ── ЧЕГО ЗДЕСЬ НЕТ ─────────────────────────────────────────────────────────
 *
 * **Смены настроек на ходу.** Кодек и слои участвуют в согласовании, и менять их посреди
 * звонка — пересогласование, а иногда разрыв (С-В5). Выбранный набор применяется к
 * **следующему** звонку, и экран говорит об этом прямо, а не оставляет догадываться.
 */
@Composable
fun BenchScreen(
    preset: PublishPreset,
    presets: List<PublishPreset>,
    running: Boolean,
    samples: List<BenchSample>,
    runs: List<BenchSummary>,
    /** Идёт ли разговор. От этого зависит, можно ли применить набор прямо сейчас. */
    inCall: Boolean,
    /** Куда лёг отчёт прошлого прогона. `null` — не записался или писать некуда. */
    lastFile: String?,
    /** Сколько первых секунд разговора не учитывать. */
    skip: Int,
    /** Нажата ли «Начать прогон». Не нажата — звонок идёт как обычный. */
    armed: Boolean,
    onChange: (PublishPreset) -> Unit,
    onSave: (PublishPreset) -> Unit,
    onForget: (String) -> Unit,
    onApply: () -> Unit,
    onArm: (Boolean) -> Unit,
    /** Шаг по кольцу наборов руками: `-1` вверх, `+1` вниз. */
    onStep: (Int) -> Unit,
    onSkip: (Int) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val words = Tima.words.bench
    Column(
        modifier = modifier
            .fillMaxSize()
            // Свой фон, как у окна звонка: окно рисуется голым, без оправы, и сквозь
            // непокрашенное видно то, что под ним.
            .background(Tima.colors.surface)
            .verticalScroll(rememberScrollState())
            .padding(TimaSpacing.about3),
        verticalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
    ) {
        Arming(armed, onArm)
        Publishing(preset, onChange)
        Saving(preset, presets, onChange, onSave, onForget, onStep)
        Applying(inCall, onApply)
        Running(running, samples, lastFile, skip, onSkip, onStop)
        Numbers(samples.lastOrNull())
        Runs(runs)
        Tertiary(words.appliesToNextCall)
    }
}

// ── «КАК ЗВОНИМ» — §3.1 ─────────────────────────────────────────────────────

@Composable
private fun Publishing(preset: PublishPreset, onChange: (PublishPreset) -> Unit) {
    val words = Tima.words.bench
    val video = preset.video
    fun video(change: VideoPreset.() -> VideoPreset) =
        onChange(preset.copy(video = video.change()))

    Section(words.sectionHow) {
        Pick(words.codec, VideoCodec.entries.map { it to it.label() }, video.codec) { chosen ->
            video {
                // SVC умеет только VP9. Оставить режим при смене кодека значило бы
                // показывать набор, которого не бывает, — и получить в отчёте прогон
                // «H.264 SVC», которого SDK не сделает.
                copy(codec = chosen, layers = layersFor(chosen, layers))
            }
        }
        // Тип назван явно: список запасных кодеков включает «нет», то есть null, и
        // вывести из него общий тип компилятору не из чего.
        //
        // Только H.264 и VP8: другие SDK запасными не посылает (VideoCodec.backupCapable),
        // и выбор VP9 здесь обещал бы то, чего в сети не бывает.
        Pick<VideoCodec?>(
            words.backup,
            VideoCodec.entries.filter { it.backupCapable }.map { it to it.label() } + (null to words.noBackup),
            video.backup,
        ) { chosen -> video { copy(backup = chosen) } }
        Pick(
            words.layers,
            listOf(
                LayerMode.Single to words.single,
                LayerMode.Simulcast to words.simulcast,
                LayerMode.Svc to words.svc,
            ).filter { it.first in layersOf(video.codec) },
            layersFor(video.codec, video.layers),
        ) { chosen -> video { copy(layers = chosen) } }
        if (layersFor(video.codec, video.layers) == LayerMode.Svc) {
            Pick(words.scalability, SCALABILITY.map { it to it }, video.scalability) { chosen ->
                video { copy(scalability = chosen) }
            }
        }
        Pick(words.size, SIZES.map { it to "${it.first}×${it.second}" }, video.width to video.height) { chosen ->
            video { copy(width = chosen.first, height = chosen.second) }
        }
        Pick(words.fps, FRAMES.map { it to it.toString() }, video.fps) { chosen ->
            video { copy(fps = chosen) }
        }
        Pick(words.bitrate, BITRATES.map { it to kbit(it.toLong()) }, video.bitrate) { chosen ->
            video { copy(bitrate = chosen, maxBitrate = chosen) }
        }
        Pick(
            words.degradation,
            listOf(
                Degradation.MaintainResolution to words.keepSize,
                Degradation.MaintainFramerate to words.keepFrames,
                Degradation.Balanced to words.asWebrtc,
            ),
            video.degradation,
        ) { chosen -> video { copy(degradation = chosen) } }
        Switch(words.dynacast, video.dynacast) { on -> video { copy(dynacast = on) } }
        Switch(words.adaptiveStream, video.adaptiveStream) { on -> video { copy(adaptiveStream = on) } }

        Sound(preset.audio) { onChange(preset.copy(audio = it)) }
    }
}

@Composable
private fun Sound(audio: AudioPreset, onChange: (AudioPreset) -> Unit) {
    val words = Tima.words.bench
    Caption(words.sound)
    Switch(words.red, audio.red) { onChange(audio.copy(red = it)) }
    Switch(words.dtx, audio.dtx) { onChange(audio.copy(dtx = it)) }
    Switch(words.stereo, audio.stereo) { onChange(audio.copy(stereo = it)) }
    Pick(words.audioBitrate, AUDIO_BITRATES.map { it to kbit(it.toLong()) }, audio.bitrate) {
        onChange(audio.copy(bitrate = it))
    }
}

// ── НАБОРЫ С ИМЕНАМИ ────────────────────────────────────────────────────────

/**
 * «Начать прогон» — вооружение забега, решение заказчика 2026-09-23.
 *
 * **Стоит первой на экране, и это не вкусовщина.** Она отвечает на вопрос «вмешивается ли
 * стенд в мои звонки прямо сейчас», а он важнее всех настроек ниже: до неё стенд
 * вмешивался в каждый звонок, пока включён режим в настройках, и сказать «этот звонок
 * обычный» было нечем.
 */
@Composable
private fun Arming(armed: Boolean, onArm: (Boolean) -> Unit) {
    val words = Tima.words.bench
    Section(if (armed) words.disarm else words.arm) {
        Button(
            label = if (armed) words.disarm else words.arm,
            onClick = { onArm(!armed) },
            kind = if (armed) ButtonKind.Dangerous else ButtonKind.Action,
        )
        Tertiary(if (armed) words.armAbout else words.idleAbout)
    }
}

@Composable
private fun Saving(
    preset: PublishPreset,
    presets: List<PublishPreset>,
    onChange: (PublishPreset) -> Unit,
    onSave: (PublishPreset) -> Unit,
    onForget: (String) -> Unit,
    onStep: (Int) -> Unit,
) {
    val words = Tima.words.bench
    // Имя живёт на экране, а не в пресете: человек набирает его по буквам, и каждая
    // буква, уходящая в хранилище, заводила бы там безымянный набор-черновик.
    var name by remember(preset.name) { mutableStateOf(preset.name) }

    Section(words.sectionPresets) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
        ) {
            Field(
                value = name,
                onChange = { name = it },
                hint = words.presetName,
                lineOne = true,
                modifier = Modifier.weight(1f),
            )
            Button(
                label = words.remember,
                onClick = { onSave(preset.copy(name = name.trim())) },
                kind = ButtonKind.Action,
                enabled = name.isNotBlank(),
            )
        }
        if (presets.isEmpty()) {
            Tertiary(words.noPresets)
        } else {
            // ── НОМЕР И СТРЕЛКИ ────────────────────────────────────────────
            //
            // Номер — то, чем два телефона сверяются между собой: забег идёт кольцом и
            // без сговора, разошлись — видно здесь. Стрелки тем и нужны: свести обратно
            // «Прогон 3» и «Прогон 4», пока они не померили разное, называя одинаково.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
            ) {
                Secondary(words.runAt(presets.indexOfFirst { it.name == preset.name } + 1, presets.size))
                IconButton(glyph = "\u25B2", onClick = { onStep(-1) })
                IconButton(glyph = "\u25BC", onClick = { onStep(1) })
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
                verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
            ) {
                for (saved in presets) {
                    Chip(
                        label = saved.name,
                        kind = if (saved.name == preset.name) ChipKind.Selected else ChipKind.Quiet,
                        onClick = { onChange(saved) },
                    )
                }
            }
            Button(
                label = words.forget,
                onClick = { onForget(preset.name) },
                kind = ButtonKind.Dangerous,
                enabled = presets.any { it.name == preset.name },
            )
            Tertiary(words.ringAbout)
        }
    }
}

// ── ПРОГОН ──────────────────────────────────────────────────────────────────

/**
 * Применить набор к идущему разговору — С-В5, вариант «б».
 *
 * **Кнопка есть только во время разговора**, и это не украшение: вне его применять
 * нечего — набор и так возьмётся при следующем входе в комнату. Кнопка, которая в
 * половине случаев ничего не делает, читается как поломка.
 */
@Composable
private fun Applying(inCall: Boolean, onApply: () -> Unit) {
    if (!inCall) return
    val words = Tima.words.bench
    Section(words.apply) {
        Button(label = words.apply, onClick = onApply, kind = ButtonKind.Action)
        Tertiary(words.applyAbout)
    }
}

@Composable
private fun Running(
    running: Boolean,
    samples: List<BenchSample>,
    lastFile: String?,
    skip: Int,
    onSkip: (Int) -> Unit,
    onStop: () -> Unit,
) {
    val words = Tima.words.bench
    Section(words.sectionRun) {
        // Разгон полосы отсекается настройкой, а не кнопкой: кнопку надо помнить в
        // каждом звонке, а настройку — один раз (заказчик 2026-09-21).
        Pick(words.skip, SKIPS.map { it to it.toString() }, skip, onSkip)
        Button(
            label = words.stop,
            onClick = onStop,
            kind = ButtonKind.Dangerous,
            enabled = running,
        )
        if (running) {
            Secondary(words.going(samples.lastOrNull()?.atSecond ?: 0, samples.size))
        } else {
            Tertiary(words.runsBySelf)
        }
        // Путь к файлу — не для красоты: по нему его забирают с телефона, и гадать,
        // куда он лёг, не должен никто.
        if (!running) {
            if (lastFile != null) Secondary(words.savedTo(lastFile)) else Tertiary(words.notSaved)
        }
    }
}

// ── «ЧТО ПЕРЕДАЁТСЯ» И «ЧЕМ ПЛАТИМ» — §3.2 и §3.3 ──────────────────────────

/**
 * Две колонки рядом — и различать их обязательно.
 *
 * Числа LiveKit — про медиа; числа телефона — про всё, включая служебный трафик, TURN и
 * повторы. **Расхождение между ними само по себе результат:** отдал вдвое больше, чем
 * насчитано по дорожкам, — вот и ответ на вопрос, куда девается полоса.
 */
@Composable
private fun Numbers(last: BenchSample?) {
    val words = Tima.words.bench
    Section(words.sectionTraffic) {
        val stats = last?.stats
        Line(words.up, stats?.upBitrate?.let { kbit(it) })
        Line(words.down, stats?.downBitrate?.let { kbit(it) })
        Line(words.rtt, stats?.rttMs?.let { "$it мс" })
        Line(words.lost, stats?.packetsLost?.toString())
        Line(words.codecNow, stats?.videoCodec)
        Line(
            words.encoder,
            when (stats?.hardwareEncoder) {
                true -> words.hardware
                false -> words.software
                null -> null
            },
        )
        // Все копии одной строкой через запятую (заказчик 2026-09-25): сколько их и каких —
        // видно сразу, и simulcast от одного слоя отличается без пояснений.
        Line(words.framesUp, stats?.upFrames?.takeIf { it.isNotEmpty() }?.joinToString(", "))
        Line(words.frameDown, stats?.downFrame)
        val traffic = last?.traffic
        Line(words.phoneSent, traffic?.sentBytes?.let { megabytes(it) })
        Line(words.phoneReceived, traffic?.receivedBytes?.let { megabytes(it) })
    }
    Section(words.sectionLoad) {
        val load = last?.load
        Line(words.cpu, load?.cpuPercent?.let { percent(it) })
        Line(words.memory, load?.memoryMb?.let { "$it МБ" })
        Line(words.heat, load?.temperatureC?.let { degrees(it) })
        Line(words.battery, load?.batteryPercent?.let { "$it %" })
    }
}

@Composable
private fun Runs(runs: List<BenchSummary>) {
    val words = Tima.words.bench
    if (runs.isEmpty()) return
    Section(words.sectionRuns) {
        for (run in runs) {
            Name(run.preset)
            Line(words.seconds, run.seconds.toString())
            Line(words.upAverage, run.upAverage?.let { kbit(it) })
            Line(words.upPeak, run.upPeak?.let { kbit(it) })
            Line(words.phoneSent, run.sentBytes?.let { megabytes(it) })
            Line(words.cpuAverage, run.cpuAverage?.let { percent(it) })
            Line(words.cpuPeak, run.cpuPeak?.let { percent(it) })
            Line(words.heatPeak, run.temperaturePeak?.let { degrees(it) })
            Line(words.batterySpent, run.batterySpent?.let { "$it %" })
            Line(words.codecNow, run.codec)
            Line(
                words.encoder,
                when (run.hardwareEncoder) {
                    true -> words.hardware
                    false -> words.software
                    null -> null
                },
            )
        }
    }
}

// ── МЕЛОЧЬ, ИЗ КОТОРОЙ ЭТО СОБРАНО ─────────────────────────────────────────

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2)) {
        Caption(title, fontSize = TimaType.sz3, weight = FontWeight.Bold)
        content()
    }
}

/**
 * Строка «что — сколько».
 *
 * **`null` пишется прочерком, а не нулём.** «Мерить было нечем» и «ноль» — разные
 * утверждения: на части прошивок нет датчика температуры, и ноль градусов в отчёте
 * читался бы как измерение.
 */
@Composable
private fun Line(label: String, value: String?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Secondary(label)
        Caption(
            value ?: "—",
            fontSize = TimaType.sz5,
            weight = FontWeight.Bold,
            color = if (value == null) Tima.colors.text3 else Tima.colors.text,
        )
    }
}

/** Ряд взаимоисключающих значений. Выбранное — залитым чипом. */
@Composable
private fun <T> Pick(label: String, options: List<Pair<T, String>>, chosen: T, onPick: (T) -> Unit) {
    Caption(label)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for ((value, text) in options) {
            Chip(
                label = text,
                kind = if (value == chosen) ChipKind.Selected else ChipKind.Quiet,
                onClick = { onPick(value) },
            )
        }
    }
}

/** Двузначная ручка. Тем же чипом, что и остальные: рядом они не должны спорить видом. */
@Composable
private fun Switch(label: String, on: Boolean, onSwitch: (Boolean) -> Unit) {
    val words = Tima.words.bench
    Pick(label, listOf(true to words.on, false to words.off), on, onSwitch)
}

private fun VideoCodec.label(): String = when (this) {
    VideoCodec.H264 -> "H.264"
    VideoCodec.VP9 -> "VP9"
    VideoCodec.H265 -> "H.265"
    VideoCodec.VP8 -> "VP8"
}

/** Какой режим слоёв оставить, когда кодек разучился в SVC. */
/**
 * Какие слои у кодека бывают на самом деле.
 *
 * SVC — только у VP9. **Simulcast у VP9 не бывает**: SDK для VP9 собирает одну
 * SVC-кодировку и `simulcast` не смотрит, так что «VP9 simulcast» мерил SVC под чужим
 * именем (убран из выбора решением заказчика 2026-09-25).
 */
private fun layersOf(codec: VideoCodec): Set<LayerMode> =
    if (codec.svcCapable) setOf(LayerMode.Single, LayerMode.Svc) else setOf(LayerMode.Single, LayerMode.Simulcast)

/**
 * Слои, приведённые к кодеку: при смене кодека и для пресетов, сохранённых раньше.
 * Недоступное становится ближайшим честным: SVC ↔ simulcast — оба «несколько качеств».
 * Сохранённый «VP9 simulcast» так и показывается тем, чем он в сети и был, — SVC.
 */
private fun layersFor(codec: VideoCodec, layers: LayerMode): LayerMode = when {
    layers in layersOf(codec) -> layers
    layers == LayerMode.Svc -> LayerMode.Simulcast
    else -> LayerMode.Svc
}

private val SCALABILITY = listOf("L3T3_KEY", "L3T3", "L1T3")

/**
 * Размеры кадра — **все кратны шестнадцати**.
 *
 * Это не эстетика: аппаратный кодер H.264 у realme (Unisoc) на высоте, не кратной
 * шестнадцати, выдаёт полосатую картинку — и видно её не себе, а собеседнику
 * (заказчик 2026-09-20). Привычные 640×360 и 854×480 из списка убраны именно поэтому.
 */
private val SIZES = listOf(320 to 240, 640 to 480, 960 to 720, 1280 to 720)

private val FRAMES = listOf(7, 15, 24, 30)

private val BITRATES = listOf(300_000, 500_000, 800_000, 1_500_000, 2_500_000)

private val AUDIO_BITRATES = listOf(16_000, 24_000, 32_000, 64_000)

/** Сколько секунд разгона не учитывать. Ноль — учитывать всё, и это тоже выбор. */
private val SKIPS = listOf(0, 3, 5, 10, 15)

// Свои, а не платформенные: `String.format` в общем коде нет, а округление тут нужно
// грубое — числа дышат, и лишние знаки после запятой только мешают их читать.

internal fun kbit(bits: Long): String =
    if (bits >= 1_000_000) "${bits / 100_000 / 10.0} Мбит/с" else "${bits / 1000} кбит/с"

internal fun megabytes(bytes: Long): String = "${bytes / 100_000 / 10.0} МБ"

internal fun percent(value: Double): String = "${(value * 10).toInt() / 10.0} %"

internal fun degrees(value: Double): String = "${(value * 10).toInt() / 10.0} °C"

/**
 * Пункт настроек «Испытательный режим звонков» — С7.
 *
 * ── ЗАЧЕМ ЗДЕСЬ СКАЗАНО, КАКОЙ НАБОР ВЫБРАН ────────────────────────────────
 *
 * Это ответ на С-В2. Человек видит «режим выключен» и не видит, что его звонки идут
 * VP9 с SVC, — а они идут: выключение флага уносит обвязку, но не выбор. Строка с именем
 * набора стоит **рядом с выключателем**, и стоит она там всегда, а не только при
 * включённом флаге: именно выключенное состояние и вводит в заблуждение.
 */
@Composable
fun CallBenchSwitch(
    on: Boolean,
    preset: String,
    onSwitch: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val words = Tima.words.bench
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Tima.colors.surface)
            .verticalScroll(rememberScrollState())
            .padding(TimaSpacing.about3),
        verticalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
    ) {
        Caption(words.flag, fontSize = TimaType.sz3, weight = FontWeight.Bold)
        Secondary(words.flagAbout)
        Pick(words.flag, listOf(true to words.on, false to words.off), on, onSwitch)
        Tertiary(words.presetNow(preset))
    }
}
