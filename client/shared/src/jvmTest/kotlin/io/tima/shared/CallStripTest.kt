package io.tima.shared

import io.tima.core.call.CallStage
import io.tima.core.words.RussianWords
import kotlin.test.Test
import kotlin.test.assertEquals

/** Панель звонка над шапкой (ПЛАН-(ВО) ВО1, Р11): что со звонком — словами. */
class CallStripTest {

    private val w = RussianWords.call

    @Test
    fun состояния_звонка_словами() {
        assertEquals("входящий звонок", stripStatus(true, CallStage.Connecting, 0, 0, false, w))
        assertEquals("соединяется…", stripStatus(false, CallStage.Reconnecting, 40, 1, false, w))
        assertEquals("вызов…", stripStatus(false, CallStage.Connected, 0, 0, false, w))
        assertEquals("идёт · 3:12", stripStatus(false, CallStage.Connected, 192, 1, false, w))
    }

    @Test
    fun групповой_сколько_участников() {
        assertEquals("идёт · 0:05 · участников: 4", stripStatus(false, CallStage.Connected, 5, 3, true, w))
    }
}

/** Узор рамки панели звонка (2026-11-10): точки градиента по возрастанию при любом повороте, лучей 12. */
class CallStripRaysTest {

    @Test
    fun точки_по_возрастанию_и_лучей_двенадцать() {
        for (step in 0..100) {
            val stops = rays(step / 100f, androidx.compose.ui.graphics.Color.Green)
            val offsets = stops.map { it.first }
            kotlin.test.assertEquals(offsets.sorted(), offsets, "поворот $step")
            kotlin.test.assertTrue(offsets.all { it in 0f..1f })
            val lit = stops.toList().zipWithNext().count { (a, b) -> a.second.alpha == 0f && b.second.alpha > 0f }
            kotlin.test.assertTrue(lit in 12..13, "лучей $lit при повороте $step")
        }
    }
}
