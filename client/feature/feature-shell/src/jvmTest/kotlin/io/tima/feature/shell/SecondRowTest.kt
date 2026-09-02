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
 * Второй ряд в снимках: фильтры и режимы под вкладками.
 *
 * Проверяются **утверждения макета**, а не картинка. Ряд заведён 2026-09-02 сразу по
 * всем местам, где он есть в макете, — и все они делят один компонент, поэтому его
 * правила проверяются здесь один раз, а не в пяти окнах.
 */
class SecondRowTest {

    /**
     * Ряд фильтров стоит на подложке **содержимого**, а не на функциональной.
     *
     * Так в макете, и это не украшение: `§1` делит экран признаком «где приложение
     * показывает — фона нет; где им управляют — есть». Вкладки принадлежат управлению
     * окном и лежат на функциональной подложке; фильтр принадлежит списку под ним.
     * Слитый с вкладками ряд читался бы вторым этажом вкладок — а он не вкладки.
     */
    @Test
    fun ряд_фильтров_на_подложке_содержимого() {
        for ((name, snapshot) in bothThemes("ряд-фильтров", WIDTH, 44) { filters("Все") }) {
            val colors = theme(name)
            assertTrue(
                snapshot.patchHas(colors.surface, y = 4 until 20, side = 6),
                "$name: ряд фильтров лежит не на подложке содержимого",
            )
        }
    }

    /**
     * Выбранный чип отличается от невыбранного.
     *
     * Проверка кажется тавтологией ровно до того дня, когда выбранный фильтр получит
     * вид остальных — и ряд молча перестанет отвечать на вопрос «что я сейчас вижу».
     */
    @Test
    fun выбранный_фильтр_виден() {
        val first = capture("ряд-фильтр-1", WIDTH, 44, dark = false) { filters("Все") }
        val second = capture("ряд-фильтр-2", WIDTH, 44, dark = false) { filters("Пропущенные") }
        assertTrue(first.difference(second) > 0.0, "смена фильтра ничего не изменила на экране")
    }

    /**
     * Переключатель режимов прижат к правому краю и показывает выбранное.
     *
     * Правый край — из макета: чипы прокручиваются, режим остаётся на виду. Проверяется
     * салатовой заливкой выбранного сегмента в правой четверти ряда.
     */
    @Test
    fun переключатель_режимов_прижат_вправо_и_показывает_выбранное() {
        val open = capture("ряд-режим-1", WIDTH, 44, dark = false) { modes("Открытое") }
        val personal = capture("ряд-режим-2", WIDTH, 44, dark = false) { modes("Личное") }

        assertTrue(
            open.patchHas(TimaColors.light.navigation, x = WIDTH * 3 / 4 until WIDTH, side = 4),
            "переключателя нет у правого края ряда",
        )
        assertTrue(
            open.difference(personal) > 0.0,
            "смена режима ничего не изменила — выбранный сегмент неотличим",
        )
    }

    /**
     * У окна 3 второй ряд есть, и в нём только режим.
     *
     * Единственная сборка целого окна в этом файле: она проверяет, что ряд вообще
     * дошёл до окна, а не остался компонентом без вызывающего — ровно то, чем `secondRow`
     * и был до 2026-09-02, когда параметр существовал, а пользовался им никто.
     */
    @Test
    fun у_медиа_второй_ряд_дошёл_до_окна() {
        val media = capture("медиа-второй-ряд", WIDTH, HEIGHT, dark = false) {
            Stage(column = { MediaWindow({}, {}, {}, {}) })
        }
        val social = capture("социум-без-ряда", WIDTH, HEIGHT, dark = false) {
            Stage(column = { SocialWindow({}, {}, {}, {}) })
        }
        // Полоса сразу под вкладками: у «Медиа» там ряд с переключателем, у «Социума»
        // сразу содержимое. Одинаковыми они быть не могут.
        assertTrue(
            media.difference(social) > 0.0,
            "«Медиа» и «Социум» нарисованы одинаково — второго ряда у «Медиа» нет",
        )
        assertTrue(
            media.patchHas(TimaColors.light.navigation, y = UNDER_TABS, x = WIDTH / 2 until WIDTH, side = 4),
            "под вкладками «Медиа» нет салатового сегмента — переключатель режимов не встал",
        )
    }

    @Composable
    private fun filters(selected: String) = Box(Modifier.fillMaxSize()) {
        FilterRow(listOf("Все", "Контактов", "Неизвестные", "Пропущенные"), selected, {})
    }

    @Composable
    private fun modes(selected: String) = Box(Modifier.fillMaxSize()) {
        FilterRow(
            items = listOf("Медиа", "Сообщения"),
            selected = "Медиа",
            onPick = {},
            trailing = { ModeSwitch(listOf("Открытое", "Личное"), selected, {}) },
        )
    }

    private companion object {
        const val WIDTH = 380
        const val HEIGHT = 800

        /**
         * Полоса, где живёт второй ряд: сразу под шапкой и рядом вкладок.
         *
         * Границы взяты с запасом в обе стороны намеренно. Точная высота — сумма
         * отступов шапки, плашки и вкладок; проверять её здесь значило бы ронять тест
         * от правки любого отступа, а утверждение теста не про высоту.
         */
        val UNDER_TABS = 90 until 140
    }
}
