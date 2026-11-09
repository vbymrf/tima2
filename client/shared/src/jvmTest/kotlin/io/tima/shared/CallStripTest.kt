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
