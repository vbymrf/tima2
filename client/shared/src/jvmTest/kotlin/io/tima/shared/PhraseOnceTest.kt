package io.tima.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Слова фразы — один раз и ненадолго (заказчик 2026-09-30, 2а). */
class PhraseOnceTest {

    @Test
    fun слова_берутся_один_раз() {
        PhraseOnce.hold(listOf("а", "б"))

        assertEquals(listOf("а", "б"), PhraseOnce.take())
        assertNull(PhraseOnce.take(), "второй раз слов нет — секрет не лежит в памяти дольше нужного")
    }

    @Test
    fun без_входа_по_фразе_слов_нет() {
        PhraseOnce.take()
        assertNull(PhraseOnce.take())
    }
}
