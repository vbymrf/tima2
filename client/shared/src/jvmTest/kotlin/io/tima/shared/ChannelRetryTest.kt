package io.tima.shared

import io.tima.core.network.LinkState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Пауза между подъёмами канала — У11.
 *
 * Проверяется не арифметика, а два решения, которые легко отменить одной строкой и не
 * заметить: основание берётся у **состояния связи** (оно выведено из настоящих журналов
 * испытаний, а не придумано), и потолок **не опускает** того, что состояние сказало.
 *
 * До этого здесь стояли жёсткие две секунды — тридцать попыток в минуту в тоннеле, без
 * конца и края. Пока канал жил вместе с окном, это было безвредно; служба живёт сутками.
 */
class ChannelRetryTest {

    private fun пауза(состояние: LinkState, подряд: Int) =
        Receiver.retryPause(состояние, подряд)

    @Test
    fun первая_попытка_идёт_по_состоянию_связи() {
        // Сеть мигает и возвращается быстро; стена у оператора стоит часами. Разные
        // числа здесь — не вкус, а два разных явления.
        assertEquals(LinkState.ONLINE.retryDelayMs, пауза(LinkState.ONLINE, 1))
        assertEquals(LinkState.NO_NETWORK.retryDelayMs, пауза(LinkState.NO_NETWORK, 1))
        assertEquals(LinkState.BLOCKED.retryDelayMs, пауза(LinkState.BLOCKED, 1))
    }

    @Test
    fun неудачи_подряд_разводят_паузу() {
        val первая = пауза(LinkState.NO_NETWORK, 1)
        val вторая = пауза(LinkState.NO_NETWORK, 2)
        val третья = пауза(LinkState.NO_NETWORK, 3)
        assertTrue(вторая > первая && третья > вторая, "пауза не растёт: $первая, $вторая, $третья")
    }

    @Test
    fun потолок_держит_даже_на_долгом_отсутствии_сети() {
        // Ночь без сети не должна кончиться паузой в час: вернувшуюся сеть надо
        // заметить, а не дождаться.
        for (подряд in 1..40) {
            assertTrue(
                пауза(LinkState.NO_NETWORK, подряд) <= Receiver.RETRY_CEILING_MS,
                "на $подряд-й попытке пауза выше потолка",
            )
        }
    }

    @Test
    fun потолок_не_опускает_того_что_сказало_состояние() {
        // У `BLOCKED` своя пауза БОЛЬШЕ потолка, и урезать её значило бы вернуться к
        // долблению в стену, которая стоит часами. Потолок здесь не применяется.
        assertTrue(LinkState.BLOCKED.retryDelayMs > Receiver.RETRY_CEILING_MS, "проверка потеряла смысл")
        assertEquals(LinkState.BLOCKED.retryDelayMs, пауза(LinkState.BLOCKED, 10))
    }
}
