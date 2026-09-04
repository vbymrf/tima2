package io.tima.feature.group

import io.tima.domain.chat.GroupCreateStep
import io.tima.domain.chat.GroupKind
import io.tima.domain.chat.GroupKeyRotator
import io.tima.domain.chat.GroupMember
import io.tima.domain.chat.GroupRegistry
import io.tima.domain.chat.GroupRole
import io.tima.domain.chat.GroupsStep
import io.tima.domain.chat.ManageGroupMembers
import io.tima.domain.chat.MemberStep
import io.tima.domain.chat.MembersStep
import io.tima.domain.chat.RotateStep
import io.tima.domain.chat.UserDirectory
import io.tima.domain.chat.UserLookup
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Состав группы глазами экрана.
 *
 * Главное, что здесь проверяется, — не список, а два состояния, которые нельзя путать:
 * «состав изменён и доступ закрыт» и «состав изменён, а ключ прежний». Второе означает, что
 * исключённый читает переписку прямо сейчас.
 */
class MembersStoreTest {

    private val group = "gggggggg-0000-0000-0000-000000000001"
    private val me = "u-1"

    @Test
    fun состав_читается_и_моя_роль_видна() = runTest {
        val store = store(this)
        store.refresh()
        runCurrent()

        assertEquals(2, store.state.value.members.size)
        assertEquals(GroupRole.Owner, store.state.value.myRole)
        assertTrue(store.state.value.memberEdit)
    }

    @Test
    fun member_not_edits_members() = runTest {
        // Экран по этому признаку прячет управление: кнопка, отвечающая отказом, сообщает
        // человеку о запрете уже после нажатия.
        val store = store(this, myRole = GroupRole.Member)
        store.refresh()
        runCurrent()

        assertFalse(store.state.value.memberEdit)
    }

    @Test
    fun несменившийся_ключ_показывается_предупреждением_а_не_бедой() = runTest {
        val store = store(this, rotation = RotateStep.Offline(4_000))
        store.remove("u-2")
        runCurrent()

        val state = store.state.value
        assertNotNull(state.warning, "человек не узнает, что доступ не закрыт")
        assertNull(state.trouble, "это не ошибка: состав действительно изменён")
    }

    @Test
    fun успешное_исключение_не_оставляет_предупреждения() = runTest {
        val store = store(this)
        store.remove("u-2")
        runCurrent()

        assertNull(store.state.value.warning)
        assertNull(store.state.value.trouble)
    }

    @Test
    fun незарегистрированный_номер_не_стирает_набранное() = runTest {
        // Стереть набранный номер при отказе — значит заставить набирать заново то, что и
        // так под рукой. Та же причина, что у поля входа.
        val store = store(this)
        store.changedNumber("+70000000000")
        store.invite()
        runCurrent()

        assertEquals("+70000000000", store.state.value.number)
        assertTrue("нет" in (store.state.value.trouble ?: ""))
    }

    @Test
    fun удачное_приглашение_очищает_поле() = runTest {
        val store = store(this)
        store.changedNumber("+79990000002")
        store.invite()
        runCurrent()

        assertEquals("", store.state.value.number)
    }

    @Test
    fun второе_нажатие_не_шлёт_второй_запрос() = runTest {
        // Иначе один нетерпеливый человек порождает две ротации ключа — и лишнюю выдачу
        // обёрток всем устройствам группы.
        val groups = FakeGroups()
        val store = store(this, groups = groups)
        store.changedNumber("+79990000002")
        store.invite()
        store.invite()
        runCurrent()

        assertEquals(1, groups.additions)
    }

    // ── подделки ────────────────────────────────────────────────────────────

    private fun store(
        scope: TestScope,
        myRole: GroupRole = GroupRole.Owner,
        rotation: RotateStep = RotateStep.Rotated,
        groups: FakeGroups = FakeGroups(),
    ): MembersStore {
        groups.myRole = myRole
        return MembersStore(
            members = ManageGroupMembers(groups, Directory, GroupKeyRotator { rotation }),
            groupId = group,
            myUserId = me,
            scope = scope,
        )
    }

    private inner class FakeGroups : GroupRegistry {
        var myRole: GroupRole = GroupRole.Owner
        var additions = 0

        override suspend fun create(title: String, kind: GroupKind, description: String) = GroupCreateStep.Created(group)
        override suspend fun mine() = GroupsStep.Groups(emptyList())

        override suspend fun members(groupId: String) = MembersStep.Members(
            listOf(
                GroupMember(me, myRole, bannedUntil = null),
                GroupMember("u-2", GroupRole.Member, bannedUntil = null),
            ),
        )

        override suspend fun addMember(groupId: String, userId: String): MemberStep {
            additions++
            return MemberStep.Done
        }

        override suspend fun removeMember(groupId: String, userId: String) = MemberStep.Done
    }

    private object Directory : UserDirectory {
        override suspend fun byPhone(phone: String): UserLookup =
            if (phone == "+79990000002") UserLookup.Found("u-2") else UserLookup.NotFound
    }
}
