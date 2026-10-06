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
    fun номера_и_точки_из_записи_по_порядку_не_слова() {
        assertEquals(listOf("fifi", "geti", "bemu"), PhraseWords.parse("1. fifi 2) geti; 3.bemu."))
    }

    @Test
    fun кириллица_остаётся_словом_чтобы_её_показать() {
        // «е» во втором слове — кириллическая: выглядит как латинская, а в словаре её нет.
        assertEquals(listOf("fifi", "gеti"), PhraseWords.parse("fifi gеti"))
    }

    @Test
    fun пустое_поле_пустая_фраза() {
        assertEquals(emptyList(), PhraseWords.parse("  ,\n"))
    }
}
