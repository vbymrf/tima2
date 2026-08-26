package io.tima.feature.group

import io.tima.domain.chat.ChatBook
import io.tima.domain.chat.ChatKind
import io.tima.domain.chat.CreateGroupChat
import io.tima.domain.chat.GroupCreateStep
import io.tima.domain.chat.GroupRegistry
import io.tima.domain.chat.GroupsStep
import io.tima.domain.chat.MemberStep
import io.tima.domain.chat.MembersStep
import io.tima.domain.chat.UserDirectory
import io.tima.domain.chat.UserLookup
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Создание группы глазами экрана.
 *
 * Проверяется ввод — то, что человек делает руками: набранное не теряется, повтор номера
 * называется словами, а непозванные номера не выдаются за провал.
 */
class NewGroupStoreTest {

    @Test
    fun номера_накапливаются_до_создания() = runTest {
        // Звать по одному после создания — это ротация ключа на каждого приглашённого.
        val store = store(this)
        store.changedNumber("+79990000002")
        store.addNumber()
        store.changedNumber("+79990000003")
        store.addNumber()

        assertEquals(listOf("+79990000002", "+79990000003"), store.state.value.numbers)
        assertEquals("", store.state.value.number)
    }

    @Test
    fun повтор_номера_называется_словами() = runTest {
        // Молча проглоченный повтор заставляет жать снова, думая, что не сработало.
        val store = store(this)
        store.changedNumber("+79990000002")
        store.addNumber()
        store.changedNumber("+79990000002")
        store.addNumber()

        assertEquals(1, store.state.value.numbers.size)
        assertTrue("уже в списке" in (store.state.value.trouble ?: ""))
    }

    @Test
    fun пустое_название_не_доходит_до_сети() = runTest {
        val network = FakeGroups()
        val store = store(this, network)
        store.create()
        runCurrent()

        assertNotNull(store.state.value.trouble)
        assertNull(store.state.value.created)
        assertEquals(0, network.creations, "запрос ушёл впустую")
    }

    @Test
    fun непозванные_показываются_отдельно_от_беды() = runTest {
        // Группа создана. Красный текст про сбой здесь означал бы, что дело не сделано.
        val store = store(this)
        store.changedTitle("Поход")
        store.changedNumber("+70000000000")
        store.addNumber()
        store.create()
        runCurrent()

        val state = store.state.value
        assertNotNull(state.created)
        assertEquals(listOf("+70000000000"), state.notInvited)
        assertNull(state.trouble)
    }

    @Test
    fun второе_нажатие_не_создаёт_вторую_группу() = runTest {
        val network = FakeGroups()
        val store = store(this, network)
        store.changedTitle("Поход")
        store.create()
        store.create()
        runCurrent()

        assertEquals(1, network.creations)
    }

    @Test
    fun сброс_очищает_экран() = runTest {
        val store = store(this)
        store.changedTitle("Поход")
        store.changedNumber("+79990000002")
        store.addNumber()
        store.reset()

        assertEquals(NewGroupState(), store.state.value)
    }

    // ── подделки ────────────────────────────────────────────────────────────

    private fun store(scope: TestScope, network: FakeGroups = FakeGroups()) =
        NewGroupStore(CreateGroupChat(network, Directory, EntryMemorable()), scope)

    private class FakeGroups : GroupRegistry {
        var creations = 0

        override suspend fun create(title: String): GroupCreateStep {
            creations++
            return GroupCreateStep.Created("gggggggg-0000-0000-0000-000000000001")
        }

        override suspend fun mine() = GroupsStep.Groups(emptyList())
        override suspend fun members(groupId: String) = MembersStep.Members(emptyList())
        override suspend fun addMember(groupId: String, userId: String) = MemberStep.Done
        override suspend fun removeMember(groupId: String, userId: String) = MemberStep.Done
    }

    private object Directory : UserDirectory {
        override suspend fun byPhone(phone: String): UserLookup =
            if (phone == "+79990000002") UserLookup.Found("u-2") else UserLookup.NotFound
    }

    private class EntryMemorable : ChatBook {
        override fun remember(chatId: String, kind: ChatKind, title: String?, peerId: String?) = Unit
    }
}
