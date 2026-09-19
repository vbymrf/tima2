package io.tima.feature.chat

import io.tima.domain.chat.ChatKind
import io.tima.domain.chat.ChatSummary
import io.tima.domain.chat.MessageDisplay
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Поиск по перепискам — решение заказчика 2026-09-19 («для Телефон реализуй функционал
 * поиска»).
 *
 * Проверяется то, что решено, а не то, как написано: ищется по имени в шапке и по первой
 * строке последнего сообщения, регистр не важен, а пустой запрос никого не прячет.
 */
class ChatSearchTest {

    private val переписка = ChatSummary(
        chatId = "c-1",
        title = "Аня Соседка",
        kind = ChatKind.Personal,
        peerId = "u-1",
        preview = "привезу ключи завтра",
        lastOutgoing = false,
        lastDisplay = MessageDisplay.RECEIVED,
        atMs = 1_000,
        unread = 0,
    )

    @Test
    fun пустой_запрос_подходит_всем() {
        // «Ничего не набрано» — это не «ничего не найдено»: список обязан остаться целым.
        assertTrue(переписка.matches(""))
        assertTrue(переписка.matches("   "))
    }

    @Test
    fun ищется_по_имени_и_по_последнему_сообщению() {
        assertTrue(переписка.matches("сосед"), "не нашлось по части имени")
        assertTrue(переписка.matches("ключи"), "не нашлось по тексту последнего сообщения")
        assertTrue(переписка.matches("АНЯ"), "регистр не должен мешать")
        assertFalse(переписка.matches("Борис"))
    }

    @Test
    fun безымянная_переписка_ищется_по_тексту() {
        // Имени может не быть вовсе: профиль ещё не приезжал. Это не повод прятать строку
        // от поиска — последнее сообщение у неё есть.
        val без = переписка.copy(title = null)
        assertTrue(без.matches("ключи"))
        assertFalse(без.matches("Аня"))
    }
}
