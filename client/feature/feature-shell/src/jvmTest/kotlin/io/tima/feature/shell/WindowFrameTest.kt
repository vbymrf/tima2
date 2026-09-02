package io.tima.feature.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.tima.core.ui.Stage
import io.tima.core.ui.TimaColors
import io.tima.core.ui.TimaContrast
import io.tima.testui.Snapshot
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
     * Ищется белое **у левого края плашки**, а не где угодно в полосе шапки. С
     * 2026-09-02 белые круги в плашке есть и у кнопок поиска и настроек, и проверка
     * «белое есть в шапке» с тех пор проходила бы и без логотипа вовсе. Левый край
     * плашки находится по салатовому: на широком формате слева стоит рейка, и с какой
     * точки начинается окно, тест знать не обязан.
     */
    @Test
    fun логотип_в_шапке_на_всех_форматах() {
        val phone = capture("каркас-лого-телефон", WIDTH, HEIGHT, dark = false) { window() }
        val wide = capture("каркас-лого-широкий", 1000, HEIGHT, dark = false) { window() }

        for ((name, snapshot) in listOf("телефон" to phone, "широкий" to wide)) {
            val left = plateLeft(snapshot)
            assertTrue(left >= 0, "$name: плашки нет вовсе — искать логотип не в чем")
            assertTrue(
                snapshot.patchHas(TimaColors.light.inPlate, y = 0 until ZONE_1, x = left until left + 60),
                "$name: у левого края плашки нет белого квадрата логотипа",
            )
        }
    }

    /**
     * Невыбранная вкладка — слово без заливки.
     *
     * **Это то самое, что заказчик увидел глазами 2026-09-02:** «второй ряд полностью
     * повторяет первый». Ряд вкладок и ряд подвкладок стоят друг под другом, и пока
     * вкладка была чипом с тихой салатовой заливкой, оба ряда читались как один ряд из
     * шести одинаковых пилюль. Макет всё это время говорил другое: у `.таб` фона нет.
     *
     * Два утверждения вместе, потому что каждое по отдельности проходит на браке.
     * «Залитых тихим нет» пройдёт и на ряде, где не осталось вообще ничего; «залитая
     * салатовым есть» пройдёт и на прежних чипах.
     */
    @Test
    fun невыбранная_вкладка_словом_без_заливки() {
        for ((name, snapshot) in bothThemes("каркас-вкладки", WIDTH, HEIGHT) { window() }) {
            val colors = theme(name)
            // Тихий акцент полупрозрачен, и на снимке он уже смешан с серым фоном ряда,
            // а тот в тёмной теме — с поверхностью. Сравнивать надо с наложением.
            val tint = TimaContrast.overlay(
                colors.softAccent,
                TimaContrast.overlay(colors.functional, colors.surface),
            )
            assertTrue(
                !snapshot.patchHas(tint, y = TAB_ROW, side = 3),
                "$name: невыбранная вкладка залита тихим акцентом — ряды вкладок сливаются",
            )
            assertTrue(
                snapshot.patchHas(colors.navigation, y = TAB_ROW, side = 6),
                "$name: текущая вкладка не залита салатовым — ряд перестал отвечать «где я»",
            )
        }
    }

    /** Левый край салатовой плашки: с какой точки начинается окно. */
    private fun plateLeft(snapshot: Snapshot): Int {
        for (x in 0 until snapshot.width) {
            for (y in 0 until ZONE_1) {
                if (Snapshot.close(snapshot.color(x, y), TimaColors.light.navigation)) return x
            }
        }
        return -1
    }

    /**
     * Имя окна стоит слева, сразу за логотипом.
     *
     * Проверка перевёрнута дважды за один день, и это стоит держать на виду. Сначала
     * имя стояло слева (как в макете), потом уехало в центр — я прочитал «центрируем
     * текст» как горизонталь, — потом вернулось: заказчик уточнил, что имелась в виду
     * **вертикаль**, то есть выравнивание по середине плашки.
     *
     * Проверяется через белое: имя набрано белым по салатовому. Оно обязано быть в
     * левой трети и не должно занимать середину — на телефоне в 380 точек «Социум»
     * помещается в первую треть целиком.
     */
    @Test
    fun имя_окна_стоит_слева_за_логотипом() {
        val phone = capture("каркас-имя-слева", WIDTH, HEIGHT, dark = false) { window() }

        // Первый столбец белого правее логотипа — и есть начало имени. Логотип
        // заканчивается около 52-й точки, поэтому смотрим с 56-й.
        var begins = WIDTH
        for (x in 56 until WIDTH) {
            val white = (0 until ZONE_1).any { y -> Snapshot.close(phone.color(x, y), TimaColors.light.inPlate) }
            if (white) { begins = x; break }
        }
        assertTrue(
            begins < WIDTH / 3,
            "имя окна начинается на $begins-й точке из $WIDTH — это не «слева за логотипом»",
        )
    }

    /**
     * Шапка, вкладки и второй ряд — один серый блок.
     *
     * Решение заказчика: «сделай фон всех вкладок и подкладок единым — серый». Между
     * шапкой и вкладками не должно быть ни линии, ни белой щели: белое там означало бы,
     * что между ними проглянуло содержимое.
     */
    @Test
    fun шапка_и_вкладки_один_серый_блок() {
        for ((name, snapshot) in bothThemes("каркас-блок", WIDTH, HEIGHT) { window() }) {
            val colors = theme(name)
            // Сравнивать надо с наложением, а не с самим токеном: в тёмной теме
            // `functional` полупрозрачен. Именно это и ловится здесь: пока блок красил
            // фон и сверху его же красила шапка, получалось 0,135 вместо 0,07 — шапка
            // выходила светлее вкладок под ней.
            val gray = TimaContrast.overlay(colors.functional, colors.surface)
            for (y in ZONE_1 - 6 until ZONE_1 + 6) {
                assertTrue(
                    Snapshot.close(snapshot.color(2, y), gray),
                    "$name: на строке $y между шапкой и вкладками не серый фон, а ${snapshot.color(2, y)}",
                )
            }
        }
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

        /**
         * Полоса ряда вкладок: сразу под шапкой.
         *
         * Границы с запасом намеренно — утверждения здесь не про точную высоту ряда, а
         * про то, чем в нём залито.
         */
        val TAB_ROW = ZONE_1 until ZONE_1 + 40
    }
}
