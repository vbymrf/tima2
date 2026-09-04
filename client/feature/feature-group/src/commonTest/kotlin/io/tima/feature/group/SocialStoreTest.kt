package io.tima.feature.group

import io.tima.domain.chat.AskStep
import io.tima.domain.chat.CardsStep
import io.tima.domain.chat.GroupCard
import io.tima.domain.chat.GroupCreateStep
import io.tima.domain.chat.GroupInfo
import io.tima.domain.chat.GroupKind
import io.tima.domain.chat.GroupRegistry
import io.tima.domain.chat.GroupRole
import io.tima.domain.chat.GroupsStep
import io.tima.domain.chat.MemberStep
import io.tima.domain.chat.MembersStep
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Окно 2 «Социум» глазами экрана.
 *
 * Проверяется то, что человек различает глазами: пустой список против неудачной загрузки,
 * ушедшая просьба против ещё не отправленной, отказ названный словами.
 */
class SocialStoreTest {

    @Test
    fun два_списка_приезжают_вместе() = runTest {
        val store = SocialStore(FakeGroups(), this)
        store.refresh()
        runCurrent()

        val state = store.state.value
        assertEquals(listOf("Ядро"), state.mine.map { it.title })
        assertEquals(listOf("Соседи"), state.cards.map { it.title })
        assertTrue(state.loaded, "загрузка не отмечена: пустой список будет неотличим от неудачи")
        assertNull(state.trouble)
    }

    @Test
    fun пустой_список_и_неудача_различаются() = runTest {
        // Молчащий экран неотличим от поломки, поэтому неудача обязана называться словами.
        val store = SocialStore(FakeGroups(mineAnswer = { GroupsStep.Offline(3_000) }), this)
        store.refresh()
        runCurrent()

        val state = store.state.value
        assertTrue(state.mine.isEmpty())
        assertNotNull(state.trouble, "неудача загрузки должна быть названа словами")
        assertTrue(state.loaded, "запрос состоялся — «не знаем» кончилось, даже если списка нет")
    }

    @Test
    fun просьба_помечает_строку_а_не_весь_экран() = runTest {
        val store = SocialStore(FakeGroups(), this)
        store.refresh()
        runCurrent()

        store.ask("g-2")
        runCurrent()

        val state = store.state.value
        assertTrue("g-2" in state.asked, "ушедшая просьба должна быть видна на строке")
        assertTrue(state.asking.isEmpty(), "состояние «просим» не снялось")
        assertNull(state.trouble)
    }

    @Test
    fun отказ_по_просьбе_называется_словами() = runTest {
        val store = SocialStore(FakeGroups(askAnswer = { AskStep.Refused("group_not_found") }), this)
        store.refresh()
        runCurrent()
        store.ask("g-2")
        runCurrent()

        val state = store.state.value
        assertTrue("g-2" !in state.asked, "отказ не должен выглядеть как ушедшая просьба")
        assertNotNull(state.trouble, "отказ обязан быть назван словами")
    }

    @Test
    fun повторное_нажатие_не_шлёт_второй_запрос() = runTest {
        val groups = FakeGroups()
        val store = SocialStore(groups, this)
        store.ask("g-2")
        store.ask("g-2")
        runCurrent()

        assertEquals(1, groups.asks, "второе нажатие послало второй запрос")
    }

    private class FakeGroups(
        private val mineAnswer: () -> GroupsStep = {
            GroupsStep.Groups(listOf(GroupInfo("g-1", "Ядро", GroupRole.Owner)))
        },
        private val cardsAnswer: () -> CardsStep = {
            CardsStep.Cards(listOf(GroupCard("g-2", "Соседи", "Дом 12", GroupKind.Personal)))
        },
        private val askAnswer: () -> AskStep = { AskStep.Asked("pending") },
    ) : GroupRegistry {
        var asks = 0

        override suspend fun create(title: String, kind: GroupKind, description: String) =
            GroupCreateStep.Created("g-1")

        override suspend fun mine(): GroupsStep = mineAnswer()
        override suspend fun cards(): CardsStep = cardsAnswer()

        override suspend fun askToJoin(groupId: String): AskStep {
            asks++
            return askAnswer()
        }

        override suspend fun members(groupId: String) = MembersStep.Members(emptyList())
        override suspend fun addMember(groupId: String, userId: String) = MemberStep.Done
        override suspend fun removeMember(groupId: String, userId: String) = MemberStep.Done
    }
}
