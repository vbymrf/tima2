package io.tima.shared

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * События звонка доходят все и по порядку — Redmi, отчёт FTPB 2026-09-27; и не теряются, когда окно пересоздают в миг
 * доставки — отчёт PMJW, 2026-11-20.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallPingsTest {

    /** Окно: читает события, пока его не отменят. Диспетчер — очередь, как главный поток. */
    private fun TestScope.window(pings: CallPings, got: MutableList<String>) =
        launch(StandardTestDispatcher(testScheduler)) { pings.each { got += it } }

    @Test
    fun входящий_не_затирается_концом_прошлого_звонка() = runTest {
        // Лента принесла одной пачкой «звонит» нового звонка и «завершён» прошлого (8 мс между ними); прежнее значение
        // хранило только последнее, и входящий пропадал.
        val pings = CallPings()
        pings.send("0b3a176e|a201146f|video")
        pings.send("конец|6247acf3|ended")
        val got = mutableListOf<String>()
        val w = window(pings, got)
        runCurrent()
        assertEquals(listOf("0b3a176e|a201146f|video", "конец|6247acf3|ended"), got)
        w.cancel()
    }

    @Test
    fun съеденное_не_приходит_второй_раз() = runTest {
        // Окно пересобрали — повтор старого «звонит» поднял бы входящий по кончившемуся звонку.
        val pings = CallPings()
        val first = mutableListOf<String>()
        val old = window(pings, first)
        runCurrent()
        pings.send("первый|x|video")
        runCurrent()
        old.cancel()
        runCurrent()

        pings.send("второй|y|audio")
        val second = mutableListOf<String>()
        val fresh = window(pings, second)
        runCurrent()
        assertEquals(listOf("первый|x|video"), first)
        assertEquals(listOf("второй|y|audio"), second)
        fresh.cancel()
    }

    @Test
    fun окно_отменено_в_миг_доставки__событие_достаётся_новому() = runTest {
        val pings = CallPings()
        val old = mutableListOf<String>()
        val oldWindow = window(pings, old)
        runCurrent() // окно ждёт событий
        pings.send("5640c35e|46496289|audio") // доставка: продолжение окна встало в очередь
        oldWindow.cancel() // система пересоздаёт окно раньше, чем очередь дошла
        runCurrent()

        val fresh = mutableListOf<String>()
        val newWindow = window(pings, fresh)
        runCurrent()
        assertEquals(emptyList(), old)
        assertEquals(listOf("5640c35e|46496289|audio"), fresh, "входящий потерян при пересоздании окна")
        newWindow.cancel()
    }

    @Test
    fun прежний_канал_в_том_же_миг_событие_терял() = runTest {
        // Доказательство причины PMJW: та же последовательность на канале, каким был CallPings до 2026-11-20.
        val channel = Channel<String>(capacity = 64)
        val old = mutableListOf<String>()
        val oldWindow = launch(StandardTestDispatcher(testScheduler)) { channel.receiveAsFlow().collect { old += it } }
        runCurrent()
        channel.trySend("5640c35e|46496289|audio")
        oldWindow.cancel()
        runCurrent()

        val fresh = mutableListOf<String>()
        val newWindow = launch(StandardTestDispatcher(testScheduler)) { channel.receiveAsFlow().collect { fresh += it } }
        runCurrent()
        assertEquals(emptyList(), old + fresh, "канал вдруг не потерял событие — причина PMJW не эта")
        newWindow.cancel()
    }
}
