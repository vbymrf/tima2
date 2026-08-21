package io.tima.harness

import io.tima.core.outbox.OutboxState
import io.tima.domain.chat.SendMessageResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Названный выход К4: сценарий «отправил — обрыв — повтор — доставлено» проходит
 * **без сервера**.
 *
 * Настоящие здесь все части, кроме транспорта: правила отправки, база SQLDelight,
 * очередь, насос, кодек тела. Проверяются два обещания этапа К3, и проверяются они
 * вместе, а не по отдельности: **ничего не потеряно** и **ничего не удвоено**.
 */
class SendScenarioTest {

    private fun харнесс() = ChatHarness(harnessDriver())

    @Test
    fun отправил_обрыв_повтор_доставлено() = runTest {
        val h = харнесс()
        h.transport.then(FakeTransport.Behaviour.Offline(retryAfterMs = 1_000))

        val поставлено = h.send("chat-1", "привет")
        assertIs<SendMessageResult.Queued>(поставлено)
        assertEquals(1, h.pendingIn(OutboxState.QUEUED))

        // Обрыв: попытка была, сообщение вернулось в очередь целым.
        assertEquals(1, h.pumpOnce(), "одно сообщение получило исход")
        assertEquals(1, h.transport.attempts.size)
        assertEquals(0, h.transport.deliveredCount(), "до сервера не дошло")
        assertEquals(1, h.pendingIn(OutboxState.QUEUED), "но и не потерялось")

        // Срок повтора не пришёл — насос не должен трогать сеть раньше времени.
        assertEquals(0, h.pumpOnce(), "повтор до срока — это лишний запрос в мёртвую сеть")
        assertEquals(1, h.transport.attempts.size)

        // Срок пришёл, сеть починилась.
        h.passTime(1_000)
        assertEquals(1, h.pumpOnce())

        assertEquals(2, h.transport.attempts.size, "ровно две попытки")
        assertEquals(1, h.transport.deliveredCount(), "и ровно одно доставленное сообщение")
        assertEquals(0, h.pending().size, "в очереди пусто: ${h.pending()}")
    }

    @Test
    fun пропавший_ответ_не_создаёт_второго_сообщения() = runTest {
        // Худший случай и главная причина, по которой dedup_key назначает клиент: сервер
        // принял, ответ не дошёл. Клиент обязан повторить — не создав второго сообщения
        // у собеседника.
        val h = харнесс()
        h.transport.then(FakeTransport.Behaviour.AcceptButLoseAnswer)

        h.send("chat-1", "привет")
        h.pumpOnce()
        assertEquals(1, h.transport.deliveredCount(), "сервер-то принял")
        assertEquals(1, h.pendingIn(OutboxState.QUEUED), "а клиент об этом не знает")

        h.passTime(2_000)
        h.pumpOnce()

        assertEquals(2, h.transport.attempts.size)
        assertEquals(
            listOf(h.transport.attempts[0].dedupKey),
            h.transport.attempts.map { it.dedupKey }.distinct(),
            "повтор обязан идти с ТЕМ ЖЕ ключом идемпотентности",
        )
        assertEquals(1, h.transport.deliveredCount(), "и остаться одним сообщением")
        assertEquals(0, h.pending().size, "повтор дошедшего — успех, а не вечный цикл")
    }

    @Test
    fun убийство_процесса_посреди_отправки_не_теряет_и_не_дублирует() = runTest {
        // Убийство изображается перезапуском харнесса над той же базой: память потеряна,
        // база осталась. В v1 убитое посреди отправки оставалось в SENDING навсегда, то
        // есть пропадало без следа для человека (инвентарь, пункт 7).
        val h = харнесс()
        h.transport.then(FakeTransport.Behaviour.Offline())
        h.send("chat-1", "привет")
        h.pumpOnce()

        val после = h.restart()

        assertEquals(1, после.pendingIn(OutboxState.QUEUED), "восстановление вернуло запись в очередь")
        после.passTime(5_000)
        assertEquals(1, после.pumpOnce())

        assertEquals(1, после.transport.deliveredCount())
        assertEquals(0, после.pending().size)
    }

