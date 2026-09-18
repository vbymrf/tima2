package io.tima.domain.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Как называть человека — правило заказчика 2026-09-18: галки через запятую в порядке
 * списка, иначе первое сверху, что есть; буква аватара — ник, имя, имя пользователя, «+».
 */
class PersonLookTest {

    private val full = ChatPerson(name = "Витя", userName = "Виктор", nick = "vitya", phone = "+79990000101")

    @Test
    fun галки_через_запятую_в_порядке_списка() {
        val look = PersonLook(
            order = listOf(PersonField.Nick, PersonField.Name, PersonField.UserName, PersonField.Phone),
            checked = setOf(PersonField.Name, PersonField.Nick),
        )
        assertEquals("@vitya, Витя", full.line(look))
    }

    @Test
    fun отмеченного_нет_у_человека_берётся_первое_что_есть() {
        val look = PersonLook(checked = setOf(PersonField.Nick))
        val withoutNick = full.copy(nick = null)
        // Ник отмечен, но его нет: первое сверху по порядку — имя.
        assertEquals("Витя", withoutNick.line(look))
    }

    @Test
    fun телефон_не_попадает_в_первую_строку_контакта() {
        val look = PersonLook(checked = setOf(PersonField.Phone))
        val onlyPhone = ChatPerson(phone = "+79990000101")
        assertNull(onlyPhone.line(look, among = setOf(PersonField.Name, PersonField.Nick, PersonField.UserName)))
        assertEquals("+79990000101", onlyPhone.line(look))
    }

    @Test
    fun буква_аватара_ник_имя_имя_пользователя_плюс() {
        assertEquals("V", full.letter())
        assertEquals("В", full.copy(nick = null).letter())
        assertEquals("В", full.copy(nick = null, name = null).letter())
        assertEquals("+", ChatPerson(phone = "+7999").letter())
    }
}
