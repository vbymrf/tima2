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

    private val first = RouteConfig(host = "one.example")
    private val second = RouteConfig(host = "two.example")
    private val third = RouteConfig(host = "three.example")
    private val list = listOf(first, second, third)

    @Test
    fun начинаем_с_того_что_работало_прошлый_раз() {
        // Без памяти человек, у которого работает третий кандидат, каждый запуск платил
        // бы двумя таймаутами за первые два — та самая задержка старта из v1.
        val memory = InMemoryRouteMemory()
        EndpointStrategy(list, memory).let { firstLaunch ->
            firstLaunch.onTemporaryFailure()
            firstLaunch.onTemporaryFailure()
            firstLaunch.onTemporaryFailure()
            firstLaunch.onTemporaryFailure()
            assertEquals("api.three.example|api.three.example:443", firstLaunch.currentKey)
            firstLaunch.onSuccess()
        }

        val secondLaunch = EndpointStrategy(list, memory)

        assertEquals(third, secondLaunch.current, "запуск обязан начаться с рабочего адреса")
    }

    @Test
    fun запоминается_только_то_что_ответило() {
        // Запомнить кандидата при выборе значило бы сохранить между запусками адрес,
        // который ни разу не ответил.
        val memory = InMemoryRouteMemory()
        val choice = EndpointStrategy(list, memory)

        choice.onTemporaryFailure()
        choice.onTemporaryFailure()

        assertEquals(second, choice.current, "адрес сменился")
        assertNull(memory.lastGood(), "а в память ещё нечего писать")
    }

    @Test
    fun окончательный_отказ_адреса_не_меняет() {
        // Главное различие. 403 на подпись означает негодный конверт, а не негодный
        // адрес: уйти из-за него со списка значит потерять рабочий адрес из-за своей же
        // ошибки — причём подпись не сойдётся ни у одного кандидата.
        val choice = EndpointStrategy(list)

        repeat(5) {
            assertFalse(choice.onOutcome(SendOutcome.Permanent("подпись не сошлась")))
        }

        assertEquals(first, choice.current)
        assertEquals(0, choice.failures(), "окончательный отказ не считается отказом адреса")
    }

    @Test
    fun один_отказ_рабочего_адреса_не_теряет() {
        // Мобильная сеть роняет соединения без причины. Уход после единственного обрыва
        // означал бы вечную карусель по списку.
        val choice = EndpointStrategy(list)

        assertFalse(choice.onTemporaryFailure(), "один отказ — ещё не смена адреса")
        assertEquals(first, choice.current)

        assertTrue(choice.onTemporaryFailure())
        assertEquals(second, choice.current)
    }

    @Test
    fun успех_обнуляет_счётчик_отказов() {
        val choice = EndpointStrategy(list)
        choice.onTemporaryFailure()

        choice.onOutcome(SendOutcome.Accepted(1))

        assertEquals(0, choice.failures())
        // И следующий одиночный отказ снова не меняет адрес: иначе «отказ, успех, отказ»
        // уводил бы с работающего адреса.
        assertFalse(choice.onTemporaryFailure())
        assertEquals(first, choice.current)
    }

    @Test
    fun повтор_дошедшего_это_успех_а_не_отказ() {
        val memory = InMemoryRouteMemory()
        val choice = EndpointStrategy(list, memory)

        choice.onOutcome(SendOutcome.Duplicate(7))

        assertEquals("api.one.example|api.one.example:443", memory.lastGood())
    }

    @Test
    fun список_обходится_по_кругу_и_не_застревает() {
        val choice = EndpointStrategy(list, failuresBeforeRotation = 1)

        assertEquals(second, choice.current.also { choice.onTemporaryFailure() }.let { choice.current })
        choice.onTemporaryFailure()
        assertEquals(third, choice.current)
        choice.onTemporaryFailure()
        assertEquals(first, choice.current, "после последнего снова первый, а не остановка")
    }

    @Test
    fun единственный_кандидат_не_крутится_но_отказы_считает() {
        // Крутить нечего, а различать «сеть моргнула» и «адрес мёртв, другого нет» —
        // надо: это разные сообщения человеку.
        val choice = EndpointStrategy(listOf(first))

        assertFalse(choice.onTemporaryFailure())
        assertFalse(choice.onTemporaryFailure())
        assertEquals(first, choice.current)
        assertEquals(2, choice.failures())
    }

    @Test
    fun память_про_исчезнувшего_кандидата_чистится_а_не_сбивает_выбор() {
        // Так выглядит обновление подписанного конфига: список другой, память про старый.
        val memory = InMemoryRouteMemory("api.которого-больше-нет.example|x:443")
        val choice = EndpointStrategy(list, memory)

        assertEquals(first, choice.current)
        assertNull(memory.lastGood(), "негодная память чистится сразу, а не каждый запуск сбивает выбор")
    }

    @Test
    fun та_же_точка_записанная_иначе_узнаётся_памятью() {
        // Кириллическое имя и его punycode — один адрес. Память обязана узнать его после
        // смены записи в конфиге, иначе обновление конфига сбрасывает выбор всем.
        val memory = InMemoryRouteMemory()
        EndpointStrategy(listOf(RouteConfig(host = "пацак.рф"), first), memory).onSuccess()

        val after = EndpointStrategy(listOf(RouteConfig(host = "xn--80aa4ar0b.xn--p1ai"), first), memory)

        assertEquals("xn--80aa4ar0b.xn--p1ai", after.current.host)
        assertEquals(0, after.failures())
    }

    @Test
    fun прокси_это_другая_точка_даже_при_том_же_сервере() {
        // Ключ собирается из собранного маршрута, а не из имени сервера: через прокси и
        // напрямую — разные пути, и память про один не годится для другого.
        val directly = RouteConfig(host = "one.example")
        val viaProxy = RouteConfig(host = "one.example", proxy = ProxyConfig("прокси.example", 8443))
        val memory = InMemoryRouteMemory()

        EndpointStrategy(listOf(directly, viaProxy), memory).onSuccess()
        val choice = EndpointStrategy(listOf(viaProxy, directly), memory)

        assertTrue(choice.current.proxy == null, "память указывала на прямой путь, а не на прокси")
    }

    @Test
    fun пустой_список_и_ноль_отказов_отвергаются_при_создании() {
        assertFailsWith<IllegalArgumentException> { EndpointStrategy(emptyList()) }
        assertFailsWith<IllegalArgumentException> {
            EndpointStrategy(list, failuresBeforeRotation = 0)
        }
    }
}
