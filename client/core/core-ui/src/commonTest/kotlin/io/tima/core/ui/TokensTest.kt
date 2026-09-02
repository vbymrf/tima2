package io.tima.core.ui

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Токены и контраст.
 *
 * **Главное здесь — не «цвет равен цвету».** Главное — что названные в макете
 * отношения контраста стали числами, которые проверяются. Макет прямо говорит, где
 * решение принято ниже порога и какой у него ценник; пока это живёт только в тексте,
 * любая правка палитры меняет цену молча.
 */
class TokensTest {

    private fun close(expected: Double, received: Double, tolerance: Double = 0.02) {
        assertTrue(
            abs(expected - received) <= tolerance,
            "ожидалось $expected : 1, получено ${(received * 100).toInt() / 100.0} : 1",
        )
    }

    // ── отношения, названные в макете ────────────────────────────────────────

    @Test
    fun в_светлой_теме_текст_на_зелёном_белый_и_цена_принята() {
        // Решение заказчика 2026-09-02, отменяет чёрный от 2026-08-23: «всё, что на
        // зелёном фоне сейчас во вкладках и навигационное, и текст окон в шапке; по
        // сути как в макете».
        //
        // Тест не спорит, а держит цену на виду: 2,08 : 1 при пороге 4,5. Прежнее
        // решение брало 10,08, и доводом были мелкие подписи — «E2E», галочки, чипы.
        // Если однажды это число вырастет, значит палитру поменяли, и решение надо
        // перечитать, а не подогнать проверку.
        val ratio = TimaContrast.ratio(TimaColors.light.onAccent, TimaColors.light.navigation)
        close(2.08, ratio)
        assertTrue(ratio < TimaContrast.TEXT_THRESHOLD)

        // На зелёном «подтверждено» (метка E2E) белый читается ещё хуже салатового:
        // это самое дорогое место решения, и число названо, чтобы оно не потерялось.
        close(2.75, TimaContrast.ratio(TimaColors.light.onAccent, TimaColors.light.confirmed))
    }

    @Test
    fun в_тёмной_теме_текст_на_зелёном_белый_и_цена_принята() {
        // Тоже решение заказчика, и обратное по цвету: в тёмной теме весь текст белый,
        // и делать зелёную заливку единственным исключением значило бы вводить правило
        // ради одного места. Цена — 2,08 : 1, и тест держит её на виду, а не спорит.
        val ratio = TimaContrast.ratio(TimaColors.dark.onAccent, TimaColors.dark.navigation)
        close(2.08, ratio)
        assertTrue(
            ratio < TimaContrast.TEXT_THRESHOLD,
            "если это однажды возьмёт порог — значит палитру поменяли, и решение надо перечитать",
        )
    }

    @Test
    fun текст_на_зелёном_в_темах_одинаковый_и_это_решение() {
        // Свели явно — 2026-09-02. До этого светлая несла чёрный, тёмная белый, и
        // одна и та же салатовая заливка была единственным местом палитры, где цвет
        // текста зависел от темы. Теперь заливка одна, значит и текст на ней один.
        //
        // Обратная проверка стояла здесь же и требовала различия. Её снятие — не
        // послабление: условие заменено на противоположное, и развести темы обратно
        // молча по-прежнему нельзя.
        assertEquals(TimaColors.light.onAccent, TimaColors.dark.onAccent)
    }

    @Test
    fun салатовый_текст_по_функциональной_подложке_нечитаем_и_поэтому_не_применяется() {
        // 1,95 : 1 — не берётся даже порог крупного текста (3 : 1). Это и есть причина,
        // по которой название окна НЕ набирают салатовым: в плашке оно белое по заливке,
        // в подокне — обычным текстом. Число держим, чтобы соблазн «а давайте зелёным,
        // это же цвет навигации» упирался в проверку, а не в чью-то память.
        val ratio = TimaContrast.ratio(TimaColors.light.navigation, TimaColors.light.functional)
        close(1.95, ratio)
        assertTrue(ratio < TimaContrast.ПОРОГ_КРУПНОГО)
    }

    @Test
    fun в_тёмной_теме_салатовый_по_подложке_читался_бы_хорошо() {
        // 8,99 : 1. Асимметрия объясняет, почему правило «зелёным нельзя» относится к
        // светлой теме, а не к цвету как таковому.
        val background = TimaContrast.overlay(TimaColors.dark.functional, TimaColors.dark.surface)
        close(8.99, TimaContrast.ratio(TimaColors.dark.navigation, background), tolerance = 0.15)
    }

    @Test
    fun название_окна_в_подокне_набрано_обычным_текстом_и_читается() {
        // У подокна плашки нет, и название там — var(--текст) по функциональной подложке.
        for ((name, theme) in listOf("светлая" to TimaColors.light, "тёмная" to TimaColors.dark)) {
            val background = TimaContrast.overlay(theme.functional, theme.surface)
            val ratio = TimaContrast.ratio(theme.text, background)
            assertTrue(
                ratio >= TimaContrast.TEXT_THRESHOLD,
                "$name: название в подокне обязано проходить порог, а даёт $ratio : 1",
            )
        }
    }

    @Test
    fun янтарь_сохранил_чёрный_текст_и_это_видно_по_числу() {
        close(12.32, TimaContrast.ratio(Color.Black, TimaColors.light.activity))
    }

