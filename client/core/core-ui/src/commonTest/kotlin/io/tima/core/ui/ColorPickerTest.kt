package io.tima.core.ui

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Разложение цвета для квадрата и полосы.
 *
 * **Проверяется одно: отметки встают там, где стоит нынешний цвет.** Без этого подбор
 * начинался бы каждый раз из угла квадрата — человек правит цвет, а не набирает заново,
 * и «открыл — а ползунки не там» он прочтёт как сброс своей настройки.
 *
 * Сама отрисовка здесь не проверяется: градиент квадрата — это две заливки поверх друг
 * друга, и снимок такого говорит «картинка изменилась», а не «выбор работает».
 */
class ColorPickerTest {

    @Test
    fun разложение_и_сборка_сходятся_на_всей_палитре() {
        // Берётся не выдуманный набор, а вся палитра проекта: это ровно те цвета, из
        // которых подбор и начинается.
        val palette = ColorSlot.entries.flatMap {
            listOf(TimaColors.light.slot(it), TimaColors.dark.slot(it))
        }
        for (color in palette) {
            val (hue, saturation, value) = color.hueSatVal()
            val again = Color.hsv(hue, saturation, value, color.alpha)
            assertTrue(
                sameColor(color, again),
                "${color.hex()} разложился в ($hue, $saturation, $value) и собрался в ${again.hex()}",
            )
            assertEquals(color.alpha, again.alpha, "непрозрачность обязана пережить разложение")
        }
    }

    /**
     * У серого оттенка нет, и это не ошибка.
     *
     * Насыщенность ноль — значит по полосе цветов ползунок может стоять где угодно,
     * цвет от этого не изменится. Отсюда и решение держать положение полосы отдельной
     * памятью: считать его из чёрного нечего.
     */
    @Test
    fun у_серого_насыщенность_ноль() {
        for (grey in listOf(TimaFixed.ink, TimaFixed.paper, TimaColors.light.my)) {
            val (_, saturation, _) = grey.hueSatVal()
            assertEquals(0f, saturation, absoluteTolerance = 0.01f, message = "${grey.hex()} не серый")
        }
        assertEquals(0f, TimaFixed.ink.hueSatVal().third, absoluteTolerance = 0.01f)
        assertEquals(1f, TimaFixed.paper.hueSatVal().third, absoluteTolerance = 0.01f)
    }

    /** Цвет по кругу: красный, зелёный и синий стоят там, где им положено. */
    @Test
    fun основные_цвета_стоят_на_своих_градусах() {
        assertEquals(0f, Color.hsv(0f, 1f, 1f).hueSatVal().first, absoluteTolerance = 0.5f)
        assertEquals(120f, Color.hsv(120f, 1f, 1f).hueSatVal().first, absoluteTolerance = 0.5f)
        assertEquals(240f, Color.hsv(240f, 1f, 1f).hueSatVal().first, absoluteTolerance = 0.5f)
    }
}
