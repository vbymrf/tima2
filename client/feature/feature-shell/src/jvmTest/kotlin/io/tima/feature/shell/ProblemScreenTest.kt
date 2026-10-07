package io.tima.feature.shell

import io.tima.core.ui.TimaColors
import io.tima.testui.capture
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * «Сообщить о проблеме» в снимке. Переключатели «Когда это началось» и «О чём» — строками с
 * раскрытием, как в «Уведомлениях» (заказчик 2026-10-07): справа выбранное и зелёная стрелка.
 */
class ProblemScreenTest {

    @Test
    fun переключатели_строками_со_стрелкой() {
        val shot = capture("проблема-переключатели", WIDTH, HEIGHT, dark = false) {
            ProblemScreen(
                state = ProblemState(text = "не приходят сообщения"),
                onText = {}, onKind = {}, onBegan = {}, onShow = {}, onSend = {},
            )
        }
        // Кнопка «Отправить» тоже зелёная, но она в самом верху; стрелки строк — ниже неё.
        assertTrue(
            shot.patchHas(TimaColors.light.navigation, x = WIDTH - 48 until WIDTH, y = BELOW_BUTTON until HEIGHT, side = 2),
            "у переключателей нет зелёной стрелки",
        )
    }

    private companion object {
        const val WIDTH = 400
        const val HEIGHT = 900
        const val BELOW_BUTTON = 100
    }
}