    @Test
    fun фоны_сообщений_читаются_в_обеих_темах() {
        // Светлая: мои 11,42 : 1, автор 21 : 1 — оба с чёрным текстом.
        close(11.42, TimaContrast.ratio(TimaColors.light.text, TimaColors.light.my))
        close(21.0, TimaContrast.ratio(TimaColors.light.text, TimaColors.light.author))

        // Тёмная: те же роли, значения опущены по серой лестнице, текст белый.
        close(10.86, TimaContrast.ratio(TimaColors.dark.text, TimaColors.dark.author), tolerance = 0.1)
        close(17.40, TimaContrast.ratio(TimaColors.dark.text, TimaColors.dark.my), tolerance = 0.1)
    }

    @Test
    fun основной_текст_на_поверхности_проходит_порог_в_обеих_темах() {
        for ((name, theme) in listOf("светлая" to TimaColors.light, "тёмная" to TimaColors.dark)) {
            val ratio = TimaContrast.ratio(theme.text, theme.surface)
            assertTrue(
                ratio >= TimaContrast.TEXT_THRESHOLD,
                "$name: основной текст обязан проходить порог, а даёт $ratio : 1",
            )
        }
    }

    // ── отношения тем между собой ────────────────────────────────────────────

    @Test
    fun автор_светлее_меня_в_обеих_темах() {
        // Отношение выбрано осознанно и сохранено при переносе в тёмную: «автор светлее
        // меня». Если однажды поменяется — пусть меняется явно, а не как побочный эффект
        // правки серой лестницы.
        assertTrue(
            brightness(TimaColors.light.author) > brightness(TimaColors.light.my),
            "светлая: автор обязан быть светлее моих",
        )
        assertTrue(
            brightness(TimaColors.dark.author) > brightness(TimaColors.dark.my),
            "тёмная: отношение обязано сохраниться",
        )
    }

    @Test
    fun цвета_кода_в_темах_совпадают() {
        // Салатовый, янтарь и зелёный работают заливкой в обеих темах, и перекрашивать
        // их не пришлось. Это тоже решение, и оно держится тестом.
        assertEquals(TimaColors.light.navigation, TimaColors.dark.navigation)
        assertEquals(TimaColors.light.activity, TimaColors.dark.activity)
        assertEquals(TimaColors.light.confirmed, TimaColors.dark.confirmed)
    }

    @Test
    fun поверхность_и_текст_в_темах_обратны_друг_другу() {
        assertEquals(TimaColors.light.surface, TimaColors.dark.text)
        assertEquals(TimaColors.light.text, TimaColors.dark.surface)
    }

    @Test
    fun эмоции_нецветные_в_обеих_темах() {
        // Цвет занят кодом. Раскрасить эмоции значило бы отдать им смысл, которого у
        // них нет: девять цветов рядом с тремя значащими.
        for ((name, theme) in listOf("светлая" to TimaColors.light, "тёмная" to TimaColors.dark)) {
            val c = theme.emotion
            assertTrue(
                abs(c.red - c.green) < 0.01f && abs(c.green - c.blue) < 0.01f,
                "$name: эмоции обязаны быть серыми, а получено $c",
            )
        }
    }

    @Test
    fun текст_на_янтаре_чёрный_в_обеих_темах() {
        // Правило README: «янтарь сохранил чёрный текст: он не зелёный, и 12,32 : 1
        // терять не за что». В стиль.css счётчику задан var(--текст), то есть в тёмной
        // теме белый по янтарю — около 1,7 : 1. Взята версия README, и вот её цена в
        // числах, чтобы выбор был виден.
        for ((name, theme) in listOf("светлая" to TimaColors.light, "тёмная" to TimaColors.dark)) {
            val ratio = TimaContrast.ratio(theme.onAmber, theme.activity)
            close(12.32, ratio, tolerance = 0.05)
            assertTrue(ratio >= TimaContrast.TEXT_THRESHOLD, "$name: текст на янтаре обязан читаться")
        }
    }

    @Test
    fun внутри_плашки_всё_белое_в_обеих_темах() {
        // Плашка в обеих темах одна и та же салатовая, значит и то, что на ней лежит,
        // одинаково. Это следствие, а не зашитый цвет: если плашка однажды сменит цвет,
        // менять придётся и это — вместе, а не по отдельности.
        assertEquals(TimaColors.light.inPlate, TimaColors.dark.inPlate)
        val ratio = TimaContrast.ratio(TimaColors.light.inPlate, TimaColors.light.navigation)
        close(2.08, ratio)
    }

    // ── инструмент ───────────────────────────────────────────────────────────

    @Test
    fun контраст_прозрачного_цвета_считать_отказывается() {
        // «Контраст рамки» без указания подложки — число ни о чём: полупрозрачный цвет
        // выглядит по-разному на разном фоне.
        assertFailsWith<IllegalArgumentException> {
            TimaContrast.ratio(TimaColors.light.line, TimaColors.light.surface)
        }
    }

    @Test
    fun наложение_прозрачного_на_фон_даёт_то_что_видит_человек() {
        // Линия 14 % чёрного на белом — это светло-серый, и контраст у него небольшой.
        val visible = TimaContrast.overlay(TimaColors.light.line, TimaColors.light.surface)
        assertEquals(1f, visible.alpha)
        assertTrue(
            TimaContrast.ratio(visible, TimaColors.light.surface) < 1.5,
            "линия обязана быть тихой: она разделяет, а не привлекает",
        )
    }

    @Test
    fun крайние_случаи_шкалы_сходятся() {
        close(21.0, TimaContrast.ratio(Color.Black, Color.White))
        close(1.0, TimaContrast.ratio(Color.White, Color.White))
    }

    private fun brightness(color: Color): Double =
        0.2126 * color.red + 0.7152 * color.green + 0.0722 * color.blue
}
