package io.tima.core.call

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Отчёт о прогоне — С8 в [ПЛАН-СТЕНДА-ЗВОНКОВ](../../../../../../../../doc_mig/ПЛАН-СТЕНДА-ЗВОНКОВ.md).
 *
 * ── ПОЧЕМУ ФАЙЛ, А НЕ ЭКРАН ────────────────────────────────────────────────
 *
 * Числа на экране живут до следующего прогона. Сравнивать нужно шесть прогонов на трёх
 * телефонах — восемнадцать наборов чисел, и переписывать их глазами значит потерять
 * половину и ошибиться в остальных.
 *
 * ── ПОЧЕМУ ОДИН ФАЙЛ НА ЗВОНОК, А НЕ ОДИН НА ВСЁ ───────────────────────────
 *
 * Решение заказчика 2026-09-21: «каждый отдельный звонок — отдельный файл». Общий файл
 * пришлось бы дописывать, а дописывание с телефона, который в любой момент могут убить,
 * кончается обрезанным файлом. Отдельный пишется целиком и один раз.
 *
 * ── ПОЧЕМУ ВНУТРИ И СВЁРТКА, И ОТСЧЁТЫ ─────────────────────────────────────
 *
 * По средним не видно провалов, по отсчётам не видно итога. Вместе они отвечают и на
 * «сколько это стоило», и на «когда стало плохо».
 */
fun benchReport(
    summary: BenchSummary,
    samples: List<BenchSample>,
    preset: PublishPreset,
    device: String,
    at: Instant = Clock.System.now(),
): String = buildString {
    appendLine("# Прогон " + stamp(at) + " · " + device)
    appendLine()
    appendLine("Набор **" + summary.preset + "**, " + summary.seconds + " с, отсчётов " + summary.samples + ".")
    appendLine()

    appendLine("## Чем звонили")
    appendLine()
    appendLine("| Что | Значение |")
    appendLine("|---|---|")
    row("Кодек", preset.video.codec.name)
    row("Запасной кодек", preset.video.backup?.name ?: "нет")
    row("Слои", preset.video.layers.name)
    if (preset.video.layers == LayerMode.Svc) row("Режим SVC", preset.video.scalability)
    row("Кадр", "" + preset.video.width + "×" + preset.video.height)
    row("Кадров в секунду", preset.video.fps.toString())
    row("Верхний битрейт", preset.video.bitrate.toString())
    row("Чем жертвовать", preset.video.degradation.name)
    row("Dynacast", yesNo(preset.video.dynacast))
    row("Adaptive Stream", yesNo(preset.video.adaptiveStream))
    row("RED", yesNo(preset.audio.red))
    row("DTX", yesNo(preset.audio.dtx))
    row("Стерео", yesNo(preset.audio.stereo))
    row("Битрейт звука", preset.audio.bitrate.toString())
    appendLine()

    appendLine("## Что получилось")
    appendLine()
    appendLine("| Что | Значение |")
    appendLine("|---|---|")
    row("Кодек на самом деле", summary.codec)
    // Вид кодера — обязательная строка отчёта (ПЛАН-СТЕНДА §5а): разница между H.264 на
    // Samsung и на Redmi может оказаться разницей двух реализаций, а не настроек.
    row("Кодер", summary.hardwareEncoder?.let { if (it) "аппаратный" else "программный" })
    row("Вверх, среднее (бит/с)", summary.upAverage?.toString())
    row("Вверх, потолок (бит/с)", summary.upPeak?.toString())
    row("Вниз, среднее (бит/с)", summary.downAverage?.toString())
    row("Телефон отдал (байт)", summary.sentBytes?.toString())
    row("Телефон принял (байт)", summary.receivedBytes?.toString())
    row("Оборот пакета, мс", summary.rttAverageMs?.toString())
    row("Потеряно пакетов", summary.packetsLost?.toString())
    row("Процессор, среднее %", summary.cpuAverage?.let { round(it) })
    row("Процессор, потолок %", summary.cpuPeak?.let { round(it) })
    row("Память, потолок МБ", summary.memoryPeakMb?.toString())
    row("Нагрев в начале, °C", summary.temperatureStart?.let { round(it) })
    row("Нагрев, потолок °C", summary.temperaturePeak?.let { round(it) })
    row("Ушло заряда, %", summary.batterySpent?.toString())
    appendLine()

    appendLine("## Отсчёты")
    appendLine()
    appendLine("| с | вверх | вниз | ЦП % | °C | заряд | отдано | принято |")
    appendLine("|---|---|---|---|---|---|---|---|")
    for (sample in samples) {
        appendLine(
            "| " + sample.atSecond +
                " | " + (sample.stats?.upBitrate?.toString() ?: "—") +
                " | " + (sample.stats?.downBitrate?.toString() ?: "—") +
                " | " + (sample.load?.cpuPercent?.let { round(it) } ?: "—") +
                " | " + (sample.load?.temperatureC?.let { round(it) } ?: "—") +
                " | " + (sample.load?.batteryPercent?.toString() ?: "—") +
                " | " + (sample.traffic?.sentBytes?.toString() ?: "—") +
                " | " + (sample.traffic?.receivedBytes?.toString() ?: "—") +
                " |",
        )
    }
}

/**
 * Имя файла — **латиницей и без имени набора**.
 *
 * Набор человек зовёт по-русски («VP9 один слой»), а имена того, что приложение кладёт на
 * диск, обязаны быть латиницей (решение заказчика 2026-09-06): кириллический путь ломается
 * о кодировку консоли Windows, о `git status` с восьмеричными кодами и об `adb`, которым
 * этот файл будут забирать. Набор назван внутри файла, и там ему ничто не грозит.
 */
fun benchFileName(device: String, at: Instant = Clock.System.now()): String =
    stamp(at).replace(" ", "-").replace(":", "") + "-" + latin(device) + ".md"

/** Время отметкой: `2026-09-21 15:30`. Местное — прогон ведёт человек, а не сервер. */
private fun stamp(at: Instant): String {
    val local = at.toLocalDateTime(TimeZone.currentSystemDefault())
    return "" + local.year + "-" + two(local.monthNumber) + "-" + two(local.dayOfMonth) +
        " " + two(local.hour) + ":" + two(local.minute)
}

private fun two(value: Int): String = if (value < 10) "0$value" else value.toString()

/** Всё, что не латиница, цифра и дефис, — в дефис. Имя файла должно пережить любой путь. */
private fun latin(text: String): String =
    text.map { if (it.isLetterOrDigit() && it.code < 128) it else '-' }
        .joinToString("")
        .trim('-')
        .ifBlank { "phone" }

private fun round(value: Double): String = ((value * 10).toInt() / 10.0).toString()

private fun yesNo(on: Boolean): String = if (on) "да" else "нет"

/** Строка таблицы. `null` — прочерк: «мерить было нечем» не то же самое, что ноль. */
private fun StringBuilder.row(label: String, value: String?) {
    appendLine("| " + label + " | " + (value ?: "—") + " |")
}

/**
 * Куда телефон кладёт отчёт.
 *
 * `null` — платформа не умеет или не должна: на ПК и iOS стенда нет вовсе.
 *
 * @return путь, по которому файл лёг, — его показывает экран, чтобы забирающий знал, что
 *   искать, и не гадал.
 */
expect fun saveBenchReport(fileName: String, text: String): String?

/** Модель телефона. Три телефона стенда различаются только так. */
expect fun phoneModel(): String
