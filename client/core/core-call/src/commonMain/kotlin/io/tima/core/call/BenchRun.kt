package io.tima.core.call

/**
 * Прогон стенда — С4, С5 и С8 в [ПЛАН-СТЕНДА-ЗВОНКОВ](../../../../../../../../doc_mig/ПЛАН-СТЕНДА-ЗВОНКОВ.md).
 *
 * ── ЧТО ТАКОЕ ПРОГОН И ПОЧЕМУ ЭТО НЕ «ЗВОНОК» ──────────────────────────────
 *
 * Звонок — то, что происходит; прогон — то, что **меряется**. Они не совпадают: замер
 * начинают через несколько секунд после соединения (первые секунды занимает разгон полосы
 * и портят среднее — ПЛАН-СТЕНДА §6) и останавливают раньше, чем кладут трубку. Один
 * звонок может дать два прогона, а может ни одного.
 *
 * ── ПОЧЕМУ ОТСЧЁТЫ ХРАНЯТСЯ ЦЕЛИКОМ, А НЕ СРАЗУ СВОРАЧИВАЮТСЯ В СРЕДНЕЕ ────
 *
 * Среднее прячет ровно то, ради чего стенд заведён. Программный VP9 может держать
 * приличный средний процессор и дважды за минуту упереться в потолок — по среднему это
 * выглядит как «дороже на четверть», а на телефоне выглядит как дёрганая картинка.
 * Поэтому отсчёт за отсчётом, а свёртка — отдельно и поверх.
 */
data class BenchSample(
    /** Сколько секунд идёт прогон к моменту отсчёта. */
    val atSecond: Int,
    val stats: CallStats? = null,
    val load: PhoneLoad? = null,
    val traffic: PhoneTraffic? = null,
)

/**
 * Свёртка прогона — то, что уходит в отчёт.
 *
 * **Байты считаются разницей, а не суммой.** Счётчик трафика у телефона общий на процесс
 * и ведётся с его запуска: сложить отсчёты значило бы сложить всё, что приложение
 * передало за день. Разница между первым и последним отсчётом — это и есть прогон.
 *
 * `null` в любом поле означает «мерить было нечем», и это честный ответ: на части
 * прошивок нет датчика температуры, на части — счётчика трафика по процессу.
 */
data class BenchSummary(
    val preset: String,
    val seconds: Int,
    val samples: Int,
    /** Среднее и потолок исходящего битрейта, бит/с. */
    val upAverage: Long? = null,
    val upPeak: Long? = null,
    val downAverage: Long? = null,
    /** Байты телефона за прогон — со служебным трафиком, TURN и повторами. */
    val sentBytes: Long? = null,
    val receivedBytes: Long? = null,
    val cpuAverage: Double? = null,
    val cpuPeak: Double? = null,
    val temperatureStart: Double? = null,
    val temperaturePeak: Double? = null,
    /** Сколько процентов заряда ушло за прогон. */
    val batterySpent: Int? = null,
    /** Сколько мА·ч ушло за прогон — по счётчику батареи. `null` — на зарядке или нечем. */
    val mahSpent: Int? = null,
    /** Средний ток за прогон, мА. `null` — на зарядке или нечем. */
    val currentAverageMa: Int? = null,
    /**
     * Был ли телефон на зарядке **хоть в одном отсчёте**. Тогда расход не считается вовсе:
     * числа такого прогона нельзя сравнивать с остальными, и отчёт говорит это прямо.
     */
    val onCharger: Boolean = false,
    val memoryPeakMb: Int? = null,
    /** Чем кодировалось на самом деле и железом ли. Без этого числа нечитаемы (§5а). */
    val codec: String? = null,
    val hardwareEncoder: Boolean? = null,
    val rttAverageMs: Int? = null,
    val packetsLost: Long? = null,
)

