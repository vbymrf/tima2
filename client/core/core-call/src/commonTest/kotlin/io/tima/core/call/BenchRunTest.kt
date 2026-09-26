package io.tima.core.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Свёртка прогона — то, из чего потом читают числа забега.
 *
 * Проверяется не арифметика, а **три решения, каждое из которых легко потерять**: байты
 * считаются разницей, а не суммой; «мерить было нечем» отличается от нуля; кодек берётся
 * последний известный, а не первый.
 */
class BenchRunTest {

    private val preset = PublishPreset(name = "проба")

    @Test
    fun байты_телефона_считаются_разницей_а_не_суммой() {
        // Счётчик трафика у телефона общий на процесс и ведётся с его запуска. Сложить
        // отсчёты значило бы сложить всё, что приложение передало за день, — и прогон на
        // минуту показал бы сотни мегабайт.
        val прогон = listOf(
            BenchSample(atSecond = 0, traffic = PhoneTraffic(sentBytes = 1_000_000, receivedBytes = 5_000_000)),
            BenchSample(atSecond = 30, traffic = PhoneTraffic(sentBytes = 1_400_000, receivedBytes = 5_300_000)),
            BenchSample(atSecond = 60, traffic = PhoneTraffic(sentBytes = 1_900_000, receivedBytes = 5_700_000)),
        )

        val свёртка = summarize(preset, прогон)

        assertEquals(900_000, свёртка.sentBytes, "исходящие байты прогона")
        assertEquals(700_000, свёртка.receivedBytes, "входящие байты прогона")
    }

    @Test
    fun одного_отсчёта_для_трафика_мало_и_это_не_ноль() {
        // Одна точка не даёт разницы. Ноль здесь был бы ложью: «ничего не передано» и
        // «мерить не с чем» — разные утверждения, и второе честнее.
        val свёртка = summarize(preset, listOf(BenchSample(0, traffic = PhoneTraffic(10, 20))))

        assertNull(свёртка.sentBytes)
        assertNull(свёртка.receivedBytes)
    }

    @Test
    fun кодек_и_кодер_берутся_последние_известные() {
        // В начале прогона дорожка ещё не поднялась, и первые отсчёты не знают ни кодека,
        // ни кодера. Взять первый непустой значило бы иногда не взять ничего.
        val прогон = listOf(
            BenchSample(0, stats = CallStats()),
            BenchSample(3, stats = CallStats(videoCodec = "VP9", hardwareEncoder = false)),
            BenchSample(6, stats = CallStats()),
        )

        val свёртка = summarize(preset, прогон)

        assertEquals("VP9", свёртка.codec)
        assertEquals(false, свёртка.hardwareEncoder)
    }

    @Test
    fun заряд_на_зарядке_не_считается() {
        // Телефон на зарядке прибавляет процент, и «расход» выходит отрицательным. Такому
        // числу цена ноль, и показывать его нельзя: по нему прочитают, что кодек экономит
        // батарею.
        val прогон = listOf(
            BenchSample(0, load = PhoneLoad(batteryPercent = 70)),
            BenchSample(60, load = PhoneLoad(batteryPercent = 73)),
        )

        assertNull(summarize(preset, прогон).batterySpent)
    }

    @Test
    fun мач_считаются_из_тока_а_не_по_счётчику() {
        // Счётчик батареи здесь нарочно врёт (шаг realme — 4–5 мА·ч): верить надо току.
        // 400 и 500 мА за 60 с — трапеция 450 мА × 60 с = 7,5 мА·ч.
        val прогон = listOf(
            BenchSample(0, load = PhoneLoad(chargeUah = 3_000_000, currentMa = 400, charging = false)),
            BenchSample(60, load = PhoneLoad(chargeUah = 3_000_000, currentMa = 500, charging = false)),
        )
        val свёртка = summarize(preset, прогон)
        assertEquals(7.5, свёртка.mahSpent)
        assertEquals(450, свёртка.currentAverageMa)
        assertFalse(свёртка.onCharger)
    }

    @Test
    fun выпавший_отсчёт_тока_не_приписывает_чужие_секунды() {
        // Ток есть на 0-й и 10-й секунде, на 20-й его нет: считается только первый
        // отрезок — 600 мА × 10 с ≈ 1,6 мА·ч, а не 600 мА × 20 с.
        val прогон = listOf(
            BenchSample(0, load = PhoneLoad(currentMa = 600)),
            BenchSample(10, load = PhoneLoad(currentMa = 600)),
            BenchSample(20, load = PhoneLoad()),
        )
        assertEquals(1.6, summarize(preset, прогон).mahSpent)
    }

    @Test
    fun без_тока_мач_нет() {
        val прогон = listOf(BenchSample(0, load = PhoneLoad()), BenchSample(60, load = PhoneLoad()))
        assertNull(summarize(preset, прогон).mahSpent)
    }

    @Test
    fun на_зарядке_расход_не_считается_а_прогон_помечен() {
        // Хоть один отсчёт на проводе — и весь расход прогона недостоверен.
        val прогон = listOf(
            BenchSample(0, load = PhoneLoad(batteryPercent = 70, chargeUah = 3_000_000, currentMa = 400, charging = false)),
            BenchSample(60, load = PhoneLoad(batteryPercent = 69, chargeUah = 2_990_000, currentMa = 900, charging = true)),
        )
        val свёртка = summarize(preset, прогон)
        assertTrue(свёртка.onCharger)
        assertNull(свёртка.mahSpent)
        assertNull(свёртка.currentAverageMa)
        assertNull(свёртка.batterySpent)
    }

    @Test
    fun единицы_тока_и_счётчика_угадываются_по_величине() {
        assertEquals(350, currentToMilliAmps(-350_000), "мкА со знаком разряда")
        assertEquals(350, currentToMilliAmps(350_000), "мкА с обратным знаком")
        assertEquals(350, currentToMilliAmps(-350), "прошивка отдаёт мА")
        assertNull(currentToMilliAmps(Int.MIN_VALUE))
        assertNull(currentToMilliAmps(0))
        assertEquals(3_000_000L, chargeToMicroAmpHours(3_000_000))
        assertEquals(3_000_000L, chargeToMicroAmpHours(3_000), "прошивка отдаёт мА·ч")
        assertNull(chargeToMicroAmpHours(Int.MIN_VALUE))
    }

    @Test
    fun пустой_прогон_сворачивается_а_не_падает() {
        // Короткий звонок — обычное дело, и прогон без единого отсчёта должен быть виден
        // как прогон без чисел, а не пропасть или уронить экран.
        val свёртка = summarize(preset, emptyList())

        assertEquals("проба", свёртка.preset)
        assertEquals(0, свёртка.samples)
        assertNull(свёртка.upAverage)
    }

    @Test
    fun нулевой_битрейт_в_среднее_не_идёт() {
        // Первый отсчёт сравнивать не с чем, и движок честно отдаёт ноль. Приняв его за
        // измерение, среднее занижалось бы тем сильнее, чем короче прогон.
        val прогон = listOf(
            BenchSample(0, stats = CallStats(upBitrate = 0)),
            BenchSample(3, stats = CallStats(upBitrate = 800_000)),
            BenchSample(6, stats = CallStats(upBitrate = 820_000)),
        )

        val свёртка = summarize(preset, прогон)

        assertTrue(свёртка.upAverage!! > 700_000, "среднее занижено нулём: ${свёртка.upAverage}")
        assertEquals(820_000, свёртка.upPeak)
    }
}