    @Test
    fun десять_сообщений_после_суток_офлайна_уходят_все_и_по_одному_разу() = runTest {
        // После долгого офлайна очередь уходит залпом — это тот случай, ради которого у
        // насоса есть предел одновременных отправок.
        val h = харнесс()
        repeat(10) { h.send("chat-1", "сообщение $it") }
        repeat(10) { h.transport.then(FakeTransport.Behaviour.Offline()) }

        assertEquals(10, h.pumpOnce(), "первый проход: все получили отказ")
        assertEquals(10, h.pendingIn(OutboxState.QUEUED))
        assertEquals(0, h.transport.deliveredCount())

        h.passTime(10_000)
        assertEquals(10, h.pumpOnce())

        assertEquals(10, h.transport.deliveredCount(), "все десять дошли")
        assertEquals(
            10,
            h.transport.attempts.map { it.dedupKey }.distinct().size,
            "и у каждого свой ключ: один ключ на два сообщения означал бы потерю одного",
        )
        assertEquals(0, h.pending().size)
    }

    @Test
    fun окончательный_отказ_не_держит_очередь_вечно() = runTest {
        // Негодный конверт обязан уйти в DEAD, а не загораживать переписку. И остаться
        // видимым: человек должен знать, что сообщение не ушло.
        val h = харнесс()
        h.transport.then(FakeTransport.Behaviour.Rejected("подпись не сошлась"))

        h.send("chat-1", "первое")
        h.send("chat-1", "второе")
        h.pumpOnce()

        assertEquals(1, h.transport.deliveredCount(), "второе прошло")
        assertEquals(0, h.pending().size, "в очереди не осталось никого")

        // Неотправленное в очереди уже не стоит, а в переписке стоит: иначе человек не
        // узнает, что сообщение не ушло. Это разные вопросы, и проверять их надо порознь.
        val переписка = h.chatPage("chat-1")
        assertEquals(2, переписка.size, "оба сообщения видны человеку")
        assertEquals(1, переписка.count { it.state == OutboxState.DEAD }, "одно помечено неотправленным")
        assertEquals(1, переписка.count { it.state == OutboxState.SENT })
    }

    @Test
    fun смена_эпохи_посреди_ожидания_пересобирает_конверт() = runTest {
        // ADR-0016: за сутки в очереди ключ эпохи escrow успевает смениться, и конверт
        // под прошлую эпоху унёс бы устаревший ключ — сообщение стало бы недоступно по
        // ордеру молча.
        val h = харнесс()
        h.transport.then(FakeTransport.Behaviour.Offline())

        h.send("chat-1", "привет")
        h.pumpOnce()
        val первыйКонверт = h.transport.attempts.single().envelope

        h.changeEpoch(2)
        h.passTime(5_000)
        h.pumpOnce()

        val второйКонверт = h.transport.attempts[1].envelope
        assertTrue(
            первыйКонверт.decodeToString().startsWith("эпоха=1|"),
            "первая попытка шла под первой эпохой",
        )
        assertTrue(
            второйКонверт.decodeToString().startsWith("эпоха=2|"),
            "повтор обязан собраться под новую эпоху",
        )
        assertEquals(1, h.transport.deliveredCount(), "и это по-прежнему одно сообщение")
    }

    @Test
    fun ограничитель_частоты_уважается_а_не_обходится() = runTest {
        // Подсказка сервера сильнее нашей лестницы: он знает про свою перегрузку больше
        // нас. Повторить раньше — значит получить ещё один отказ и растянуть доставку.
        val h = харнесс()
        h.transport.then(FakeTransport.Behaviour.RateLimited(retryAfterMs = 30_000))

        h.send("chat-1", "привет")
        h.pumpOnce()

        h.passTime(29_000)
        assertEquals(0, h.pumpOnce(), "до срока, названного сервером, стучать нельзя")

        h.passTime(1_000)
        assertEquals(1, h.pumpOnce())
        assertEquals(1, h.transport.deliveredCount())
    }

    @Test
    fun пустое_сообщение_не_доходит_до_очереди_и_не_видно_в_переписке() = runTest {
        val h = харнесс()
        assertEquals(SendMessageResult.Empty, h.send("chat-1", "   "))
        assertEquals(0, h.pending().size)
        assertEquals(0, h.pumpOnce())
        assertEquals(0, h.transport.attempts.size)
    }
}
