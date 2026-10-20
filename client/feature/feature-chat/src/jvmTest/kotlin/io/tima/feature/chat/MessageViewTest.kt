package io.tima.feature.chat

import io.tima.domain.chat.RichBlock
import io.tima.domain.chat.RichContent
import io.tima.domain.chat.RichText
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Лента и просмотр редактора делят сообщение одинаково (заказчик 2026-10-20: «Показать сообщение» —
 * как в чате): хвост подкладок — под пузырём, остальное — в пузыре.
 */
class MessageViewTest {

    private val text = RichBlock.Paragraph(RichText("до"))
    private fun underlay(id: Long) = RichBlock.Underlay("chat", "c-1", id, author = false, lines = 0)

    @Test
    fun хвост_подкладок_уходит_под_пузырь() {
        val (inside, apart) = splitUnderlays(RichContent(listOf(text, underlay(1), underlay(2))))
        assertEquals(listOf<RichBlock>(text), inside.blocks)
        assertEquals(listOf<RichBlock>(underlay(1), underlay(2)), apart)
    }

    @Test
    fun подкладка_в_середине_остаётся_в_пузыре() {
        val blocks = listOf(underlay(1), text)
        val (inside, apart) = splitUnderlays(RichContent(blocks))
        assertEquals(blocks, inside.blocks)
        assertEquals(emptyList(), apart)
    }

    @Test
    fun одни_подкладки_остаются_в_пузыре() {
        val blocks = listOf(underlay(1))
        val (inside, apart) = splitUnderlays(RichContent(blocks))
        assertEquals(blocks, inside.blocks)
        assertEquals(emptyList(), apart)
    }
}
