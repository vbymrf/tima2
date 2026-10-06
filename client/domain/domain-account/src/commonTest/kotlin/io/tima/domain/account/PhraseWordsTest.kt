package io.tima.domain.account

import kotlin.test.Test
import kotlin.test.assertEquals

class PhraseWordsTest {

    @Test
    fun заглавная_от_клавиатуры_и_запятые_не_мешают() {
        assertEquals(listOf("fifi", "geti", "bemu"), PhraseWords.parse("Fifi geti, Bemu"))
        assertEquals(listOf("fifi", "geti"), PhraseWords.parse(" , fifi\n  GETI  "))
    }

    @Test
    fun пустое_поле_пустая_фраза() {
        assertEquals(emptyList(), PhraseWords.parse("  ,\n"))
    }
}
