package io.tima.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Тест здесь не ради покрытия: он доказывает, что общий код действительно
 * компилируется и исполняется на каждом таргете. Пустой модуль без теста
 * компилируется даже когда набор источников подключён неправильно.
 */
class IdsTest {

    @Test
    fun пустой_идентификатор_не_создаётся() {
        assertFailsWith<IllegalArgumentException> { ChatId("") }
        assertFailsWith<IllegalArgumentException> { UserId(" ") }
        assertFailsWith<IllegalArgumentException> { DedupKey("") }
    }

    @Test
    fun серверный_идентификатор_только_положительный() {
        // Ноль в v1 означал «ещё не отправлено» и ездил в том же поле, что настоящий
        // идентификатор. Здесь этого состояния нет: нет ответа сервера — нет и типа.
        assertFailsWith<IllegalArgumentException> { ServerMessageId(0) }
        assertFailsWith<IllegalArgumentException> { LocalMessageId(-1) }
        assertEquals(7L, ServerMessageId(7).value)
    }

    @Test
    fun строковое_представление_без_обёртки() {
        assertEquals("chat-1", ChatId("chat-1").toString())
    }
}
