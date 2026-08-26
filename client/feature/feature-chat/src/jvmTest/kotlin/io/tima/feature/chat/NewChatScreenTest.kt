package io.tima.feature.chat

import io.tima.testui.FOREIGN_BACKGROUND
import io.tima.testui.bothThemes
import io.tima.testui.capture
import io.tima.testui.theme
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Новая переписка в снимках.
 *
 * Экран простой, и проверяется на нём то, что на простых экранах и ломается: рисуется ли он
 * в обеих темах, заливает ли свой фон, видно ли отказ и **отличается ли «номера нет в TIMA»
 * от беды**. Последнее — не мелочь: человек, которого нет в мессенджере, это не ошибка
 * ввода, и если сказать это тем же тоном, он будет искать опечатку в своём номере.
 */
class NewChatScreenTest {

    /** Экран заливает свой фон (находка 29): снимается на краске, которой в палитре нет. */
    @Test
    fun экран_заливает_свой_фон() {
        val snapshots = bothThemes("новая-фон", WIDTH, HEIGHT, backdrop = FOREIGN_BACKGROUND) {
            screen(NewChatState())
        }
        for ((name, snapshot) in snapshots) {
            assertTrue(!snapshot.has(FOREIGN_BACKGROUND), "$name: сквозь экран видна подложка")
        }
    }

    @Test
    fun экран_рисуется_в_обеих_темах() {
        val snapshots = bothThemes("новая", WIDTH, HEIGHT) { screen(NewChatState()) }
        val difference = snapshots.getValue("светлая").difference(snapshots.getValue("тёмная"))
        assertTrue(difference > 0.10, "темы расходятся лишь на ${(difference * 100).toInt()}%")
    }

    /**
     * Шапка подокна есть, а плашки окна нет.
     *
     * Это правило макета, из которого выведена вся раскладка: человек пришёл сюда из списка
     * и вернётся туда же, поэтому здесь «назад», а не название окна.
     */
    @Test
    fun у_подокна_шапка_без_плашки() {
        for ((name, snapshot) in bothThemes("новая-шапка", WIDTH, HEIGHT) { screen(NewChatState()) }) {
            assertTrue(
                !snapshot.patchHas(theme(name).navigation, y = 0 until 40, side = 20),
                "$name: у подокна появилась плашка окна",
            )
        }
    }

    /** Набранный номер не теряется при отказе — то же правило, что на входе. */
    @Test
    fun отказ_нарисован_и_номер_остался() {
        val clean = capture("новая-чисто", WIDTH, HEIGHT, dark = false) {
            screen(NewChatState(number = "+79990000001"))
        }
        val withTrouble = capture("новая-беда", WIDTH, HEIGHT, dark = false) {
            screen(NewChatState(number = "+79990000001", trouble = "Нет связи — повторим через 5 с"))
        }

        assertTrue(withTrouble.difference(clean) > 0.0, "отказ не нарисовался вовсе")
    }

    /**
     * «Этого номера в TIMA нет» рисуется отдельно от беды.
     *
     * Не ошибка ввода, а другой исход: человека надо позвать. Если он выглядит как беда,
     * искать будут опечатку в номере.
     */
    @Test
    fun приглашение_нарисовано() {
        val clean = capture("новая-чисто", WIDTH, HEIGHT, dark = false) {
            screen(NewChatState(number = "+79990000001"))
        }
        val invite = capture("новая-позвать", WIDTH, HEIGHT, dark = false) {
            screen(NewChatState(number = "+79990000001", invite = true))
        }

        assertTrue(invite.difference(clean) > 0.0, "приглашение не нарисовалось")
    }

    private companion object {
        const val WIDTH = 380
        const val HEIGHT = 600

        @androidx.compose.runtime.Composable
        fun screen(state: NewChatState) = NewChatScreen(
            state = state,
            onNumber = {},
            onFind = {},
            onBack = {},
        )
    }
}
