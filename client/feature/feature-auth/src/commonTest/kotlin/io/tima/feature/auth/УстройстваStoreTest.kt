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
class УстройстваStoreTest {

    private val книга = ПоддельнаяКнига()

    private fun store(scope: kotlinx.coroutines.CoroutineScope) = УстройстваStore(MyDevices(книга), scope)

    @Test
    fun список_приходит_при_открытии() = runTest {
        val store = store(backgroundScope)

        val состояние = store.state.first { !it.ждём }

        assertEquals(2, состояние.устройства.size)
        assertEquals("Телефон", состояние.устройства.first().name)
        assertTrue(состояние.устройства.first().current, "своё устройство обязано быть помечено")
    }

    /** Нажатие «Отключить» задаёт вопрос, а в сеть не идёт. */
    @Test
    fun отключение_сначала_спрашивает() = runTest {
        val store = store(backgroundScope)
        store.state.first { !it.ждём }

        store.спросить("d-2")

        assertEquals("d-2", store.state.value.спрашиваем)
        assertEquals(0, книга.отзывов, "до подтверждения в сеть не уходит ничего")
    }

    @Test
    fun подтверждение_отключает_и_перечитывает_список() = runTest {
        val store = store(backgroundScope)
        store.state.first { !it.ждём }
        store.спросить("d-2")

        store.отключить()

        val состояние = store.state.first { !it.ждём && it.спрашиваем == null }
        assertEquals(1, книга.отзывов)
        assertEquals("d-2", книга.отозванное)
        assertEquals(
            1,
            состояние.устройства.size,
            "список обязан быть перечитан у сервера, а не поправлен по памяти",
        )
    }

    /** Передумал — вопрос снят, ничего не произошло. */
    @Test
    fun передумал_ничего_не_делает() = runTest {
        val store = store(backgroundScope)
        store.state.first { !it.ждём }
        store.спросить("d-2")

        store.передумал()

        assertNull(store.state.value.спрашиваем)
        assertEquals(0, книга.отзывов)
    }

    /**
     * Последнее устройство — своё сообщение.
     *
     * «Не получилось» здесь означало бы, что человек попробует ещё раз то же самое. Ему надо
     * знать, что дело не в связи: без единственного устройства входить в аккаунт будет нечем.
     */
    @Test
    fun последнее_устройство_объясняется_словами() = runTest {
        книга.наОтзыв = { RevokeStep.LastDevice }
        val store = store(backgroundScope)
        store.state.first { !it.ждём }
        store.спросить("d-2")

        store.отключить()

        val состояние = store.state.first { !it.ждём }
        assertTrue(состояние.беда.orEmpty().contains("единственное"), "беда: ${состояние.беда}")
    }

    /** Уже отозванное — не беда: список просто устарел, и его надо перечитать. */
    @Test
    fun уже_отозванное_только_обновляет_список() = runTest {
        книга.наОтзыв = { RevokeStep.Gone }
        val store = store(backgroundScope)
        store.state.first { !it.ждём }
        store.спросить("d-2")

        store.отключить()

        val состояние = store.state.first { !it.ждём && it.спрашиваем == null }
        assertNull(состояние.беда, "беды здесь нет: устройства и так больше нет")
        assertEquals(2, книга.запросов, "список обязан быть перечитан")
    }

    /** Отказ связи не выкидывает вопрос: человек ещё не отменил своё решение. */
    @Test
    fun отказ_связи_оставляет_вопрос() = runTest {
        книга.наОтзыв = { RevokeStep.Offline(retryAfterMs = 5_000) }
        val store = store(backgroundScope)
        store.state.first { !it.ждём }
        store.спросить("d-2")

        store.отключить()

        val состояние = store.state.first { !it.ждём }
        assertEquals("d-2", состояние.спрашиваем, "вопрос остался: решение человека в силе")
        assertTrue(состояние.беда.orEmpty().contains("связи"), "беда: ${состояние.беда}")
    }

    private class ПоддельнаяКнига : DeviceBook {
        var запросов = 0
        var отзывов = 0
        var отозванное: String? = null
        var наОтзыв: suspend () -> RevokeStep = { RevokeStep.Revoked }

        override suspend fun mine(): DevicesStep {
            запросов++
            // После отзыва сервер отдаёт список короче — так и проверяется, что список
            // перечитан, а не поправлен по памяти.
            val все = listOf(
                AccountDevice("d-1", "Телефон", "2026-08-20T10:00:00Z", current = true),
                AccountDevice("d-2", "Компьютер", "2026-08-23T10:00:00Z", current = false),
            )
            return DevicesStep.Devices(if (отзывов == 0) все else все.take(1))
        }

        override suspend fun revoke(deviceId: String): RevokeStep {
            отзывов++
            отозванное = deviceId
            return наОтзыв()
        }
    }
}
