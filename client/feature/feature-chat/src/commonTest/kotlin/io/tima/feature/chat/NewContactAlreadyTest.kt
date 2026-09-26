package io.tima.feature.chat

import io.tima.domain.chat.BookEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * «Новый контакт» для того, кто уже в контактах — заказчик 2026-09-26: сказать об этом и
 * не сохранять, иначе «Добавить» переписывает его запись и работает как «Изменить».
 */
class NewContactAlreadyTest {

    private val саша = BookEntry(id = "p1", phone = "+79990000101", nameOwn = "Саша", userId = "u1")
    private val безTima = BookEntry(id = "p2", phone = "+79990000102", namePhone = "Аня")

    @Test
    fun номер_из_контактов_найден_и_сохранить_нельзя() {
        val state = NewContactState(normalized = "+79990000101", contacts = listOf(саша, безTima))
        assertEquals("p1", state.already?.id)
        assertFalse(state.canSave)
    }

    @Test
    fun новый_номер_сохраняется() {
        val state = NewContactState(normalized = "+79990000199", contacts = listOf(саша))
        assertNull(state.already)
        assertTrue(state.canSave)
    }

    @Test
    fun выбранный_по_нику_сверяется_по_user_id() {
        val state = NewContactState(picked = "u1", contacts = listOf(саша))
        assertEquals("p1", state.already?.id)
        assertFalse(state.canSave)
    }

    @Test
    fun выбранный_по_нику_перекрывает_набранный_номер() {
        // Сохраняется выбранный из поиска, а не номер: и сверять надо его.
        val state = NewContactState(picked = "u9", normalized = "+79990000101", contacts = listOf(саша))
        assertNull(state.already)
    }

    @Test
    fun человек_без_tima_тоже_уже_есть() {
        val state = NewContactState(normalized = "+79990000102", contacts = listOf(безTima))
        assertEquals("p2", state.already?.id)
        assertNull(state.already?.userId)
    }
}
