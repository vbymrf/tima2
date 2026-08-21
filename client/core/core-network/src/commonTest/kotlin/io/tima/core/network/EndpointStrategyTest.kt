package io.tima.core.network

import io.tima.core.outbox.SendOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Выбор кандидата и память маршрутов (последняя часть К3.1).
 */
class EndpointStrategyTest {

    private val первый = RouteConfig(host = "one.example")
    private val второй = RouteConfig(host = "two.example")
    private val третий = RouteConfig(host = "three.example")
    private val список = listOf(первый, второй, третий)

    @Test
    fun начинаем_с_того_что_работало_прошлый_раз() {
        // Без памяти человек, у которого работает третий кандидат, каждый запуск платил
        // бы двумя таймаутами за первые два — та самая задержка старта из v1.
        val память = InMemoryRouteMemory()
        EndpointStrategy(список, память).let { первыйЗапуск ->
            первыйЗапуск.onTemporaryFailure()
            первыйЗапуск.onTemporaryFailure()
            первыйЗапуск.onTemporaryFailure()
            первыйЗапуск.onTemporaryFailure()
            assertEquals("api.three.example|api.three.example:443", первыйЗапуск.currentKey)
            первыйЗапуск.onSuccess()
        }

        val второйЗапуск = EndpointStrategy(список, память)

        assertEquals(третий, второйЗапуск.current, "запуск обязан начаться с рабочего адреса")
    }

    @Test
    fun запоминается_только_то_что_ответило() {
        // Запомнить кандидата при выборе значило бы сохранить между запусками адрес,
        // который ни разу не ответил.
        val память = InMemoryRouteMemory()
        val выбор = EndpointStrategy(список, память)

        выбор.onTemporaryFailure()
        выбор.onTemporaryFailure()

        assertEquals(второй, выбор.current, "адрес сменился")
        assertNull(память.lastGood(), "а в память ещё нечего писать")
    }

    @Test
    fun окончательный_отказ_адреса_не_меняет() {
        // Главное различие. 403 на подпись означает негодный конверт, а не негодный
        // адрес: уйти из-за него со списка значит потерять рабочий адрес из-за своей же
        // ошибки — причём подпись не сойдётся ни у одного кандидата.
        val выбор = EndpointStrategy(список)

        repeat(5) {
            assertFalse(выбор.onOutcome(SendOutcome.Permanent("подпись не сошлась")))
        }

        assertEquals(первый, выбор.current)
        assertEquals(0, выбор.failures(), "окончательный отказ не считается отказом адреса")
    }

    @Test
    fun один_отказ_рабочего_адреса_не_теряет() {
        // Мобильная сеть роняет соединения без причины. Уход после единственного обрыва
        // означал бы вечную карусель по списку.
        val выбор = EndpointStrategy(список)

        assertFalse(выбор.onTemporaryFailure(), "один отказ — ещё не смена адреса")
        assertEquals(первый, выбор.current)

        assertTrue(выбор.onTemporaryFailure())
        assertEquals(второй, выбор.current)
    }

    @Test
    fun успех_обнуляет_счётчик_отказов() {
        val выбор = EndpointStrategy(список)
        выбор.onTemporaryFailure()

        выбор.onOutcome(SendOutcome.Accepted(1))

        assertEquals(0, выбор.failures())
        // И следующий одиночный отказ снова не меняет адрес: иначе «отказ, успех, отказ»
        // уводил бы с работающего адреса.
        assertFalse(выбор.onTemporaryFailure())
        assertEquals(первый, выбор.current)
    }

    @Test
    fun повтор_дошедшего_это_успех_а_не_отказ() {
        val память = InMemoryRouteMemory()
        val выбор = EndpointStrategy(список, память)

        выбор.onOutcome(SendOutcome.Duplicate(7))

        assertEquals("api.one.example|api.one.example:443", память.lastGood())
    }

    @Test
    fun список_обходится_по_кругу_и_не_застревает() {
        val выбор = EndpointStrategy(список, failuresBeforeRotation = 1)

        assertEquals(второй, выбор.current.also { выбор.onTemporaryFailure() }.let { выбор.current })
        выбор.onTemporaryFailure()
        assertEquals(третий, выбор.current)
        выбор.onTemporaryFailure()
        assertEquals(первый, выбор.current, "после последнего снова первый, а не остановка")
    }

    @Test
    fun единственный_кандидат_не_крутится_но_отказы_считает() {
        // Крутить нечего, а различать «сеть моргнула» и «адрес мёртв, другого нет» —
        // надо: это разные сообщения человеку.
        val выбор = EndpointStrategy(listOf(первый))

        assertFalse(выбор.onTemporaryFailure())
        assertFalse(выбор.onTemporaryFailure())
        assertEquals(первый, выбор.current)
        assertEquals(2, выбор.failures())
    }

    @Test
    fun память_про_исчезнувшего_кандидата_чистится_а_не_сбивает_выбор() {
        // Так выглядит обновление подписанного конфига: список другой, память про старый.
        val память = InMemoryRouteMemory("api.которого-больше-нет.example|x:443")
        val выбор = EndpointStrategy(список, память)

        assertEquals(первый, выбор.current)
        assertNull(память.lastGood(), "негодная память чистится сразу, а не каждый запуск сбивает выбор")
    }

    @Test
    fun та_же_точка_записанная_иначе_узнаётся_памятью() {
        // Кириллическое имя и его punycode — один адрес. Память обязана узнать его после
        // смены записи в конфиге, иначе обновление конфига сбрасывает выбор всем.
        val память = InMemoryRouteMemory()
        EndpointStrategy(listOf(RouteConfig(host = "пацак.рф"), первый), память).onSuccess()

        val после = EndpointStrategy(listOf(RouteConfig(host = "xn--80aa4ar0b.xn--p1ai"), первый), память)

        assertEquals("xn--80aa4ar0b.xn--p1ai", после.current.host)
        assertEquals(0, после.failures())
    }

    @Test
    fun прокси_это_другая_точка_даже_при_том_же_сервере() {
        // Ключ собирается из собранного маршрута, а не из имени сервера: через прокси и
        // напрямую — разные пути, и память про один не годится для другого.
        val прямо = RouteConfig(host = "one.example")
        val черезПрокси = RouteConfig(host = "one.example", proxy = ProxyConfig("прокси.example", 8443))
        val память = InMemoryRouteMemory()

        EndpointStrategy(listOf(прямо, черезПрокси), память).onSuccess()
        val выбор = EndpointStrategy(listOf(черезПрокси, прямо), память)

        assertTrue(выбор.current.proxy == null, "память указывала на прямой путь, а не на прокси")
    }

    @Test
    fun пустой_список_и_ноль_отказов_отвергаются_при_создании() {
        assertFailsWith<IllegalArgumentException> { EndpointStrategy(emptyList()) }
        assertFailsWith<IllegalArgumentException> {
            EndpointStrategy(список, failuresBeforeRotation = 0)
        }
    }
}
