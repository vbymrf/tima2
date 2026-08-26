package io.tima.feature.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import io.tima.core.ui.Name
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.tima.testui.bothThemes
import io.tima.testui.theme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Настройки в снимках.
 *
 * Проверяются **утверждения макета**, а не картинка: список разделов виден целиком,
 * открытый раздел закрывает список собой, шапка называет, где человек находится.
 *
 * Экран собран глазами на живом приложении 2026-08-26 и выглядел верно. Тест нужен не
 * вместо этого, а после: увиденное однажды не защищает от следующей правки.
 */
class SettingsScreenTest {

    /**
     * Перечень и макет не разъезжаются.
     *
     * Разделы и их порядок взяты из `doc/Layout-UI-light/пк/настройки.html`: там их
     * одиннадцать. Двенадцатый — «Обновление», добавленное решением заказчика 2026-08-26
     * рядом с «О приложении»; расхождение названо в самом перечне и правится решением, а
     * не молча. Пункт, добавленный мимо этого, уронит тест — и правильно, потому что
     * решает здесь макет.
     */
    @Test
    fun разделов_двенадцать_в_четырёх_группах() {
        assertEquals(
            12,
            SettingsItem.entries.size,
            "список разошёлся с макетом: одиннадцать пунктов оттуда плюс «Обновление»",
        )
        assertEquals(4, SettingsGroup.entries.size)

        // Пункты одной группы обязаны идти подряд: экран печатает заголовок группы перед
        // её пунктами, и группа, разорванная чужим пунктом, напечатает заголовок дважды.
        val порядок = SettingsItem.entries.map { it.group.ordinal }
        assertEquals(порядок.sorted(), порядок, "пункты группы разорваны чужим пунктом")
    }

    /** У каждого пункта своя надпись и свой знак: два одинаковых знака неразличимы в списке. */
    @Test
    fun надписи_и_знаки_не_повторяются() {
        val надписи = SettingsItem.entries.map { it.title }
        assertEquals(надписи.size, надписи.distinct().size, "две одинаковые надписи в списке")
        val знаки = SettingsItem.entries.map { it.glyph }
        assertEquals(знаки.size, знаки.distinct().size, "два одинаковых знака в списке")
    }

    /**
     * Список рисуется целиком, а не одним экраном заголовков.
     *
     * Двенадцать строк в четырёх группах на телефон не влезают, и они обязаны
     * прокручиваться. Проверяется тем, что нижняя часть экрана занята: пустой низ
     * означал бы, что список оборвался на середине.
     */
    @Test
    fun список_занимает_экран_целиком() {
        for ((name, snapshot) in bothThemes("настройки-список", WIDTH, HEIGHT) { список() }) {
            assertTrue(
                snapshot.patchHas(theme(name).text, y = HEIGHT / 2 until HEIGHT, side = 2),
                "$name: нижняя половина списка пуста — строки не дорисованы",
            )
        }
    }

    /**
     * Открытый раздел закрывает список собой.
     *
     * Иначе на телефоне список и содержимое встали бы друг под другом, и до содержимого
     * пришлось бы прокручивать мимо одиннадцати строк.
     */
    @Test
    fun открытый_раздел_вытесняет_список() {
        val списком = bothThemes("настройки-список", WIDTH, HEIGHT) { список() }
        val разделом = bothThemes("настройки-раздел", WIDTH, HEIGHT) { раздел() }
        for ((name, _) in списком) {
            assertTrue(
                списком.getValue(name).pixels().toList() != разделом.getValue(name).pixels().toList(),
                "$name: открытый раздел ничего не изменил — список не вытеснен",
            )
        }
    }

    @Composable
    private fun список() = Box(Modifier.fillMaxSize()) {
        SettingsScreen(opened = null, onOpen = {}, onBack = {}) { }
    }

    @Composable
    private fun раздел() = Box(Modifier.fillMaxSize()) {
        SettingsScreen(opened = SettingsItem.UPDATE, onOpen = {}, onBack = {}) { Name(it.title) }
    }

    private companion object {
        const val WIDTH = 420
        const val HEIGHT = 900
    }
}
