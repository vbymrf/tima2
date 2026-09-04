package io.tima.domain.chat

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Сужение круга: что отвергается ДО сети (ADR-0019 §6, ПЛАН-СОЦИУМА Г7).
 *
 * Главное здесь — не перевод ответов сервера, а то, что до сервера доходит только сужение.
 * Проверка «расширение недоступно в интерфейсе, а не только на сервере» именно об этом.
 */
class NarrowMessageLevelTest {

    @Test
    fun расширение_до_сервера_не_доходит() = runTest {
        val port = CountingLevels()
        val outcome = NarrowMessageLevel(port).narrow("g-1", 7, was = 2, to = 1)

        assertIs<NarrowStep.Wider>(outcome)
        assertEquals(0, port.calls, "запрос ушёл на сервер — отказ обязан случиться раньше")
    }

    @Test
    fun тот_же_круг_это_тоже_не_сужение() = runTest {
        val port = CountingLevels()
        val outcome = NarrowMessageLevel(port).narrow("g-1", 7, was = 2, to = 2)

        assertIs<NarrowStep.Wider>(outcome)
        assertEquals(0, port.calls)
    }

    @Test
    fun зашифрованное_не_сужается() = runTest {
        // У шифра круга нет вовсе: его читают участники по ключу, и «сузить» там нечего.
        val port = CountingLevels()
        val outcome = NarrowMessageLevel(port).narrow("g-1", 7, was = LEVEL_SECRET, to = 3)

        assertIs<NarrowStep.AlreadySecret>(outcome)
        assertEquals(0, port.calls)
    }

    @Test
    fun открытое_нельзя_зашифровать_задним_числом() = runTest {
        val port = CountingLevels()
        val outcome = NarrowMessageLevel(port).narrow("g-1", 7, was = 1, to = LEVEL_SECRET)

        assertIs<NarrowStep.CannotEncryptLater>(outcome)
        assertEquals(0, port.calls)
    }

    @Test
    fun сужение_уходит_на_сервер() = runTest {
        val port = CountingLevels()
        val outcome = NarrowMessageLevel(port).narrow("g-1", 7, was = 1, to = 3)

        assertEquals(NarrowStep.Narrowed(3), outcome)
        assertEquals(1, port.calls)
        assertEquals(3, port.lastLevel)
    }

    @Test
    fun экран_предлагает_только_более_узкие_круги() = runTest {
        // То же правило с другой стороны: список кругов для сужения не содержит ни
        // нынешнего, ни широких, ни шифра.
        val narrower = MessageCircle.narrowerThan(1)

        assertTrue(narrower.all { it.level > 1 }, "в списке оказался круг шире нынешнего: $narrower")
        assertTrue(MessageCircle.Secret !in narrower, "шифр предлагать нельзя: назад его не собрать")
        assertTrue(MessageCircle.narrowerThan(LEVEL_SECRET).isEmpty(), "у шифра сужать нечего")
    }

    private class CountingLevels : MessageLevels {
        var calls = 0
            private set
        var lastLevel = Int.MIN_VALUE
            private set

        override suspend fun narrow(groupId: String, messageId: Long, level: Int): NarrowStep {
            calls++
            lastLevel = level
            return NarrowStep.Narrowed(level)
        }
    }
}
