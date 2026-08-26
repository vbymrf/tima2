package io.tima.core.ui

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Контраст по WCAG 2.1 — инструмент, а не украшение.
 *
 * **Зачем он в коде дизайн-системы.** Макет называет конкретные отношения и прямо
 * говорит, где они ниже порога: название окна салатовым по светлой шапке даёт
 * 1,95 : 1, белый на салатовом — 2,08 : 1 при пороге 4,5 : 1. Это решения с
 * названной ценой, а не недосмотр. Но пока цена живёт только в тексте README, любая
 * правка палитры меняет её молча.
 *
 * Здесь она становится числом, которое можно проверить тестом.
 */
object TimaContrast {

    /** Порог WCAG AA для обычного текста. */
    const val TEXT_THRESHOLD: Double = 4.5

    /** Порог для крупного (18 pt, либо 14 pt полужирного) текста. */
    const val ПОРОГ_КРУПНОГО: Double = 3.0

    /**
     * Отношение контраста двух **непрозрачных** цветов: от 1 : 1 до 21 : 1.
     *
     * Прозрачность здесь запрещена намеренно: контраст полупрозрачного цвета зависит
     * от того, что под ним, и «контраст рамки» без указания подложки — число ни о чём.
     * Кому нужно — пусть сперва наложит цвет на фон ([наложить]).
     */
    fun ratio(first: Color, second: Color): Double {
        require(first.alpha == 1f && second.alpha == 1f) {
            "контраст считается для непрозрачных цветов; наложите на фон через наложить()"
        }
        val a = brightness(first)
        val b = brightness(second)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    /**
     * Накладывает полупрозрачный цвет на непрозрачный фон.
     *
     * Нужно как раз для линий, рамок и приглушённого текста: в макете они заданы
     * прозрачностью, а видит человек результат наложения.
     */
    fun overlay(over: Color, background: Color): Color {
        require(background.alpha == 1f) { "фон обязан быть непрозрачным" }
        val a = over.alpha
        return Color(
            red = over.red * a + background.red * (1 - a),
            green = over.green * a + background.green * (1 - a),
            blue = over.blue * a + background.blue * (1 - a),
            alpha = 1f,
        )
    }

    /** Относительная яркость по WCAG: линеаризация каналов, потом взвешенная сумма. */
    private fun brightness(color: Color): Double {
        fun channel(v: Float): Double {
            val x = v.toDouble()
            return if (x <= 0.03928) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }
}
