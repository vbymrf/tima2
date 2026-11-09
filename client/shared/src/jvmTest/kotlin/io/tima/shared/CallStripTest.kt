package io.tima.shared

import io.tima.core.call.CallStage
import io.tima.core.words.RussianWords
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
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

/** Узор рамки панели звонка (2026-11-10): точки градиента по возрастанию при любом повороте, лучей 24. */
class CallStripRaysTest {

    @Test
    fun точки_по_возрастанию_и_лучей_двадцать_четыре() {
        for (step in 0..100) {
            val stops = rays(step / 100f, androidx.compose.ui.graphics.Color.Green)
            val offsets = stops.map { it.first }
            kotlin.test.assertEquals(offsets.sorted(), offsets, "поворот $step")
            kotlin.test.assertTrue(offsets.all { it in 0f..1f })
            val lit = stops.toList().zipWithNext().count { (a, b) -> a.second.alpha == 0f && b.second.alpha > 0f }
            kotlin.test.assertTrue(lit in 24..25, "лучей $lit при повороте $step")
        }
    }
}

/** «Завершить» панели звонка (заказчик 2026-11-10): трубка стоит по середине круга, а не «упала на низ». */
class HangUpIconTest {

    @Test
    fun трубка_по_середине_круга() {
        val side = 72
        val shot = io.tima.testui.capture("панель-завершить", side, side, dark = false) {
            androidx.compose.foundation.layout.Box(
                androidx.compose.ui.Modifier.size(side.dp).background(androidx.compose.ui.graphics.Color.Red, androidx.compose.foundation.shape.CircleShape),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) { HangUpIcon(androidx.compose.ui.graphics.Color.White, androidx.compose.ui.Modifier.size((side * 0.8f).dp)) }
        }
        val white = (0 until side).flatMap { x -> (0 until side).map { y -> x to y } }.filter { (x, y) ->
            val c = shot.color(x, y)
            c.red > 0.8f && c.green > 0.8f && c.blue > 0.8f
        }
        kotlin.test.assertTrue(white.isNotEmpty(), "трубки не видно")
        val midX = (white.minOf { it.first } + white.maxOf { it.first }) / 2.0
        val midY = (white.minOf { it.second } + white.maxOf { it.second }) / 2.0
        kotlin.test.assertTrue(kotlin.math.abs(midX - side / 2.0) <= 2.0, "трубка сдвинута вбок: середина $midX")
        kotlin.test.assertTrue(kotlin.math.abs(midY - side / 2.0) <= 2.0, "трубка сдвинута по высоте: середина $midY")
        val wide = white.maxOf { it.first } - white.minOf { it.first }
        kotlin.test.assertTrue(wide >= side * 0.6, "трубка мелкая: $wide из $side")
    }
}
