package io.tima.testui

import io.tima.core.ui.TimaColors
import io.tima.core.ui.TimaTheme
import io.tima.core.ui.Тима

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.Bitmap
import java.io.File
import kotlin.math.abs

/**
 * Снимок композиции — настоящие пиксели, без устройства и без эмулятора.
 *
 * `ImageComposeScene` из Compose Desktop рисует композицию в картинку прямо в тесте.
 * Компоненты и экраны лежат в `commonMain`, поэтому расхождение, снятое здесь, есть
 * расхождение вообще, а не только на JVM.
 *
 * Модуль отдельный, потому что проверять снимками надо и дизайн-систему, и экраны, а
 * тестовые наборы между модулями не разделяются. Исходники лежат в `main` по той же
 * причине, что у `test-harness`: его потребители — тесты других модулей.
 *
 * **Золотых картинок в этих тестах нет, и это выбор, а не упрощение.** Золотая картинка
 * говорит «стало не так, как было», и человек всё равно идёт смотреть глазами, чей она
 * при этом заложник версии шрифта, сглаживания и системы сборки. Здесь проверяются
 * утверждения макета: счётчик янтарный, текст на нём чёрный, полоса автора огибает
 * скругление, аватар её перекрывает, тема действительно меняет пиксели. Такой тест
 * говорит, ЧТО именно сломалось, и не краснеет от смены шрифта.
 *
 * Картинки при этом всё равно сохраняются в `build/снимки/` — посмотреть глазами
 * полезно, просто это не то, на чём стоит проверка.
 */
class Снимок(private val карта: Bitmap, val имя: String) {
    val ширина: Int get() = карта.width
    val высота: Int get() = карта.height

    /** Цвет пикселя. Всегда непрозрачный: сцена рисуется на непрозрачной поверхности. */
    fun цвет(x: Int, y: Int): Color = Color(карта.getColor(x, y))

    fun пиксели(): Sequence<Color> = sequence {
        for (y in 0 until высота) for (x in 0 until ширина) yield(цвет(x, y))
    }

    /** Есть ли такой цвет хоть где-нибудь. Для «полосы тут быть не должно». */
    fun есть(цвет: Color, допуск: Double = ДОПУСК): Boolean = пиксели().any { близки(it, цвет, допуск) }

    /**
     * Есть ли в области **пятно** этого цвета — сплошной квадрат [сторона]×[сторона].
     *
     * Одиночный пиксель проверять нельзя, и это не осторожность, а найденный случай:
     * сглаженная граница чужого пузыря на белом дала ровно тот серый, каким залито своё
     * сообщение, — четыре пикселя на всю картинку, и проверка «своё не касается левого
     * края» покраснела на пустом месте. Сглаживание даёт одиночные совпадения и никогда
     * не даёт сплошного квадрата.
     *
     * Квадрат, а не ряд: полоса автора шириной в четыре точки не содержит ряда в восемь,
     * зато содержит квадрат три на три.
     */
    fun естьПятно(
        цвет: Color,
        x: IntRange = 0 until ширина,
        y: IntRange = 0 until высота,
        сторона: Int = 3,
        допуск: Double = ДОПУСК,
    ): Boolean {
        for (yy in y.first..(y.last - сторона + 1)) {
            столбцы@ for (xx in x.first..(x.last - сторона + 1)) {
                for (dy in 0 until сторона) {
                    for (dx in 0 until сторона) {
                        val точка = цвет(xx + dx, yy + dy)
                        if (!близки(точка, цвет, допуск)) continue@столбцы
                    }
                }
                return true
            }
        }
        return false
    }

    /** Самый тёмный пиксель области — сердцевина глифа, если в области есть текст. */
    fun самыйТёмный(x: IntRange, y: IntRange): Color = крайний(x, y) { -яркость(it) }

    fun самыйСветлый(x: IntRange, y: IntRange): Color = крайний(x, y) { яркость(it) }

    private fun крайний(x: IntRange, y: IntRange, вес: (Color) -> Double): Color {
        var лучший = цвет(x.first, y.first)
        for (yy in y) for (xx in x) {
            val c = цвет(xx, yy)
            if (вес(c) > вес(лучший)) лучший = c
        }
        return лучший
    }

    /** Доля пикселей, которыми два снимка расходятся. */
    fun расхождение(другой: Снимок): Double {
        require(ширина == другой.ширина && высота == другой.высота)
        var разных = 0
        for (y in 0 until высота) for (x in 0 until ширина) {
            if (!близки(цвет(x, y), другой.цвет(x, y), ДОПУСК)) разных++
        }
        return разных.toDouble() / (ширина * высота)
    }

    companion object {
        const val ДОПУСК: Double = 2.0 / 255

        fun близки(a: Color, b: Color, допуск: Double = ДОПУСК): Boolean =
            abs(a.red - b.red) <= допуск &&
                abs(a.green - b.green) <= допуск &&
                abs(a.blue - b.blue) <= допуск

        private fun яркость(c: Color): Double = 0.2126 * c.red + 0.7152 * c.green + 0.0722 * c.blue
    }
}

/**
 * Снять композицию.
 *
 * Позади содержимого всегда лежит [TimaColors.поверхность]: в тёмной теме `функц`
 * полупрозрачен, и без непрозрачной подложки снимок получился бы на пустоте, чего на
 * устройстве не бывает.
 */
fun снять(
    имя: String,
    ширина: Int,
    высота: Int,
    тёмная: Boolean,
    содержимое: @Composable () -> Unit,
): Снимок {
    val сцена = ImageComposeScene(width = ширина, height = высота, density = Density(1f)) {
        TimaTheme(dark = тёмная) {
            Box(Modifier.fillMaxSize().background(Тима.цвета.поверхность)) { содержимое() }
        }
    }
    try {
        val картинка = сцена.render()
        val полноеИмя = "$имя-${if (тёмная) "тёмная" else "светлая"}"
        сохранить(картинка, полноеИмя)
        return Снимок(Bitmap.makeFromImage(картинка), полноеИмя)
    } finally {
        сцена.close()
    }
}

private fun сохранить(картинка: org.jetbrains.skia.Image, имя: String) {
    val каталог = File("build/снимки").apply { mkdirs() }
    картинка.encodeToData()?.bytes?.let { File(каталог, "$имя.png").writeBytes(it) }
}

/** Обе темы одним вызовом: почти каждое утверждение макета проверяется в двух. */
fun обеТемы(
    имя: String,
    ширина: Int,
    высота: Int,
    содержимое: @Composable () -> Unit,
): Map<String, Снимок> = mapOf(
    "светлая" to снять(имя, ширина, высота, тёмная = false, содержимое = содержимое),
    "тёмная" to снять(имя, ширина, высота, тёмная = true, содержимое = содержимое),
)

/** Тема по имени — чтобы в сообщении об ошибке стояло «светлая», а не `false`. */
fun тема(имя: String): TimaColors =
    if (имя == "тёмная") TimaColors.тёмная else TimaColors.светлая
