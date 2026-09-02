package io.tima.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.tima.testui.Snapshot
import io.tima.testui.bothThemes
import io.tima.testui.capture
import io.tima.testui.theme
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Полосы стана в пикселях (У.4).
 *
 * [ФорматTest] проверяет числа, а этот тест — что числа доехали до экрана. Слоты
 * закрашены разными цветами темы, снимок читается построчно, и границы полос выходят
 * координатами: где начинается колонка, где главная область, есть ли четвёртая полоса.
 *
 * **Одна разметка на все форматы** проверяется буквально: во всех трёх случаях вызов
 * [Стан] один и тот же, меняется только размер сцены.
 */
class StageTest {

    /** Ширины полос ПК — из раскладки, а не переписанные сюда числом. */
    private val rail = FormatTima.CAPTION_RAIL.value.toInt()
    private val column = FormatTima.DESKTOP_COLUMN.value.toInt()

    @Test
    fun на_телефоне_одна_полоса_и_это_подокно() {
        val snapshot = capture("стан-телефон", 380, 800, dark = false) { stage() }
        // Подокно не стоит рядом с колонкой, а заменяет её: перерисовка, как в макете.
        assertEquals(0, start(snapshot, MAIN), "главная область обязана занять окно целиком")
        assertNull(start(snapshot, RAIL), "на телефоне рейки нет")
        assertNull(start(snapshot, COLUMN), "на телефоне колонка не стоит рядом с подокном")
        assertNull(start(snapshot, PANEL))
    }

    /** Ничего не выбрано — на телефоне видно список. Пустого состояния там не бывает. */
    @Test
    fun на_телефоне_без_выбора_видно_список() {
        val snapshot = capture("стан-телефон-список", 380, 800, dark = false) { stage(mainHas = false) }
        assertEquals(0, start(snapshot, COLUMN))
        assertNull(start(snapshot, MAIN))
    }

    @Test
    fun на_планшете_три_полосы() {
        val snapshot = capture("стан-планшет", 1024, 768, dark = false) { stage() }
        assertEquals(0, start(snapshot, RAIL))
        assertEquals(76, start(snapshot, COLUMN), "рейка значками — 76 точек")
        assertEquals(76 + 296, start(snapshot, MAIN), "колонка планшета — 296 точек")
        assertNull(start(snapshot, PANEL), "на планшете страница объекта открывается перерисовкой")
    }

    @Test
    fun на_пк_четыре_полосы() {
        val snapshot = capture("стан-пк", 1440, 900, dark = false) { stage() }
        assertEquals(0, start(snapshot, RAIL))
        // Числа берутся из тех же констант, что и раскладка: здесь проверяется, что
        // Стан их слушается, а не то, чему они равны. «Чему равны» — RailWidthTest,
        // и там это выведено из самого длинного текста, а не выбрано.
        assertEquals(rail, start(snapshot, COLUMN), "рейка с подписями — $rail точек")
        assertEquals(rail + column, start(snapshot, MAIN), "колонка ПК — $column точек")
        // 1141, а не 1140: у панели линия СЛЕВА, и первый её пиксель занят границей —
        // ровно как `border-left: 1px` в макете.
        assertEquals(1440 - 300 + 1, start(snapshot, PANEL), "панель прижата к правому краю")
    }

    /**
     * Панель — слот, а не полоса на месте.
     *
     * Ширина ПК её терпит, но если экран её не отдал, четвёртой полосы нет, и главная
     * область забирает место себе. Пустая полоса у края была бы хуже её отсутствия.
     */
    @Test
    fun не_отданная_панель_не_оставляет_пустой_полосы() {
        val snapshot = capture("стан-пк-без-панели", 1440, 900, dark = false) { stage(hasPanel = false) }
        assertEquals(rail + column, start(snapshot, MAIN))
        assertNull(start(snapshot, PANEL))
        assertTrue(
            Snapshot.close(snapshot.color(snapshot.width - 1, snapshot.height / 2), MAIN),
            "главная область обязана дойти до правого края",
        )
    }

