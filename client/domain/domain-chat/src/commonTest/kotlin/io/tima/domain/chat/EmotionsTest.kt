package io.tima.domain.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Шкала «Оценки» и своя эмоция (ПЛАН-(СЦ) §1.18): одна на человека — смена переносит голос, снятие убирает; счёт
 * по убыванию, нулевых нет. Так экран меняет плашку сразу, до ответа сервера.
 */
class EmotionsTest {

    @Test
    fun шкала_восемь_эмоций_и_покой_посередине() {
        assertEquals(9, Emotion.entries.size)
        assertEquals(Emotion.AXES, Emotion.entries.map { it.axis }.distinct().size)
        assertNull(Emotion.CALM.positive, "покой — без пары")
        assertEquals(Emotion.INTEREST, Emotion.of("interest"))
        assertNull(Emotion.of("surprise"), "удивления в шкале нет")
    }

    @Test
    fun смена_своей_эмоции_переносит_голос() {
        val before = Reactions(listOf(EmotionCount("calm", 24), EmotionCount("interest", 9), EmotionCount("laughter", 4)), mine = "calm")
        val after = before.with("laughter")
        assertEquals("laughter", after.mine)
        assertEquals(listOf(EmotionCount("calm", 23), EmotionCount("interest", 9), EmotionCount("laughter", 5)), after.counts)
    }

    @Test
    fun снять_и_поставить_новую() {
        val one = Reactions(listOf(EmotionCount("approval", 1)), mine = "approval")
        assertEquals(Reactions(emptyList(), null), one.with(null), "единственная снятая — плашка пустая, «😊 0»")
        val fresh = Reactions().with("fear")
        assertEquals(listOf(EmotionCount("fear", 1)), fresh.counts)
        assertEquals("fear", fresh.mine)
    }
}
