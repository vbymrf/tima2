package io.tima.feature.shell

import io.tima.core.ui.TimaColors
import io.tima.testui.capture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Панель переходов по пробе И1 (`пробы-окно-переходов.html`, принята заказчиком 2026-10-07): окна в
 * общей зелёной рамке, аккаунты сеткой, «Добавить» — в правой колонке последней строки.
 */
class SwitchingLookTest {

    @Test
    fun добавить_всегда_в_правой_колонке() {
        // Три аккаунта: «Добавить» в той же строке, справа, между ними пустые места.
        assertEquals(listOf(listOf<Int?>(0, 1, 2, null, ADD_SLOT)), accountSlots(3, add = true))
        // Пять аккаунтов заняли строку целиком — «Добавить» на новой строке, справа.
        assertEquals(
            listOf(listOf<Int?>(0, 1, 2, 3, 4), listOf<Int?>(null, null, null, null, ADD_SLOT)),
            accountSlots(5, add = true),
        )
        // Семь: пять и два, «Добавить» справа во второй строке.
        assertEquals(listOf<Int?>(5, 6, null, null, ADD_SLOT), accountSlots(7, add = true)[1])
        // Виртуальный не заводит виртуальных — «Добавить» нет, строка добита пустыми местами.
        assertEquals(listOf(listOf<Int?>(0, null, null, null, null)), accountSlots(1, add = false))
    }

    @Test
    fun окна_в_зелёной_рамке_и_аккаунты_сеткой() {
        val shot = capture("переходы-и1", WIDTH, HEIGHT, dark = false) {
            WindowSwitchingScreen(
                current = Window.Phone,
                name = "Анна Смирнова",
                alias = "@anna",
                phone = "+7 999 000-00-01",
                onSelect = {},
                onClose = {},
                inCall = true,
                onCall = {},
                callPeer = "Ольга",
                accounts = listOf("a" to "Анна Смирнова", "b" to "Работа", "c" to "@shop"),
                currentAccount = "a",
                unsent = mapOf("b" to 2),
                news = mapOf("a" to 3, "c" to 5),
                onNewAccount = {},
                onSettings = {},
            )
        }
        val colors = TimaColors.light
        // Зелёная окантовка окон — у левого края панели, ниже шапки.
        assertTrue(shot.patchHas(colors.navigation, x = 0 until 30, y = 200 until HEIGHT, side = 2), "нет зелёной рамки окон")
        // Мягкая подложка внутри рамки.
        assertTrue(shot.patchHas(colors.softAccent, x = 40 until WIDTH - 40, y = 200 until HEIGHT, side = 6), "нет подложки окон")
    }

    private companion object {
        const val WIDTH = 380
        const val HEIGHT = 1100
    }
}
