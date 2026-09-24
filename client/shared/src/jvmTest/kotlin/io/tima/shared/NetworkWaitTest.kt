package io.tima.shared

import io.tima.core.network.NetworkState
import io.tima.core.network.NetworkWatch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Ожидание перед подъёмом канала — У17.
 *
 * Проверяется то, что ADR-0016 §4 обещал с июля и чего не было: просыпаться на смену сети,
 * а не по таймеру. И то, что легко сломать, «упростив»: нынешнее состояние — не событие.
 */
class NetworkWaitTest {

    private class Сеть(начало: NetworkState) : NetworkWatch {
        override val state = MutableStateFlow(начало)
        override val switched = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    }

    @Test
    fun без_события_ждём_паузу_целиком() = runTest {
        val woke = waitBeforeRetry(Сеть(NetworkState.AVAILABLE), pauseMs = 5_000, ceilingMs = 60_000)
        assertTrue(!woke)
        assertEquals(5_000, currentTime, "пауза не выдержана")
    }

    @Test
    fun сеть_вернулась_и_пробуем_сразу() = runTest {
        // Главное этой задачи: вернувшаяся сеть не должна ждать паузы отступления, до
        // минуты, — звонок живёт сорок пять секунд.
        val сеть = Сеть(NetworkState.LOST)
        var woke = false
        launch { woke = waitBeforeRetry(сеть, pauseMs = 5_000, ceilingMs = 60_000) }
        runCurrent()
        advanceTimeBy(1_000)
        сеть.state.value = NetworkState.AVAILABLE
        runCurrent()

        assertTrue(woke, "не проснулись на возвращение сети")
        assertEquals(1_000, currentTime, "проснулись не сразу")
    }

    @Test
    fun смена_сети_будит_раньше_паузы() = runTest {
        val сеть = Сеть(NetworkState.AVAILABLE)
        var woke = false
        launch { woke = waitBeforeRetry(сеть, pauseMs = 60_000, ceilingMs = 60_000) }
        runCurrent()
        advanceTimeBy(2_000)
        сеть.switched.tryEmit(Unit)
        runCurrent()

        assertTrue(woke)
        assertEquals(2_000, currentTime)
    }

    @Test
    fun без_сети_ждём_до_потолка_а_не_вечно() = runTest {
        // Прошивка, однажды не приславшая `onAvailable`, иначе оставила бы канал
        // мёртвым навсегда. Потолок — страховка, а не опрос.
        val woke = waitBeforeRetry(Сеть(NetworkState.LOST), pauseMs = 5_000, ceilingMs = 60_000)
        assertTrue(!woke)
        assertEquals(60_000, currentTime, "без сети ждали паузу, а не потолок")
    }

    @Test
    fun нынешнее_состояние_не_событие() = runTest {
        // Сеть ЕСТЬ уже сейчас — это не «появилась». Проснись мы на нынешнее значение, и
        // пауза отступления перестала бы существовать: цикл молотил бы сервер без
        // передышки, пока тот отказывает.
        val woke = waitBeforeRetry(Сеть(NetworkState.AVAILABLE), pauseMs = 3_000, ceilingMs = 60_000)
        assertTrue(!woke)
        assertEquals(3_000, currentTime)
    }

    @Test
    fun канал_рвётся_на_смену_и_на_пропажу_но_не_на_нынешнее() = runTest {
        val сеть = Сеть(NetworkState.LOST)
        var разорван = false
        launch { networkBrokeChannel(сеть); разорван = true }
        runCurrent()
        // Канал поднят при устаревшем «нет»: рвать его сразу нельзя — иначе по кругу.
        assertTrue(!разорван, "канал разорван нынешним состоянием")

        сеть.state.value = NetworkState.AVAILABLE
        runCurrent()
        assertTrue(!разорван)

        сеть.state.value = NetworkState.LOST
        runCurrent()
        assertTrue(разорван, "пропажа сети не разорвала канал")
    }
}
