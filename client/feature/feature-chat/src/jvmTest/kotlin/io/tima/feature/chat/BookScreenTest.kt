package io.tima.feature.chat

import io.tima.domain.chat.BookEntry
import io.tima.domain.chat.SyncStep
import io.tima.testui.capture
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * «Контакты» без разрешения на книгу телефона (заказчик 2026-10-08): записанное показывается и
 * так, просьба стоит над списком, «Отказаться» её убирает.
 */
class BookScreenTest {

    private val written = listOf(BookEntry(id = "tel:+79000000001", phone = "+79000000001", nameOwn = "Аня"))

    @Test
    fun без_разрешения_записанные_видны_а_отказ_убирает_просьбу() {
        val asked = capture("книга-без-разрешения", 400, 500, dark = false) {
            BookScreen(state = BookState(all = written, everyone = written, sync = SyncStep.NeedPermission), onOpen = {}, onAllow = {}, onRefuse = {})
        }
        val refused = capture("книга-отказались", 400, 500, dark = false) {
            BookScreen(state = BookState(all = written, everyone = written, sync = SyncStep.NeedPermission, permissionRefused = true), onOpen = {}, onAllow = {}, onRefuse = {})
        }
        val read = capture("книга-прочитана", 400, 500, dark = false) {
            BookScreen(state = BookState(all = written, everyone = written), onOpen = {}, onAllow = {}, onRefuse = {})
        }
        assertTrue(asked.difference(refused) > 0.005, "просьба не видна")
        assertTrue(refused.difference(read) < 0.001, "после отказа записанные не показаны так же, как с разрешением")
    }
}
