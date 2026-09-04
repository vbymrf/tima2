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
            screen(NewGroupState(step = Step.Naming, title = "Поход"))
        }
        val withNumbers = capture("группа-новая-номера", WIDTH, HEIGHT, dark = false) {
            screen(NewGroupState(step = Step.Naming, title = "Поход", numbers = listOf("+79990000002")))
        }
        assertTrue(withNumbers.difference(empty) > 0.0, "добавленный номер не нарисовался")
    }

    @Test
    fun непозванные_нарисованы_и_отдельно_от_беды() {
        val clean = capture("группа-новая-чисто", WIDTH, HEIGHT, dark = false) {
            screen(NewGroupState(step = Step.Naming, title = "Поход"))
        }
        val notInvited = capture("группа-новая-непозванные", WIDTH, HEIGHT, dark = false) {
            screen(NewGroupState(step = Step.Naming, title = "Поход", notInvited = listOf("+70000000000")))
        }
        val trouble = capture("группа-новая-беда", WIDTH, HEIGHT, dark = false) {
            screen(NewGroupState(step = Step.Naming, title = "Поход", trouble = "Нет связи — повторим через 5 с"))
        }

        assertTrue(notInvited.difference(clean) > 0.0, "непозванные не нарисовались")
        assertTrue(
            notInvited.difference(trouble) > 0.0,
            "непозванные выглядят так же, как беда: человек станет повторять сделанное",
        )
    }

    /** Четыре раздела на входе, и три из них показаны, а не спрятаны. */
    @Test
    fun на_входе_четыре_раздела() {
        val sections = capture("мастер-разделы", WIDTH, HEIGHT, dark = false) {
            screen(NewGroupState(step = Step.Section))
        }
        val kind = capture("мастер-вид", WIDTH, HEIGHT, dark = false) {
            screen(NewGroupState(step = Step.Kind))
        }
        assertTrue(sections.difference(kind) > 0.0, "шаги мастера выглядят одинаково")
    }

    /**
     * У личной группы открытого вступления нет, и экран это ПОКАЗЫВАЕТ.
     *
     * Разница снимков доказывает, что состояние строки различается: у публичной строка
     * «Открытая» выбираема, у личной — приглушена и подписана.
     */
    @Test
    fun у_личной_группы_открытого_вступления_нет() {
        val personal = capture("мастер-вступление-личная", WIDTH, HEIGHT, dark = false) {
            screen(NewGroupState(step = Step.Joining, kind = io.tima.domain.chat.GroupKind.Personal))
        }
        val public = capture("мастер-вступление-публичная", WIDTH, HEIGHT, dark = false) {
            screen(NewGroupState(step = Step.Joining, kind = io.tima.domain.chat.GroupKind.Public))
        }
        assertTrue(
            personal.difference(public) > 0.0,
            "у личной и публичной группы шаг вступления выглядит одинаково — значит запрет не показан",
        )
    }

    private companion object {
        const val WIDTH = 380
        const val HEIGHT = 700

        @androidx.compose.runtime.Composable
        fun screen(state: NewGroupState) = NewGroupScreen(
            state = state,
            onSection = {},
            onKind = {},
            onJoining = {},
            onForward = {},
            onExplain = {},
            onTitle = {},
            onDescription = {},
            onNumber = {},
            onAddNumber = {},
            onRemoveNumber = {},
            onCreate = {},
            onBack = {},
        )
    }
}
