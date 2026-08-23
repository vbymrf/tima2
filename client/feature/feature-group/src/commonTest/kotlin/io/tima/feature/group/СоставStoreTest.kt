package io.tima.feature.group

import io.tima.domain.chat.GroupCreateStep
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
class СоставStoreTest {

    private val группа = "gggggggg-0000-0000-0000-000000000001"
    private val я = "u-1"

    @Test
    fun состав_читается_и_моя_роль_видна() = runTest {
        val store = store(this)
        store.обновить()
        runCurrent()

        assertEquals(2, store.state.value.участники.size)
        assertEquals(GroupRole.Владелец, store.state.value.мояРоль)
        assertTrue(store.state.value.правлюСоставом)
    }

    @Test
    fun участник_не_правит_составом() = runTest {
        // Экран по этому признаку прячет управление: кнопка, отвечающая отказом, сообщает
        // человеку о запрете уже после нажатия.
        val store = store(this, мояРоль = GroupRole.Участник)
        store.обновить()
        runCurrent()

        assertFalse(store.state.value.правлюСоставом)
    }

    @Test
    fun несменившийся_ключ_показывается_предупреждением_а_не_бедой() = runTest {
        val store = store(this, ротация = RotateStep.Offline(4_000))
        store.исключить("u-2")
        runCurrent()

        val состояние = store.state.value
        assertNotNull(состояние.предупреждение, "человек не узнает, что доступ не закрыт")
        assertNull(состояние.беда, "это не ошибка: состав действительно изменён")
    }

    @Test
    fun успешное_исключение_не_оставляет_предупреждения() = runTest {
        val store = store(this)
        store.исключить("u-2")
        runCurrent()

        assertNull(store.state.value.предупреждение)
        assertNull(store.state.value.беда)
    }

    @Test
    fun незарегистрированный_номер_не_стирает_набранное() = runTest {
        // Стереть набранный номер при отказе — значит заставить набирать заново то, что и
        // так под рукой. Та же причина, что у поля входа.
        val store = store(this)
        store.номерИзменён("+70000000000")
        store.позвать()
        runCurrent()

        assertEquals("+70000000000", store.state.value.номер)
        assertTrue("нет" in (store.state.value.беда ?: ""))
    }

    @Test
    fun удачное_приглашение_очищает_поле() = runTest {
        val store = store(this)
        store.номерИзменён("+79990000002")
        store.позвать()
        runCurrent()

        assertEquals("", store.state.value.номер)
    }

    @Test
    fun второе_нажатие_не_шлёт_второй_запрос() = runTest {
        // Иначе один нетерпеливый человек порождает две ротации ключа — и лишнюю выдачу
        // обёрток всем устройствам группы.
        val группы = ПоддельныеГруппы()
        val store = store(this, группы = группы)
        store.номерИзменён("+79990000002")
        store.позвать()
        store.позвать()
        runCurrent()

        assertEquals(1, группы.добавлений)
    }

    // ── подделки ────────────────────────────────────────────────────────────

    private fun store(
        scope: TestScope,
        мояРоль: GroupRole = GroupRole.Владелец,
        ротация: RotateStep = RotateStep.Rotated,
        группы: ПоддельныеГруппы = ПоддельныеГруппы(),
    ): СоставStore {
        группы.мояРоль = мояРоль
        return СоставStore(
            участники = ManageGroupMembers(группы, Справочник, GroupKeyRotator { ротация }),
            groupId = группа,
            myUserId = я,
            scope = scope,
        )
    }

    private inner class ПоддельныеГруппы : GroupRegistry {
        var мояРоль: GroupRole = GroupRole.Владелец
        var добавлений = 0

        override suspend fun create(title: String) = GroupCreateStep.Created(группа)
        override suspend fun mine() = GroupsStep.Groups(emptyList())

        override suspend fun members(groupId: String) = MembersStep.Members(
            listOf(
                GroupMember(я, мояРоль, bannedUntil = null),
                GroupMember("u-2", GroupRole.Участник, bannedUntil = null),
            ),
        )

        override suspend fun addMember(groupId: String, userId: String): MemberStep {
            добавлений++
            return MemberStep.Done
        }

        override suspend fun removeMember(groupId: String, userId: String) = MemberStep.Done
    }

    private object Справочник : UserDirectory {
        override suspend fun byPhone(phone: String): UserLookup =
            if (phone == "+79990000002") UserLookup.Found("u-2") else UserLookup.NotFound
    }
}