    /** Полосы разделены линией темы — той же, что под шапкой, и в один пиксель. */
    @Test
    fun полосы_разделены_линией() {
        val snapshot = capture("стан-линии", 1440, 900, dark = false) { stage() }
        val y = snapshot.height / 2
        for ((name, x) in listOf("рейка/колонка" to rail - 1, "колонка/главная" to rail + column - 1)) {
            val line = snapshot.color(x, y)
            assertTrue(
                !Snapshot.close(line, RAIL) && !Snapshot.close(line, COLUMN) &&
                    !Snapshot.close(line, MAIN),
                "$name: на границе полос нет линии, там $line",
            )
        }
    }

    /**
     * Зона 3 опускается, а не исчезает.
     *
     * На телефоне гроздь висит над списком — содержимое доходит до нижнего края и видно
     * под кнопками. На широком формате кнопки те же, но стоят в отдельной области у
     * нижнего края колонки: содержимое до края больше не доходит. Круглая кнопка, висящая
     * поверх широкого списка, опирается только на воздух.
     *
     * Вызов [СГроздью] в обоих случаях один и тот же — формат он не спрашивает.
     */
    @Test
    fun гроздь_создания_на_телефоне_висит_а_на_широком_опускается() {
        val phone = capture("гроздь-телефон", 380, 800, dark = false) { stageWithCluster() }
        assertTrue(
            Snapshot.close(phone.color(6, 793), COLUMN),
            "на телефоне список обязан доходить до нижнего края: под грудью видно содержимое",
        )
        assertTrue(phone.has(CLUSTER), "гроздь на телефоне не нарисовалась вовсе")

        val desktop = capture("гроздь-пк", 1440, 900, dark = false) { stageWithCluster() }
        // Смотрим нижний край колонки ПК — чуть правее её левой границы.
        assertTrue(
            !Snapshot.close(desktop.color(rail + 14, 893), COLUMN),
            "на широком формате гроздь обязана опуститься в отдельную область: " +
                "список не должен доходить до нижнего края колонки",
        )
        assertTrue(desktop.has(CLUSTER), "гроздь на широком формате потерялась")
    }

    private companion object {
        // Слоты закрашены цветами темы — так снимок остаётся в палитре, а границы полос
        // читаются как смена цвета. Что именно нарисовано внутри полосы, здесь не важно.
        val RAIL: Color = TimaColors.light.activity
        val COLUMN: Color = TimaColors.light.confirmed
        val MAIN: Color = TimaColors.light.my
        val PANEL: Color = TimaColors.light.navigation
        val CLUSTER: Color = TimaColors.light.emotion

        /** Первый столбец, где встречается цвет. `null` — полосы этого цвета нет вовсе. */
        fun start(snapshot: Snapshot, color: Color): Int? {
            val y = snapshot.height / 2
            return (0 until snapshot.width).firstOrNull { Snapshot.close(snapshot.color(it, y), color) }
        }

        @Composable
        fun fill(color: Color) = Box(Modifier.fillMaxSize().background(color))

        /**
         * Один и тот же вызов на все три формата — в этом и признак готовности У.4.
         *
         * Экран не спрашивает про формат и ничего не прячет: он отдаёт слоты, а сколько из
         * них видно, решает ширина контейнера.
         */
        @Composable
        fun stage(mainHas: Boolean = true, hasPanel: Boolean = true) = Stage(
            column = { fill(COLUMN) },
            rail = { fill(RAIL) },
            main = if (mainHas) ({ fill(MAIN) }) else null,
            panel = if (hasPanel) ({ fill(PANEL) }) else null,
        )

        /** Тот же вызов [СГроздью] на телефоне и на ПК: место кнопок решает формат. */
        @Composable
        fun stageWithCluster() = Stage(
            column = {
                WithCluster(
                    cluster = { Box(Modifier.size(36.dp).background(CLUSTER, CircleShape)) },
                    caption = "Написать",
                ) { fill(COLUMN) }
            },
            rail = { fill(RAIL) },
            main = null,
        )
    }
}