/**
 * Свернуть прогон.
 *
 * Пустой список даёт свёртку с нулями и `null`-ами, а не исключение: «прогон, в котором
 * не успели снять ни одного отсчёта» — обычный исход короткого звонка, и он должен быть
 * виден как прогон без чисел, а не пропасть.
 */
fun summarize(preset: PublishPreset, samples: List<BenchSample>): BenchSummary {
    val ups = samples.mapNotNull { it.stats?.upBitrate }.filter { it > 0 }
    val downs = samples.mapNotNull { it.stats?.downBitrate }.filter { it > 0 }
    val cpus = samples.mapNotNull { it.load?.cpuPercent }
    val temperatures = samples.mapNotNull { it.load?.temperatureC }
    val batteries = samples.mapNotNull { it.load?.batteryPercent }
    val charges = samples.mapNotNull { it.load?.chargeUah }
    val currents = samples.mapNotNull { it.load?.currentMa }
    val onCharger = samples.any { it.load?.charging == true }
    val traffics = samples.mapNotNull { it.traffic }
    val rtts = samples.mapNotNull { it.stats?.rttMs }

    return BenchSummary(
        preset = preset.name,
        seconds = samples.lastOrNull()?.atSecond ?: 0,
        samples = samples.size,
        upAverage = ups.averageBits()?.toLong(),
        upPeak = ups.maxOrNull(),
        downAverage = downs.averageBits()?.toLong(),
        sentBytes = traffics.spread { it.sentBytes },
        receivedBytes = traffics.spread { it.receivedBytes },
        cpuAverage = cpus.averagePercent(),
        cpuPeak = cpus.maxOrNull(),
        temperatureStart = temperatures.firstOrNull(),
        temperaturePeak = temperatures.maxOrNull(),
        // Заряд только падает, поэтому первый минус последний. Отрицательное значит, что
        // телефон стоял на зарядке, — и тогда числу цена ноль, о чём и говорит null.
        batterySpent = if (batteries.size >= 2 && !onCharger) {
            (batteries.first() - batteries.last()).takeIf { it >= 0 }
        } else {
            null
        },
        mahSpent = if (charges.size >= 2 && !onCharger) {
            ((charges.first() - charges.last()) / 1000).toInt().takeIf { it >= 0 }
        } else {
            null
        },
        currentAverageMa = if (currents.isNotEmpty() && !onCharger) currents.average().toInt() else null,
        onCharger = onCharger,
        memoryPeakMb = samples.mapNotNull { it.load?.memoryMb }.maxOrNull(),
        // Кодек и кодер берутся последними известными: в начале прогона дорожка ещё не
        // поднялась, и первые отсчёты про них не знают ничего.
        codec = samples.lastNotNullOf { it.stats?.videoCodec },
        hardwareEncoder = samples.lastNotNullOf { it.stats?.hardwareEncoder },
        rttAverageMs = rtts.averageMs()?.toInt(),
        packetsLost = samples.lastOrNull()?.stats?.packetsLost,
    )
}

// Имена разные, а не перегрузка: на JVM типы списков стираются, и три `averageOrNull`
// стали бы одной сигнатурой. Отлавливается это только при сборке jvm-таргета — то есть
// позже и дороже, чем стоит экономия на имени.
private fun List<Long>.averageBits(): Double? = if (isEmpty()) null else average()

private fun List<Double>.averagePercent(): Double? = if (isEmpty()) null else average()

private fun List<Int>.averageMs(): Double? = if (isEmpty()) null else average()

/** Разница между последним и первым — сколько прибавилось за прогон. */
private fun List<PhoneTraffic>.spread(of: (PhoneTraffic) -> Long): Long? =
    if (size < 2) null else of(last()) - of(first())

private fun <T : Any> List<BenchSample>.lastNotNullOf(of: (BenchSample) -> T?): T? {
    for (i in indices.reversed()) {
        val value = of(this[i])
        if (value != null) return value
    }
    return null
}
