package io.tima.testui

import io.tima.core.ui.TimaColors
import io.tima.core.ui.TimaTheme
import io.tima.core.ui.Tima

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
class Snapshot(private val map: Bitmap, val name: String) {
    val width: Int get() = map.width
    val height: Int get() = map.height

    /** Цвет пикселя. Всегда непрозрачный: сцена рисуется на непрозрачной поверхности. */
    fun color(x: Int, y: Int): Color = Color(map.getColor(x, y))

    fun pixels(): Sequence<Color> = sequence {
        for (y in 0 until height) for (x in 0 until width) yield(color(x, y))
    }

    /** Есть ли такой цвет хоть где-нибудь. Для «полосы тут быть не должно». */
    fun has(color: Color, tolerance: Double = TOLERANCE): Boolean = pixels().any { close(it, color, tolerance) }

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
    fun patchHas(
        color: Color,
        x: IntRange = 0 until width,
        y: IntRange = 0 until height,
        side: Int = 3,
        tolerance: Double = TOLERANCE,
    ): Boolean {
        for (yy in y.first..(y.last - side + 1)) {
            columns@ for (xx in x.first..(x.last - side + 1)) {
                for (dy in 0 until side) {
                    for (dx in 0 until side) {
                        val dot = color(xx + dx, yy + dy)
                        if (!close(dot, color, tolerance)) continue@columns
                    }
                }
                return true
            }
        }
        return false
    }

    /** Самый тёмный пиксель области — сердцевина глифа, если в области есть текст. */
    fun darkMost(x: IntRange, y: IntRange): Color = edge(x, y) { -brightness(it) }

    fun lightMost(x: IntRange, y: IntRange): Color = edge(x, y) { brightness(it) }

    private fun edge(x: IntRange, y: IntRange, weight: (Color) -> Double): Color {
        var best = color(x.first, y.first)
        for (yy in y) for (xx in x) {
            val c = color(xx, yy)
            if (weight(c) > weight(best)) best = c
        }
        return best
    }

    /** Доля пикселей, которыми два снимка расходятся. */
    fun difference(other: Snapshot): Double {
        require(width == other.width && height == other.height)
        var different = 0
        for (y in 0 until height) for (x in 0 until width) {
            if (!close(color(x, y), other.color(x, y), TOLERANCE)) different++
        }
        return different.toDouble() / (width * height)
    }

    companion object {
        const val TOLERANCE: Double = 2.0 / 255

        fun close(a: Color, b: Color, tolerance: Double = TOLERANCE): Boolean =
            abs(a.red - b.red) <= tolerance &&
                abs(a.green - b.green) <= tolerance &&
                abs(a.blue - b.blue) <= tolerance

        private fun brightness(c: Color): Double = 0.2126 * c.red + 0.7152 * c.green + 0.0722 * c.blue
    }
}

/**
 * Снять композицию.
 *
 * Позади содержимого всегда лежит [TimaColors.поверхность]: в тёмной теме `функц`
 * полупрозрачен, и без непрозрачной подложки снимок получился бы на пустоте, чего на
 * устройстве не бывает.
 */
fun capture(
    name: String,
    width: Int,
    height: Int,
    dark: Boolean,
    /**
     * Чем закрашено под содержимым.
     *
     * `null` — поверхность темы, как в приложении. [ЧУЖОЙ_ФОН] — краска, которой в палитре
     * нет вовсе: если она видна на снимке, значит **экран не залил свой фон** и показывает
     * то, что под ним. На устройстве это выглядит как чужой цвет из-под шапки, и снимок с
     * подложкой темы такого не покажет никогда — он покрасит ровно то же, что покрасил бы
     * экран.
     */
    backdrop: Color? = null,
    content: @Composable () -> Unit,
): Snapshot {
    val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
        TimaTheme(dark = dark) {
            Box(Modifier.fillMaxSize().background(backdrop ?: Tima.colors.surface)) { content() }
        }
    }
    try {
        val image = scene.render()
        val fullName = "$name-${if (dark) "dark" else "light"}"
        save(image, fullName)
        return Snapshot(Bitmap.makeFromImage(image), fullName)
    } finally {
        scene.close()
    }
}

private fun save(image: org.jetbrains.skia.Image, name: String) {
    val catalog = File("build/снимки").apply { mkdirs() }
    image.encodeToData()?.bytes?.let { File(catalog, "$name.png").writeBytes(it) }
}

/** Обе темы одним вызовом: почти каждое утверждение макета проверяется в двух. */
fun bothThemes(
    name: String,
    width: Int,
    height: Int,
    backdrop: Color? = null,
    content: @Composable () -> Unit,
): Map<String, Snapshot> = mapOf(
    "светлая" to capture(name, width, height, dark = false, backdrop = backdrop, content = content),
    "тёмная" to capture(name, width, height, dark = true, backdrop = backdrop, content = content),
)

/**
 * Краска, которой в палитре нет.
 *
 * Нужна ровно для одного утверждения: **экран заливает свой фон**. Экран без фона
 * показывает то, что под ним, — на телефоне это увидели глазами, а снимок с обычной
 * подложкой этого не покажет: он красит ровно тем же цветом, каким покрасил бы экран.
 */
val FOREIGN_BACKGROUND: Color = Color(0xFFFF00FF)

/** Тема по имени — чтобы в сообщении об ошибке стояло «светлая», а не `false`. */
fun theme(name: String): TimaColors =
    if (name == "тёмная") TimaColors.dark else TimaColors.light
