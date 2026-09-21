package io.tima.core.call

import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Отчёт о прогоне — файл, который заберут с телефона и по которому потом будут решать.
 *
 * Проверяется не вёрстка, а **три вещи, без которых файл бесполезен**: имя набора и вид
 * кодера в нём есть; несобранное число — прочерк, а не ноль; имя файла латиницей, как бы
 * человек ни назвал набор.
 */
class BenchReportTest {

    private val at = Instant.parse("2026-09-21T12:30:00Z")

    @Test
    fun имя_файла_латиницей_даже_у_русского_набора() {
        // Имя файла увидит `adb`, консоль Windows и `git status`. Кириллица в нём ломает
        // всех троих (решение заказчика 2026-09-06), а набор человек зовёт по-русски —
        // поэтому имени набора в имени файла нет вовсе, оно внутри.
        val name = benchFileName("RMX3269", at)

        assertTrue(name.all { it.code < 128 }, "в имени файла не латиница: $name")
        assertTrue(name.endsWith(".md"), "имя без расширения: $name")
        assertTrue(name.contains("RMX3269"), "в имени нет телефона: $name")
    }

    @Test
    fun телефон_без_латинского_имени_не_ломает_имя_файла() {
        // Модель приходит от системы, и на телефоне с локализованной прошивкой в ней
        // бывает что угодно. Пустое имя файла хуже некрасивого.
        val name = benchFileName("Телефон №1", at)

        assertTrue(name.all { it.code < 128 }, "кириллица просочилась: $name")
        assertTrue(name.length > 4, "имя схлопнулось в одно расширение: $name")
    }

    @Test
    fun в_отчёте_есть_набор_и_вид_кодера() {
        // Без имени набора отчёт не с чем сравнивать; без вида кодера числа нечитаемы —
        // разница между H.264 на Samsung и на Redmi может оказаться разницей двух
        // реализаций, а не настроек (ПЛАН-СТЕНДА §5а).
        val preset = PublishPreset(name = "VP9 один слой", video = VideoPreset(codec = VideoCodec.VP9))
        val summary = summarize(
            preset,
            listOf(BenchSample(3, stats = CallStats(upBitrate = 500_000, videoCodec = "VP9", hardwareEncoder = false))),
        )

        val text = benchReport(summary, emptyList(), preset, "RMX3269", at)

        assertTrue(text.contains("VP9 один слой"), "в отчёте нет имени набора")
        assertTrue(text.contains("программный"), "в отчёте не сказано, чем кодировали")
        assertTrue(text.contains("RMX3269"), "в отчёте нет телефона")
    }

    @Test
    fun несобранное_число_пишется_прочерком() {
        // «Мерить было нечем» и «ноль» — разные утверждения. Ноль в отчёте прочитают как
        // измерение, и через полгода по нему сделают вывод о кодеке.
        val preset = PublishPreset(name = "проба")
        val text = benchReport(summarize(preset, emptyList()), emptyList(), preset, "phone", at)

        assertTrue(text.contains("| Нагрев, потолок °C | — |"), "несобранное число не прочерк:\n$text")
        assertTrue(!text.contains("| Нагрев, потолок °C | 0"), "несобранное число стало нулём")
    }

    @Test
    fun отсчёты_попадают_в_отчёт_построчно() {
        // По средним не видно провалов. Ради них отсчёты и хранятся по одному.
        val preset = PublishPreset(name = "проба")
        val samples = listOf(
            BenchSample(1, stats = CallStats(upBitrate = 100_000)),
            BenchSample(2, stats = CallStats(upBitrate = 900_000)),
        )

        val text = benchReport(summarize(preset, samples), samples, preset, "phone", at)

        assertEquals(2, text.lines().count { it.startsWith("| 1 |") || it.startsWith("| 2 |") })
    }
}
