package io.tima.core.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Пауза порядка после прокрутки (ПЛАН-(ВС) ВС3, заказчик 2026-11-20): листаешь — строки стоят; после N секунд от
 * последнего движения список встаёт по порядку; каждое движение продлевает паузу.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CalmOrderTest {

    @Test
    fun во_время_паузы_порядок_держится_потом_встаёт() = runTest {
        val calm = CalmOrder(10_000, backgroundScope)
        assertEquals(listOf("a", "b", "c"), calm.order(listOf("a", "b", "c")) { it })
        calm.moved()
        // Пришло сообщение в «c» — по порядку она первая, но список листают: стоит на месте.
        assertEquals(listOf("a", "b", "c"), calm.order(listOf("c", "a", "b")) { it })
        advanceTimeBy(9_000); runCurrent()
        calm.moved() // ещё движение — пауза заново
        advanceTimeBy(9_000); runCurrent()
        assertEquals(listOf("a", "b", "c"), calm.order(listOf("c", "a", "b")) { it }, "движение не продлило паузу")
        advanceTimeBy(1_500); runCurrent()
        assertEquals(listOf("c", "a", "b"), calm.order(listOf("c", "a", "b")) { it }, "после паузы — по порядку")
    }

    @Test
    fun новые_сверху_ушедшие_убраны() = runTest {
        val calm = CalmOrder(10_000, backgroundScope)
        calm.order(listOf("a", "b", "c")) { it }
        calm.moved()
        assertEquals(listOf("n", "a", "c"), calm.order(listOf("c", "n", "a")) { it })
    }

    @Test
    fun без_паузы_порядок_как_есть() = runTest {
        val calm = CalmOrder(0, backgroundScope)
        calm.order(listOf("a", "b")) { it }
        calm.moved()
        assertEquals(listOf("b", "a"), calm.order(listOf("b", "a")) { it })
    }
}
