package io.tima.feature.chat

import io.tima.domain.chat.RichBlock
import io.tima.domain.chat.RichContent
import io.tima.testui.capture
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Ссылка (заказчик 2026-10-21): кнопкой с названием — только название, без домена; текстом — синяя
 * полоса справа на рамке пузыря. Снимки — в `build/снимки`.
 */
class LinkLookScreenTest {

    private fun shot(name: String, link: RichBlock.Link) = capture(name, 340, 140, dark = false) {
        io.tima.core.ui.Bubble(my = false, author = "Аня") { RichBody(RichContent(listOf(link))) }
    }

    @Test
    fun кнопка_с_названием_и_текст_с_полосой() {
        val button = shot("ссылка-кнопка-название", RichBlock.Link("https://tima.ru/a", "button", "Афиша", ""))
        val plain = shot("ссылка-текст-полоса", RichBlock.Link("https://tima.ru/a", "plain", "Афиша", ""))
        val bare = shot("ссылка-кнопка-без-названия", RichBlock.Link("https://tima.ru/a", "button", "", ""))
        assertTrue(button.difference(plain) > 0.0)
        assertTrue(button.difference(bare) > 0.0, "кнопка с названием и без выглядят одинаково")
    }
}
