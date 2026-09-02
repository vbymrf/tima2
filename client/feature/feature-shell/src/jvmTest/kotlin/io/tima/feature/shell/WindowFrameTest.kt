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
     * Логотип есть на всех форматах.
     *
     * Проверка перевёрнута 2026-09-02. До этого она требовала обратного — логотипа на
     * широком формате быть не должно, потому что макет ПК отдаёт «Т» шапке рейки. Той
     * шапки в коде не было вовсе, и на настольной сборке буквы не оказалось нигде;
     * заказчик увидел это глазами и решил: «на ПК он там же должен быть, вставить Т в
     * плашку шапки».
     *
     * Белый квадрат в полосе шапки — только логотип: подложка рейки серая
     * функциональная, плашка салатовая, а кнопки на ней мягкого акцента.
     */
    @Test
    fun логотип_в_шапке_на_всех_форматах() {
        val phone = capture("каркас-лого-телефон", WIDTH, HEIGHT, dark = false) { window() }
        val wide = capture("каркас-лого-широкий", 1000, HEIGHT, dark = false) { window() }

        assertTrue(
            phone.patchHas(TimaColors.light.inPlate, y = 0 until ZONE_1, x = 0 until 60),
            "на телефоне белого квадрата логотипа нет",
        )
        assertTrue(
            wide.patchHas(TimaColors.light.inPlate, y = 0 until ZONE_1),
            "на широком формате логотипа нет — а он больше не зависит от ширины",
        )
    }

    /**
     * Имя окна стоит по центру полосы, а не сразу за логотипом.
     *
     * Проверяется через белое: имя набрано белым по салатовому, и его пятно обязано
     * попадать в среднюю треть шапки. Слева от неё стоит логотип, справа две кнопки, и
     * до 2026-09-02 имя жило вплотную к логотипу — то есть в левой трети.
     *
     * Обе трети проверяются вместе намеренно. Одна левая проверка проходила бы и на
     * пустой шапке, а «в середине что-то белое есть» — на любой раскладке, где имя
     * просто длинное.
     */
    @Test
    fun имя_окна_стоит_по_центру_плашки() {
        val phone = capture("каркас-имя-центр", WIDTH, HEIGHT, dark = false) { window() }
        val third = WIDTH / 3
        assertTrue(
            phone.patchHas(TimaColors.light.inPlate, y = 0 until ZONE_1, x = third until third * 2, side = 2),
            "имени окна нет в средней трети шапки",
        )
        assertTrue(
            !phone.patchHas(TimaColors.light.inPlate, y = 0 until ZONE_1, x = 60 until third, side = 2),
            "между логотипом и центром что-то белое — имя не уехало в центр",
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
