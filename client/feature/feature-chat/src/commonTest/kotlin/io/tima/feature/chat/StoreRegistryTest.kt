package io.tima.feature.chat

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Реестр store переписок (беда 2026-11-20): один на переписку, счёт ссылок, остановка простаивающих. */
class StoreRegistryTest {
    private class Fake(val name: String) {
        var stopped = false
    }

    private fun registry(maxIdle: Int = 3) =
        StoreRegistry<Fake>(stop = { it.stopped = true }, isStopped = { it.stopped }, idleMs = 60_000L, maxIdle = maxIdle)

    @Test
    fun повторное_открытие_берёт_тот_же_store_и_не_заводит_второго() = runTest {
        val r = registry()
        var made = 0
        val a = r.acquire("u/c1") { made++; Fake("a") }
        val b = r.acquire("u/c1") { made++; Fake("b") }
        assertSame(a, b)
        assertEquals(1, made, "второго наблюдателя быть не должно")
        assertEquals(2, r.refs("u/c1"))
    }

    @Test
    fun отпущенный_останавливается_через_минуту_а_возврат_раньше_его_сохраняет() = runTest {
        val r = registry()
        val a = r.acquire("u/c1") { Fake("a") }
        r.release("u/c1", backgroundScope)
        advanceTimeBy(30_000L); runCurrent()
        assertTrue(!a.stopped, "минуту ещё живёт")
        val back = r.acquire("u/c1") { Fake("b") }
        assertSame(a, back, "вернулся за минуту — тот же store")
        r.release("u/c1", backgroundScope)
        advanceTimeBy(61_000L); runCurrent()
        assertTrue(a.stopped, "срок вышел — остановлен")
        assertEquals(0, r.size)
        val fresh = r.acquire("u/c1") { Fake("c") }
        assertEquals("c", fresh.name, "остановленный не отдаётся — заводится новый")
    }

    @Test
    fun простаивающих_не_больше_предела() = runTest {
        val r = registry(maxIdle = 2)
        val s = (1..3).map { i -> r.acquire("u/c$i") { Fake("s$i") } }
        (1..3).forEach { i -> r.release("u/c$i", backgroundScope) }
        assertTrue(s[0].stopped, "самый давний простаивающий ушёл сразу")
        assertTrue(!s[1].stopped && !s[2].stopped)
    }

    @Test
    fun смена_аккаунта_останавливает_store_прежнего() = runTest {
        val r = registry()
        val old = r.acquire("u1/c1") { Fake("old") }
        val mine = r.acquire("u2/c1") { Fake("mine") }
        r.clearExcept("u2/")
        assertTrue(old.stopped)
        assertTrue(!mine.stopped)
    }
}
