package io.tima.feature.group

import io.tima.testui.FOREIGN_BACKGROUND
import io.tima.testui.bothThemes
import io.tima.testui.capture
import io.tima.testui.theme
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Новая группа в снимках.
 *
 * Кроме обязательных заслонов — фон и темы — здесь проверяется различие, ради которого
 * экран и устроен так: **непозванные номера рисуются не как беда**. Группа создана, и
 * красный текст про сбой заставил бы человека повторять сделанное.
 */
class NewGroupScreenTest {

    /** Экран заливает свой фон (находка 29): снимается на краске, которой в палитре нет. */
    @Test
    fun экран_заливает_свой_фон() {
        for ((name, snapshot) in bothThemes("группа-новая-фон", WIDTH, HEIGHT, backdrop = FOREIGN_BACKGROUND) {
            screen(NewGroupState())
        }) {
            assertTrue(!snapshot.has(FOREIGN_BACKGROUND), "$name: сквозь экран видна подложка")
        }
    }

    @Test
    fun экран_рисуется_в_обеих_темах() {
        val snapshots = bothThemes("группа-новая", WIDTH, HEIGHT) { screen(NewGroupState()) }
        val difference = snapshots.getValue("светлая").difference(snapshots.getValue("тёмная"))
        assertTrue(difference > 0.10, "темы расходятся лишь на ${(difference * 100).toInt()}%")
    }

    /** Подокно: есть «назад», нет плашки окна. Человек вернётся туда, откуда пришёл. */
    @Test
    fun у_подокна_шапка_без_плашки() {
        for ((name, snapshot) in bothThemes("группа-новая-шапка", WIDTH, HEIGHT) { screen(NewGroupState()) }) {
            assertTrue(
                !snapshot.patchHas(theme(name).navigation, y = 0 until 40, side = 20),
                "$name: у подокна появилась плашка окна",
            )
        }
    }

    @Test
    fun набранные_номера_видны_списком() {
        val empty = capture("группа-новая-пусто", WIDTH, HEIGHT, dark = false) {
            screen(NewGroupState(title = "Поход"))
        }
        val withNumbers = capture("группа-новая-номера", WIDTH, HEIGHT, dark = false) {
            screen(NewGroupState(title = "Поход", numbers = listOf("+79990000002")))
        }
        assertTrue(withNumbers.difference(empty) > 0.0, "добавленный номер не нарисовался")
    }

    @Test
    fun непозванные_нарисованы_и_отдельно_от_беды() {
        val clean = capture("группа-новая-чисто", WIDTH, HEIGHT, dark = false) {
            screen(NewGroupState(title = "Поход"))
        }
        val notInvited = capture("группа-новая-непозванные", WIDTH, HEIGHT, dark = false) {
            screen(NewGroupState(title = "Поход", notInvited = listOf("+70000000000")))
        }
        val trouble = capture("группа-новая-беда", WIDTH, HEIGHT, dark = false) {
            screen(NewGroupState(title = "Поход", trouble = "Нет связи — повторим через 5 с"))
        }

        assertTrue(notInvited.difference(clean) > 0.0, "непозванные не нарисовались")
        assertTrue(
            notInvited.difference(trouble) > 0.0,
            "непозванные выглядят так же, как беда: человек станет повторять сделанное",
        )
    }

    private companion object {
        const val WIDTH = 380
        const val HEIGHT = 700

        @androidx.compose.runtime.Composable
        fun screen(state: NewGroupState) = NewGroupScreen(
            state = state,
            onTitle = {},
            onNumber = {},
            onAddNumber = {},
            onRemoveNumber = {},
            onCreate = {},
            onBack = {},
        )
    }
}
