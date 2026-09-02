package io.tima.feature.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.tima.core.ui.FormatTima
import io.tima.core.ui.Stage
import io.tima.core.ui.TimaContrast
import io.tima.core.ui.TimaColors
import io.tima.testui.FOREIGN_BACKGROUND
import io.tima.testui.Snapshot
import io.tima.testui.bothThemes
import io.tima.testui.capture
import io.tima.testui.theme
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Второй ряд в снимках: фильтры и режимы под вкладками.
 *
 * Проверяются **утверждения макета и решения заказчика**, а не картинка. Ряд заведён
 * 2026-09-02 сразу по всем местам, где он есть в макете, и все они делят один
 * компонент, поэтому его правила проверяются здесь один раз, а не в пяти окнах.
 */
class SecondRowTest {

    /**
     * Ряд фильтров стоит на **той же серой подложке**, что шапка и вкладки.
     *
     * Решение заказчика: «сделай фон всех вкладок и подкладок единым — серый». Первая
     * редакция ставила ряд на подложку содержимого — так в макете, где `.строка-фильтров`
     * фона не имеет, — и при появлении второй строки получалось два разных фона друг под
     * другом. Расхождение с макетом записано в `интерфейс.md §1`.
     */
    @Test
    fun ряд_фильтров_на_общей_серой_подложке() {
        for ((name, snapshot) in bothThemes("ряд-фильтров", WIDTH, 44) { filters("Все") }) {
            val colors = theme(name)
            // Сравнивать надо с наложением, а не с самим токеном: в тёмной теме
            // `functional` полупрозрачен, и на снимке он уже смешан с поверхностью.
            val gray = TimaContrast.overlay(colors.functional, colors.surface)
            assertTrue(
                snapshot.patchHas(gray, y = 2 until 18, x = 0 until 8, side = 4),
                "$name: ряд фильтров лежит не на общей серой подложке",
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
     * Режим набран теми же ярлычками, что и фильтры, и показывает выбранное.
     *
     * Решение заказчика 2026-09-02. Прежняя редакция рисовала сегменты в общей пилюле,
     * чтобы отличать «переключить состояние» от «сузить список»; отличие снято.
     */
    @Test
    fun режим_набран_ярлычками_и_показывает_выбранное() {
        val open = capture("ряд-режим-1", WIDTH, 44, dark = false) { modes("Открытое") }
        val personal = capture("ряд-режим-2", WIDTH, 44, dark = false) { modes("Личное") }
        assertTrue(
            open.difference(personal) > 0.0,
            "смена режима ничего не изменила — выбранный ярлычок неотличим",
        )
    }

    /**
     * Не поместилось — переносится на новую строку, а не прячется за краем.
     *
     * **Это ровно тот случай, который заказчик увидел глазами:** на колонке ПК в 340
     * точек чип «Каталог» исчезал, и выглядело это не как «прокрутите», а как «его нет».
     * Прокрутка вбок без видимого признака и есть способ спрятать.
     *
     * Проверяется высотой: тот же ряд на узкой полосе обязан быть **выше**, чем на
     * широкой. Высота — единственное, что отличает перенос от обрезки: обрезанный ряд
     * остаётся одной строкой при любой ширине.
     */
    @Test
    fun не_поместившееся_переносится_а_не_прячется() {
        val column = FormatTima.DESKTOP_COLUMN.value.toInt()
        val narrow = capture("ряд-перенос-узкий", column, 120, dark = false, backdrop = FOREIGN_BACKGROUND) {
            collections()
        }
        val wide = capture("ряд-перенос-широкий", 900, 120, dark = false, backdrop = FOREIGN_BACKGROUND) {
            collections()
        }
        assertTrue(
            height(wide) in 1 until height(narrow),
            "на колонке ПК ряд не перенёсся: высота там ${height(narrow)}, на широкой ${height(wide)}",
        )
    }

    /**
     * У окна 3 переключатель режима стоит **в ряду вкладок**, а не под ними.
     *
     * «Лента / Слайды» — состояние всего окна: оно переживает смену вкладки, поэтому и
     * место ему в одном ряду с вкладками (решение заказчика — «положи правее вкладок»),
     * а не во втором ряду, который принадлежит вкладке.
     *
     * Заодно это единственная сборка целого окна в файле: она проверяет, что ряд вообще
     * дошёл до окна, а не остался компонентом без вызывающего — ровно тем, чем параметр
     * `secondRow` и был до 2026-09-02.
     */
    @Test
    fun у_медиа_режим_стоит_в_ряду_вкладок() {
        val media = capture("медиа-ряд-вкладок", WIDTH, HEIGHT, dark = false) {
            Stage(column = { MediaWindow({}, {}, {}, {}) })
        }
        val social = capture("социум-без-режима", WIDTH, HEIGHT, dark = false) {
            Stage(column = { SocialWindow({}, {}, {}, {}) })
        }
        assertTrue(
            media.difference(social) > 0.0,
            "«Медиа» и «Социум» нарисованы одинаково — переключателя режима нет",
        )
        // Правая половина ряда вкладок: у «Социума» там пусто, у «Медиа» — залитая
        // «Лента». Полоса взята с запасом: утверждение не про точную высоту ряда.
        assertTrue(
            media.patchHas(TimaColors.light.navigation, y = TAB_ROW, x = WIDTH / 2 until WIDTH, side = 4),
            "в правой части ряда вкладок «Медиа» нет залитого ярлычка режима",
        )
        assertTrue(
            !social.patchHas(TimaColors.light.navigation, y = TAB_ROW, x = WIDTH / 2 until WIDTH, side = 4),
            "у «Социума» в той же полосе что-то залито — значит проверка ловит не режим",
        )
    }

    /** Высота серой полосы управления: до какой строки сверху идёт функциональный фон. */
    private fun height(snapshot: Snapshot): Int {
        var last = 0
        for (y in 0 until snapshot.height) {
            if (Snapshot.close(snapshot.color(2, y), TimaColors.light.functional)) last = y + 1
        }
        return last
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

    /** Ряд «Коллекций» окна 5 целиком: три подвкладки и переключатель контура. */
    @Composable
    private fun collections() = Box(Modifier.fillMaxSize()) {
        FilterRow(
            items = listOf("Медиа", "Сообщения", "Каталог"),
            selected = "Медиа",
            onPick = {},
            trailing = { ModeSwitch(listOf("Открытое", "Личное"), "Личное", {}) },
        )
    }

    private companion object {
        const val WIDTH = 380
        const val HEIGHT = 800

        /**
         * Полоса, где живёт ряд вкладок: сразу под шапкой.
         *
         * Границы взяты с запасом в обе стороны намеренно. Точная высота — сумма
         * отступов шапки и плашки; проверять её здесь значило бы ронять тест от правки
         * любого отступа, а утверждение теста не про высоту.
         */
        val TAB_ROW = 60 until 100
    }
}
