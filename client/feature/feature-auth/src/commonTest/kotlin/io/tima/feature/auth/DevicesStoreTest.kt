package io.tima.feature.auth

import io.tima.domain.account.AccountDevice
import io.tima.domain.account.DeviceBook
import io.tima.domain.account.DevicesStep
import io.tima.domain.account.MyDevices
import io.tima.domain.account.RevokeStep
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Свои устройства: список и отключение.
 *
 * Главное правило — **отключение спрашивает**. Отозванное устройство обратно не вернуть, а
 * строки в списке похожи друг на друга: «Телефон» и «Телефон». Нажатие без вопроса означает,
 * что человек однажды выкинет то устройство, с которого читает.
 */
class DevicesStoreTest {

    private val book = FakeBook()

    private fun store(scope: kotlinx.coroutines.CoroutineScope) = DevicesStore(MyDevices(book), scope)

    @Test
    fun список_приходит_при_открытии() = runTest {
        val store = store(backgroundScope)

        val state = store.state.first { !it.expect }

        assertEquals(2, state.devices.size)
        assertEquals("Телефон", state.devices.first().name)
        assertTrue(state.devices.first().current, "своё устройство обязано быть помечено")
    }

    /** Нажатие «Отключить» задаёт вопрос, а в сеть не идёт. */
    @Test
    fun отключение_сначала_спрашивает() = runTest {
        val store = store(backgroundScope)
        store.state.first { !it.expect }

        store.ask("d-2")

        assertEquals("d-2", store.state.value.ask)
        assertEquals(0, book.revocations, "до подтверждения в сеть не уходит ничего")
    }

    @Test
    fun подтверждение_отключает_и_перечитывает_список() = runTest {
        val store = store(backgroundScope)
        store.state.first { !it.expect }
        store.ask("d-2")

        store.revoke()

        val state = store.state.first { !it.expect && it.ask == null }
        assertEquals(1, book.revocations)
        assertEquals("d-2", book.revoked)
        assertEquals(
            1,
            state.devices.size,
            "список обязан быть перечитан у сервера, а не поправлен по памяти",
        )
    }

    /** Передумал — вопрос снят, ничего не произошло. */
    @Test
    fun передумал_ничего_не_делает() = runTest {
        val store = store(backgroundScope)
        store.state.first { !it.expect }
        store.ask("d-2")

        store.changedMind()

        assertNull(store.state.value.ask)
        assertEquals(0, book.revocations)
    }

    /**
     * Последнее устройство — своё сообщение.
     *
     * «Не получилось» здесь означало бы, что человек попробует ещё раз то же самое. Ему надо
     * знать, что дело не в связи: без единственного устройства входить в аккаунт будет нечем.
     */
    @Test
    fun последнее_устройство_объясняется_словами() = runTest {
        book.onRevocation = { RevokeStep.LastDevice }
        val store = store(backgroundScope)
        store.state.first { !it.expect }
        store.ask("d-2")

        store.revoke()

        val state = store.state.first { !it.expect }
        assertTrue(state.trouble.orEmpty().contains("единственное"), "беда: ${state.trouble}")
    }

    /** Уже отозванное — не беда: список просто устарел, и его надо перечитать. */
    @Test
    fun уже_отозванное_только_обновляет_список() = runTest {
        book.onRevocation = { RevokeStep.Gone }
        val store = store(backgroundScope)
        store.state.first { !it.expect }
        store.ask("d-2")

        store.revoke()

        val state = store.state.first { !it.expect && it.ask == null }
        assertNull(state.trouble, "беды здесь нет: устройства и так больше нет")
        assertEquals(2, book.requests, "список обязан быть перечитан")
    }

    /** Отказ связи не выкидывает вопрос: человек ещё не отменил своё решение. */
    @Test
    fun отказ_связи_оставляет_вопрос() = runTest {
        book.onRevocation = { RevokeStep.Offline(retryAfterMs = 5_000) }
        val store = store(backgroundScope)
        store.state.first { !it.expect }
        store.ask("d-2")

        store.revoke()

        val state = store.state.first { !it.expect }
        assertEquals("d-2", state.ask, "вопрос остался: решение человека в силе")
        assertTrue(state.trouble.orEmpty().contains("связи"), "беда: ${state.trouble}")
    }

    private class FakeBook : DeviceBook {
        var requests = 0
        var revocations = 0
        var revoked: String? = null
        var onRevocation: suspend () -> RevokeStep = { RevokeStep.Revoked }

        override suspend fun mine(): DevicesStep {
            requests++
            // После отзыва сервер отдаёт список короче — так и проверяется, что список
            // перечитан, а не поправлен по памяти.
            val all = listOf(
                AccountDevice("d-1", "Телефон", "2026-08-20T10:00:00Z", current = true),
                AccountDevice("d-2", "Компьютер", "2026-08-23T10:00:00Z", current = false),
            )
            return DevicesStep.Devices(if (revocations == 0) all else all.take(1))
        }

        override suspend fun revoke(deviceId: String): RevokeStep {
            revocations++
            revoked = deviceId
            return onRevocation()
        }
    }
}
