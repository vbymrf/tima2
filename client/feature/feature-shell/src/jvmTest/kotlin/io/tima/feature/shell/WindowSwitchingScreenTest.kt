package io.tima.feature.shell

import io.tima.core.ui.TimaColors
import io.tima.testui.Snapshot
import io.tima.testui.capture
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Панель переключения окон на телефоне — решения заказчика 2026-09-15.
 *
 * **Панель не выше 85 % экрана, остальное прокручивается.** До этого прокрутки не было
 * вовсе: на телефоне семь окон, шапка, аккаунты и настройки не помещались в высоту, и
 * низ уезжал за край без способа до него добраться. Увидеть это можно было, только
 * развернув телефон, — снимки рисовались в размере, где всё влезало.
 *
 * Пиксели здесь отвечают на один вопрос: осталась ли над панелью полоса затемнённого
 * окна. Осталась — значит панель ограничена и её содержимое живёт в прокрутке; ушла до
 * верха — панель растёт по содержимому, и прокручивать нечему.
 */
class WindowSwitchingScreenTest {

    @Test
    fun на_телефоне_панель_оставляет_сверху_полосу_окна_под_ней() {
        val shot = capture("переключение-телефон", PHONE_W, PHONE_H, dark = false) {
            WindowSwitchingScreen(
                current = Window.Phone,
                name = "Пётр Смирнов",
                alias = "@petr_smirnov",
                phone = "+7 916 000-11-22",
                onSelect = {},
                onClose = {},
                onSettings = {},
                onProfile = {},
                onNewAccount = {},
                accounts = listOf("a" to "Пётр", "b" to "Лена Г."),
                currentAccount = "a",
            )
        }
        // Затемнённая полоса: поверхность темы под слоем DIM. Панель же — чистая поверхность.
        val surface = TimaColors.light.surface
        val topIsDimmed = !Snapshot.close(shot.color(PHONE_W / 2, 4), surface)
        assertTrue(topIsDimmed, "верх экрана залит поверхностью панели — панель дотянулась до самого верха, ограничения нет")

        // Граница панели: первая строка сверху, где середина экрана — уже поверхность.
        val panelTop = (0 until PHONE_H).first { Snapshot.close(shot.color(PHONE_W / 2, it), surface) }
        val floor = (PHONE_H * (1 - 0.85f)).toInt()
        assertTrue(
            panelTop >= floor - 2,
            "панель начинается на $panelTop, а обязана не выше $floor (15 % экрана остаются окну под ней)",
        )
    }

    @Test
    fun на_широком_экране_панель_не_растягивается_до_потолка_без_нужды() {
        // Содержимого мало — панель по содержимому, а не по 85 %: пустая панель во всю
        // высоту обещает список, которого нет.
        val shot = capture("переключение-пк", 800, 1400, dark = false) {
            WindowSwitchingScreen(
                current = Window.Phone,
                name = "Пётр",
                alias = "",
                onSelect = {},
                onClose = {},
            )
        }
        val surface = TimaColors.light.surface
        val panelTop = (0 until 1400).first { Snapshot.close(shot.color(400, it), surface) }
        assertTrue(panelTop > 1400 * 0.15f, "панель заняла 85 % высоты при малом содержимом — ограничение сработало как растяжка")
    }

    private companion object {
        // Телефонная высота нарочно мала: 640 точек — ровно там, где список не влезал.
        const val PHONE_W = 360
        const val PHONE_H = 640
    }
}
