package io.tima.domain.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Буфер переходов (ПЛАН-(БП)): «откуда» и «куда», новое — в конец, одинаковые подряд — одна точка. */
class JumpBufferTest {

    private fun from(n: Long, chat: String = "c") = JumpPoint(chat, n, JumpKind.FROM)
    private fun to(n: Long, chat: String = "c") = JumpPoint(chat, n, JumpKind.TO)

    @Test
    fun назад_к_куда_после_ухода_и_к_ссылке() {
        val b = JumpBuffer()
        b.jumped(from(1), to(9))
        assertEquals(1L, b.back()?.serverId, "стоим на «куда» — «назад» ведёт к ссылке")
        assertEquals(9L, b.forward()?.serverId)
        b.left()
        assertEquals(9L, b.back()?.serverId, "ушли с «куда» — «назад» к ней самой")
        assertEquals(1L, b.back()?.serverId, "ещё раз — к сообщению со ссылкой")
        assertNull(b.back())
        assertEquals(9L, b.forward()?.serverId)
        assertNull(b.forward())
    }

    @Test
    fun новый_переход_в_конец_вперёд_не_стирается() {
        val b = JumpBuffer()
        b.jumped(from(1), to(9))
        b.back()
        b.jumped(from(2), to(8))
        assertEquals(listOf(1L, 9L, 2L, 8L), b.points.map { it.serverId })
        assertEquals(3, b.at)
    }

    @Test
    fun одинаковые_подряд_одна_точка() {
        val b = JumpBuffer()
        val pointers = JumpPoint("", 0, JumpKind.POINTERS)
        b.jumped(pointers, to(9))
        b.back()
        b.jumped(pointers, to(9))
        assertEquals(2, b.points.size, "повторный приход из «Указателей» — те же две точки")
        b.jumped(from(9), to(5))
        assertEquals(listOf(0L, 9L, 5L), b.points.map { it.serverId }, "«откуда», равное последней точке, не дописано")
    }

    @Test
    fun не_больше_двадцати_точек() {
        val b = JumpBuffer()
        repeat(15) { b.jumped(from(it * 2L), to(it * 2L + 1)) }
        assertEquals(JumpBuffer.MAX, b.points.size)
        assertEquals(29L, b.points.last().serverId)
        assertEquals(b.points.lastIndex, b.at)
    }

    @Test
    fun очистка_и_запись_туда_обратно() {
        val b = JumpBuffer()
        b.jumped(from(1, "x:y"), to(2))
        b.left()
        val back = JumpBuffer.decode(b.encode())
        assertEquals(b.points, back.points)
        assertEquals(b.at, back.at)
        assertEquals(b.on, back.on)
        assertEquals(0, JumpBuffer.decode("испорчено").points.size)
        b.clear()
        assertNull(b.backTarget())
    }

    @Test
    fun чей_буфер() {
        assertEquals("personal:p", JumpBuffer.keyOf("p", group = false))
        assertEquals("community:s", JumpBuffer.keyOf("g", group = true, community = "s"))
        assertEquals("group:g", JumpBuffer.keyOf("g", group = true))
    }
}
