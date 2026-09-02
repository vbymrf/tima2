package io.tima.feature.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.tima.core.ui.Appearance
import io.tima.core.ui.ColorSlot
import io.tima.core.ui.ThemeChoice
import io.tima.core.ui.TimaColors
import io.tima.core.ui.TimaFixed
import io.tima.core.ui.with
import io.tima.testui.FOREIGN_BACKGROUND
import io.tima.testui.bothThemes
import io.tima.testui.capture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * «Оформление» в снимках.
 *
 * Проверяются утверждения, а не картинка: три темы видно, выбранная отличается,
 * семнадцать цветов показываются только у своей темы, а сама тема применяется к экрану,
 * на котором её и выбирают.
 */
class AppearanceScreenTest {

    /**
     * Список цветов принадлежит своей теме и только ей.
     *
     * Показать семнадцать строк рядом со «Светлой» значило бы предложить править то, что
     * не применится: у готовых тем цвета свои и правке не подлежат.
     */
    @Test
    fun цвета_показываются_только_у_своей_темы() {
        val light = capture("оформление-светлая", WIDTH, HEIGHT, dark = false) { screen(ThemeChoice.Light) }
        val custom = capture("оформление-своя", WIDTH, HEIGHT, dark = false) { screen(ThemeChoice.Custom) }
        assertTrue(
            light.difference(custom) > 0.0,
            "выбор темы ничего не изменил на экране — список цветов не появился",
        )
    }

    /**
     * Список тем виден целиком, а не одним заголовком.
     *
     * Трёх строк мало, чтобы занять экран, поэтому проверяется верхняя треть: пустая
     * означала бы, что список не нарисовался вовсе.
     *
     * Ищется чёрное в обеих темах, а не текст темы: с 2026-09-03 экран рисуется
     * [TimaFixed] — чёрным по белому — при любой выбранной теме.
     */
    @Test
    fun три_темы_нарисованы() {
        for ((name, snapshot) in bothThemes("оформление-темы", WIDTH, HEIGHT) { screen(ThemeChoice.Light) }) {
            assertTrue(
                snapshot.patchHas(TimaFixed.ink, y = 0 until HEIGHT / 3, side = 2),
                "$name: верхняя треть пуста — списка тем нет",
            )
        }
    }

    /**
     * Экран оформления **не подчиняется** подобранной теме.
     *
     * Проверка перевёрнута 2026-09-03 вместе с решением. До этого она требовала
     * обратного: своя тема применяется к самому экрану, «человек подбирает цвет, глядя
     * на него». Довод проиграл случаю, который тем же способом и достигается: своя тема
     * открыта целиком, и первым, что она делает нечитаемым, оказывается этот экран.
     * Человек остаётся без строк, без цветов и без кнопки возврата — то есть без выхода
     * из положения, в которое сам себя и завёл.
     *
     * Проверяется заведомо чужой краской, которой в палитре нет вовсе: если она видна,
     * значит фон экрана взят у своей темы.
     */
    @Test
    fun экран_оформления_не_подчиняется_своей_теме() {
        val mark = FOREIGN_BACKGROUND
        val wrecked = TimaColors.light
            .with(ColorSlot.SURFACE, mark)
            .with(ColorSlot.TEXT, mark)
            .with(ColorSlot.FUNCTIONAL, mark)
        val painted = capture("оформление-не-подчиняется", WIDTH, HEIGHT, dark = false) {
            Box(Modifier.fillMaxSize()) {
                io.tima.core.ui.TimaTheme(colors = wrecked) {
                    AppearanceScreen(
                        appearance = Appearance(ThemeChoice.Custom, wrecked),
                        onAppearance = {},
                    )
                }
            }
        }
        // Смотрим полосу у левого края: там только фон. Образцы цветов начинаются
        // с шестнадцатой точки — это поле строки, — и они-то как раз обязаны быть
        // чужой краской, поэтому по всему снимку такую проверку ставить нельзя.
        val edge = 0 until 8
        assertTrue(
            !painted.patchHas(mark, x = edge, side = 4),
            "своя тема залила фон экрана оформления — на нём нельзя будет починить её же",
        )
        assertTrue(
            painted.patchHas(TimaFixed.paper, x = edge, side = 4),
            "фон экрана оформления обязан оставаться белым",
        )
        assertTrue(
            painted.patchHas(TimaFixed.ink, side = 2),
            "текст экрана оформления обязан оставаться чёрным",
        )
        // Образцы цветов при этом показывают именно свои значения: экран не подчиняется
        // теме, но и не скрывает её. Чужая краска обязана быть видна квадратиком.
        assertTrue(painted.has(mark), "образцы цветов не показывают подобранное")
    }

    /**
     * Возврат — выбор из двух, а не догадка.
     *
     * Промежуточная редакция того же дня возвращала «то, что ближе»: сравнивала фон
     * своей темы с тёмным. Догадка врёт ровно тогда, когда дороже всего — человек,
     * начавший со светлой и перекрасивший фон в тёмный, получал бы тёмную обратно.
     */
    @Test
    fun кнопок_возврата_две_и_они_возвращают_готовые_темы() {
        assertEquals(2, RESETS.size)
        assertEquals(listOf(TimaColors.light, TimaColors.dark), RESETS.map { it.second })
        assertEquals(
            RESETS.size,
            RESETS.map { it.first }.distinct().size,
            "две кнопки с одной надписью неразличимы",
        )
    }

    @Composable
    private fun screen(choice: ThemeChoice) = Box(Modifier.fillMaxSize()) {
        AppearanceScreen(
            appearance = Appearance(choice, TimaColors.light),
            onAppearance = {},
        )
    }

    private fun theme(name: String): TimaColors = io.tima.testui.theme(name)

    private companion object {
        const val WIDTH = 420
        const val HEIGHT = 900
    }
}
