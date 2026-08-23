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
class НоваяГруппаStoreTest {

    @Test
    fun номера_накапливаются_до_создания() = runTest {
        // Звать по одному после создания — это ротация ключа на каждого приглашённого.
        val store = store(this)
        store.номерИзменён("+79990000002")
        store.добавитьНомер()
        store.номерИзменён("+79990000003")
        store.добавитьНомер()

        assertEquals(listOf("+79990000002", "+79990000003"), store.state.value.номера)
        assertEquals("", store.state.value.номер)
    }

    @Test
    fun повтор_номера_называется_словами() = runTest {
        // Молча проглоченный повтор заставляет жать снова, думая, что не сработало.
        val store = store(this)
        store.номерИзменён("+79990000002")
        store.добавитьНомер()
        store.номерИзменён("+79990000002")
        store.добавитьНомер()

        assertEquals(1, store.state.value.номера.size)
        assertTrue("уже в списке" in (store.state.value.беда ?: ""))
    }

    @Test
    fun пустое_название_не_доходит_до_сети() = runTest {
        val сеть = ПоддельныеГруппы()
        val store = store(this, сеть)
        store.создать()
        runCurrent()

        assertNotNull(store.state.value.беда)
        assertNull(store.state.value.создана)
        assertEquals(0, сеть.созданий, "запрос ушёл впустую")
    }

    @Test
    fun непозванные_показываются_отдельно_от_беды() = runTest {
        // Группа создана. Красный текст про сбой здесь означал бы, что дело не сделано.
        val store = store(this)
        store.названиеИзменено("Поход")
        store.номерИзменён("+70000000000")
        store.добавитьНомер()
        store.создать()
        runCurrent()

        val состояние = store.state.value
        assertNotNull(состояние.создана)
        assertEquals(listOf("+70000000000"), состояние.непозванные)
        assertNull(состояние.беда)
    }

    @Test
    fun второе_нажатие_не_создаёт_вторую_группу() = runTest {
        val сеть = ПоддельныеГруппы()
        val store = store(this, сеть)
        store.названиеИзменено("Поход")
        store.создать()
        store.создать()
        runCurrent()

        assertEquals(1, сеть.созданий)
    }

    @Test
    fun сброс_очищает_экран() = runTest {
        val store = store(this)
        store.названиеИзменено("Поход")
        store.номерИзменён("+79990000002")
        store.добавитьНомер()
        store.сброс()

        assertEquals(НоваяГруппаState(), store.state.value)
    }

    // ── подделки ────────────────────────────────────────────────────────────

    private fun store(scope: TestScope, сеть: ПоддельныеГруппы = ПоддельныеГруппы()) =
        НоваяГруппаStore(CreateGroupChat(сеть, Справочник, ПамятныеЗаписи()), scope)

    private class ПоддельныеГруппы : GroupRegistry {
        var созданий = 0

        override suspend fun create(title: String): GroupCreateStep {
            созданий++
            return GroupCreateStep.Created("gggggggg-0000-0000-0000-000000000001")
        }

        override suspend fun mine() = GroupsStep.Groups(emptyList())
        override suspend fun members(groupId: String) = MembersStep.Members(emptyList())
        override suspend fun addMember(groupId: String, userId: String) = MemberStep.Done
        override suspend fun removeMember(groupId: String, userId: String) = MemberStep.Done
    }

    private object Справочник : UserDirectory {
        override suspend fun byPhone(phone: String): UserLookup =
            if (phone == "+79990000002") UserLookup.Found("u-2") else UserLookup.NotFound
    }

    private class ПамятныеЗаписи : ChatBook {
        override fun remember(chatId: String, kind: ChatKind, title: String?, peerId: String?) = Unit
    }
}
