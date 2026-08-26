package io.tima.feature.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.tima.core.ui.Stage
import io.tima.core.ui.TimaColors
import io.tima.testui.bothThemes
import io.tima.testui.capture
import io.tima.testui.theme
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Каркас окна в снимках.
 *
 * Два правила шапки переехали сюда из `ЭкранПереписокTest` вместе с самой шапкой: пока
 * окно было одно, она жила в его экране, и проверялась там же. Теперь каркас один на
 * пять окон, и правила проверяются один раз — иначе их пришлось бы повторять пять раз
 * и они бы разошлись.
 */
class WindowFrameTest {

    /**
     * Шапка-плашка есть: это основное окно.
     *
     * Плашка отвечает на вопрос «в каком я окне». У подокна такого вопроса нет, и там
     * плашки не бывает — за этим следит `ЭкранЧатаTest`.
     */
    @Test
    fun у_основного_окна_есть_салатовая_плашка() {
        for ((name, snapshot) in bothThemes("каркас-шапка", WIDTH, HEIGHT) { window() }) {
            assertTrue(
                snapshot.patchHas(theme(name).navigation, y = 0 until ZONE_1, side = 8),
                "$name: плашки окна нет — человек не видит, в каком он окне",
            )
        }
    }

    /**
     * Логотип есть только на телефоне.
     *
     * На широком формате он живёт в рейке, и второй раз ему в шапке колонки делать
     * нечего: ширина добавляет полосы, а не повторяет показанное.
     */
    @Test
    fun логотип_в_шапке_только_на_телефоне() {
        val phone = capture("каркас-лого-телефон", WIDTH, HEIGHT, dark = false) { window() }
        val wide = capture("каркас-лого-широкий", 1000, HEIGHT, dark = false) { window() }

        assertTrue(
            phone.patchHas(TimaColors.light.inPlate, y = 0 until ZONE_1, x = 0 until 60),
            "на телефоне белого квадрата логотипа нет",
        )
        assertTrue(
            !wide.patchHas(TimaColors.light.inPlate, y = 0 until ZONE_1, x = 76 until 140),
            "на широком формате логотип повторён в шапке колонки, хотя он уже в рейке",
        )
    }

    /**
     * Выбранная вкладка отличается от невыбранной.
     *
     * Проверка кажется тавтологией ровно до того дня, когда чип выбранной вкладки
     * получит тот же вид, что остальные, — и окно молча перестанет показывать, где вы.
     */
    @Test
    fun выбранная_вкладка_видна() {
        val first = capture("каркас-вкладка-1", WIDTH, HEIGHT, dark = false) { window("Общая") }
        val second = capture("каркас-вкладка-2", WIDTH, HEIGHT, dark = false) { window("Каталог") }

        assertTrue(
            first.difference(second) > 0.0,
            "смена вкладки ничего не изменила на экране",
        )
    }

    @Composable
    private fun window(selected: String = "Общая") = Stage(
        column = {
            WindowFrame(
                window = Window.Social,
                tabs = listOf("Общая", "Друзья", "Каталог"),
                selected = selected,
                onTab = {},
                onSwitchWindows = {},
                onSearch = {},
                onSettings = {},
            ) { Box(Modifier.fillMaxSize()) }
        },
    )

    private companion object {
        const val WIDTH = 380
        const val HEIGHT = 800
        const val ZONE_1 = 56
    }
}
