package io.tima.feature.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.tima.core.ui.Appearance
import io.tima.core.ui.ColorSlot
import io.tima.core.ui.ThemeChoice
import io.tima.core.ui.TimaColors
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
     */
    @Test
    fun три_темы_нарисованы() {
        for ((name, snapshot) in bothThemes("оформление-темы", WIDTH, HEIGHT) { screen(ThemeChoice.Light) }) {
            assertTrue(
                snapshot.patchHas(theme(name).text, y = 0 until HEIGHT / 3, side = 2),
                "$name: верхняя треть пуста — списка тем нет",
            )
        }
    }

    /**
     * Своя тема применяется к самому экрану оформления.
     *
     * Это и есть смысл «применяется сразу»: человек подбирает цвет, глядя на него.
     * Проверяется заведомо чужим цветом — такого в палитре нет вовсе, и появиться на
     * снимке он может только из своей темы.
     */
    @Test
    fun своя_тема_применяется_к_самому_экрану() {
        // `FOREIGN_BACKGROUND` — та самая «краска, которой в палитре нет вовсе»,
        // заведённая в снимках ровно для таких утверждений. Свой литерал здесь был бы
        // вторым таким же, а заодно нарушил бы правило «на экранах нет зашитых цветов».
        val mark = FOREIGN_BACKGROUND
        val painted = capture("оформление-применилось", WIDTH, HEIGHT, dark = false) {
            Box(Modifier.fillMaxSize()) {
                io.tima.core.ui.TimaTheme(colors = TimaColors.light.with(ColorSlot.SURFACE, mark)) {
                    AppearanceScreen(
                        appearance = Appearance(ThemeChoice.Custom, TimaColors.light.with(ColorSlot.SURFACE, mark)),
                        onAppearance = {},
                    )
                }
            }
        }
        assertTrue(
            painted.patchHas(mark, side = 8),
            "выбранный цвет фона не виден на экране, где его выбирают",
        )
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
