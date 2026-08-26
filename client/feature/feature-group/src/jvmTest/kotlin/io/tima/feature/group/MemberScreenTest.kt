package io.tima.feature.group

import io.tima.domain.chat.GroupMember
import io.tima.domain.chat.GroupRole
import io.tima.testui.FOREIGN_BACKGROUND
import io.tima.testui.bothThemes
import io.tima.testui.capture
import io.tima.testui.theme
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Состав группы в снимках.
 *
 * Главная проверка здесь — **предупреждение о несменившемся ключе видно**. Человек нажал
 * «Исключить», строка из списка пропала, и если больше ничего не изменилось, он уверен, что
 * закрыл доступ. А исключённый в это время читает переписку дальше.
 */
class MemberScreenTest {

    private val owner = GroupMember("u-1", GroupRole.Owner, bannedUntil = null)
    private val member = GroupMember("u-2", GroupRole.Member, bannedUntil = null)

    private val ownerMembers = MembersState(
        members = listOf(owner, member),
        myRole = GroupRole.Owner,
    )

    /** Экран заливает свой фон (находка 29). */
    @Test
    fun экран_заливает_свой_фон() {
        for ((name, snapshot) in bothThemes("состав-фон", WIDTH, HEIGHT, backdrop = FOREIGN_BACKGROUND) {
            screen(ownerMembers)
        }) {
            assertTrue(!snapshot.has(FOREIGN_BACKGROUND), "$name: сквозь экран видна подложка")
        }
    }

    @Test
    fun экран_рисуется_в_обеих_темах() {
        val snapshots = bothThemes("состав", WIDTH, HEIGHT) { screen(ownerMembers) }
        val difference = snapshots.getValue("светлая").difference(snapshots.getValue("тёмная"))
        assertTrue(difference > 0.10, "темы расходятся лишь на ${(difference * 100).toInt()}%")
    }

    @Test
    fun у_подокна_шапка_без_плашки() {
        for ((name, snapshot) in bothThemes("состав-шапка", WIDTH, HEIGHT) { screen(ownerMembers) }) {
            assertTrue(
                !snapshot.patchHas(theme(name).navigation, y = 0 until 40, side = 20),
                "$name: у подокна появилась плашка окна",
            )
        }
    }

    @Test
    fun предупреждение_о_ключе_видно() {
        val normally = capture("состав-обычно", WIDTH, HEIGHT, dark = false) { screen(ownerMembers) }
        val withWarning = capture("состав-ключ", WIDTH, HEIGHT, dark = false) {
            screen(ownerMembers.copy(warning = "Состав изменён, но ключ не сменился: нет связи"))
        }
        assertTrue(
            withWarning.difference(normally) > 0.0,
            "человек не увидит, что исключённый читает переписку дальше",
        )
    }

    /**
     * Участнику управление составом не показывается.
     *
     * Кнопка, отвечающая отказом, сообщает о запрете уже после нажатия — то есть предлагает
     * человеку то, чего он не может.
     */
    @Test
    fun участник_не_видит_управления() {
        val atOwner = capture("состав-владелец", WIDTH, HEIGHT, dark = false) { screen(ownerMembers) }
        val atMember = capture("состав-участник", WIDTH, HEIGHT, dark = false) {
            screen(ownerMembers.copy(myRole = GroupRole.Member))
        }
        assertTrue(atMember.difference(atOwner) > 0.0, "управление показано тому, кому нельзя")
    }

    @Test
    fun пустой_состав_объясняет_себя() {
        // Пустой экран без слов читается как поломка. Здесь он говорит, что делать.
        val empty = capture("состав-пусто", WIDTH, HEIGHT, dark = false) {
            screen(MembersState(myRole = GroupRole.Owner))
        }
        val withPeople = capture("состав-люди", WIDTH, HEIGHT, dark = false) { screen(ownerMembers) }
        assertTrue(empty.difference(withPeople) > 0.0, "пустой состав нарисован так же, как полный")
    }

    private companion object {
        const val WIDTH = 380
        const val HEIGHT = 600

        @androidx.compose.runtime.Composable
        fun screen(state: MembersState) = MemberScreen(
            state = state,
            onNumber = {},
            onInvite = {},
            onRemove = {},
            onBack = {},
        )
    }
}
