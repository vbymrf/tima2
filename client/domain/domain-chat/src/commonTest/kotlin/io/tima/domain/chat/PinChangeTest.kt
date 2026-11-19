package io.tima.domain.chat

import kotlin.test.Test
import kotlin.test.assertEquals

/** Служебная строка закреплённых (ПЛАН-(СЦ) СЦ12): первое и полное снятие — как прежде, остальное — «обновил(а)». */
class PinChangeTest {
    @Test
    fun первое_закрепление_и_полное_снятие_как_прежде() {
        assertEquals(PinChange.Pinned, pinChange(emptyList(), listOf(5), 5))
        assertEquals(PinChange.Unpinned, pinChange(listOf(5, 6), emptyList(), 0))
        assertEquals(PinChange.Pinned, pinChange(emptyList(), null, 7), "старое событие одним номером")
        assertEquals(PinChange.Unpinned, pinChange(listOf(7), null, 0))
    }

    @Test
    fun любое_другое_изменение_обновил() {
        assertEquals(PinChange.Updated, pinChange(listOf(5), listOf(5, 6), 6))
        assertEquals(PinChange.Updated, pinChange(listOf(5, 6), listOf(6), 0))
        assertEquals(PinChange.Updated, pinChange(listOf(5), listOf(6), 6), "замена одного другим")
    }

    @Test
    fun своё_событие_после_оптимистичного_обновления() {
        assertEquals(PinChange.Pinned, pinChange(listOf(5), listOf(5), 5), "первое своё")
        assertEquals(PinChange.Updated, pinChange(listOf(5, 6), listOf(5, 6), 6), "своё второе")
    }
}
