package io.tima.feature.group

import io.tima.domain.chat.AccessGrant
import io.tima.domain.chat.AccessPort
import io.tima.domain.chat.AccessState
import io.tima.domain.chat.AskAccessStep
import io.tima.domain.chat.GrantStep
import io.tima.domain.chat.GrantsStep
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Подокно «Доступ» глазами экрана (ПЛАН-СОЦИУМА Г9).
 *
 * Главное здесь — **отказ виден и не путается с «ещё не решено»**. Молчание в этом месте
 * заставляет человека просить снова и снова, считая, что его не заметили.
 */
class AccessStoreTest {

    @Test
    fun админ_и_участник_различаются_по_ответу_сервера() = runTest {
        // Клиент не спрашивает «а не админ ли я»: различие приезжает в ответе, и второго
        // источника правды о правах не заводится.
        val admin = AccessStore(FakeAccess(), "g-1", backgroundScope)
        admin.refresh()
        runCurrent()
        assertTrue(admin.state.value.admin, "состав приехал, но экран считает себя участником")

        val member = AccessStore(FakeAccess(grants = { GrantsStep.Mine(2) }), "g-1", backgroundScope)
        member.refresh()
        runCurrent()
        assertTrue(!member.state.value.admin)
        assertEquals(2, member.state.value.myLevel)
    }

    @Test
    fun отказ_виден_просившему() = runTest {
        val store = AccessStore(
            FakeAccess(
                grants = { GrantsStep.Mine(2) },
                ask = { AskAccessStep.Asked(AccessState.Declined) },
            ),
            "g-1",
            backgroundScope,
        )

        store.ask()
        runCurrent()

        assertEquals(AccessState.Declined, store.state.value.mine, "отказ не доехал до экрана")
        assertNull(store.state.value.trouble, "отказ админа — не поломка приложения")
    }

    @Test
    fun второе_нажатие_не_шлёт_вторую_просьбу() = runTest {
        val port = FakeAccess(ask = { AskAccessStep.Offline(1_000) })
        val store = AccessStore(port, "g-1", backgroundScope)

        store.ask()
        store.ask()
        runCurrent()

        assertEquals(1, port.asks, "второе нажатие послало вторую просьбу")
    }

    @Test
    fun неверный_срок_до_сервера_не_доходит() = runTest {
        val port = FakeAccess()
        val store = AccessStore(port, "g-1", backgroundScope)

        store.decide("u-2", grant = true, untilEpoch = "12 октября")
        runCurrent()

        assertEquals(0, port.decisions, "срок-опечатка ушёл на сервер")
        assertNotNull(store.state.value.trouble, "про опечатку надо сказать словами")
    }

    @Test
    fun решение_перечитывает_состав() = runTest {
        val port = FakeAccess()
        val store = AccessStore(port, "g-1", backgroundScope)

        store.decide("u-2", grant = true, untilEpoch = "2026-10")
        runCurrent()

        assertEquals(1, port.decisions)
        assertTrue(port.reads >= 1, "состав не перечитан — экран покажет вчерашнее состояние")
    }

    @Test
    fun сроки_считаются_снаружи() = runTest {
        // Часы живут в `shared`, а экрану нужен готовый срок: так проверка не зависит от
        // того, какой сегодня месяц.
        val store = AccessStore(FakeAccess(), "g-1", backgroundScope, epochAfter = { "2026-1$it" })

        assertEquals(listOf("2026-11", "2026-13"), store.state.value.terms.map { it.epoch })
    }

    private class FakeAccess(
        private val grants: () -> GrantsStep = {
            GrantsStep.Grants(listOf(AccessGrant("u-2", 3, AccessState.Asked)))
        },
        private val ask: () -> AskAccessStep = { AskAccessStep.Asked(AccessState.Asked) },
    ) : AccessPort {
        var asks = 0
            private set
        var decisions = 0
            private set
        var reads = 0
            private set

        override suspend fun ask(groupId: String): AskAccessStep {
            asks++
            return ask.invoke()
        }

        override suspend fun grants(groupId: String): GrantsStep {
            reads++
            return grants.invoke()
        }

        override suspend fun decide(
            groupId: String,
            userId: String,
            grant: Boolean,
            untilEpoch: String,
        ): GrantStep {
            decisions++
            return GrantStep.Done
        }
    }
}
