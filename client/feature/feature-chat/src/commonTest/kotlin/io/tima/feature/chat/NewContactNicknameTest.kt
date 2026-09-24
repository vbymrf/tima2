package io.tima.feature.chat

import io.tima.domain.chat.BookList
import io.tima.domain.chat.NicknameHit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Поиск по нику в подокне «Новый контакт» — Л10, Л11, Л18.
 *
 * Проверяется то, из-за чего этот вход вообще заведён: **человек без номера полноправен**,
 * и сохранить его надо, ничего про номер не спрашивая. И то, что легко «починить» обратно:
 * пустая выдача — не то же, что «сервер молчит», а заблокированный из выдачи не прячется.
 */
class NewContactNicknameTest {

    private val аня = NicknameHit("u-9", "anna_kovaleva")

    @Test
    fun по_одной_букве_не_ищем() {
        // Три знака — не аккуратность, а часть барьера: по одной букве выдачи не бывает,
        // бывает выгрузка каталога по алфавиту.
        assertTrue(!NewContactState(nick = "an").canSearch)
        assertTrue(NewContactState(nick = "ann").canSearch)
        assertTrue(!NewContactState(nick = "ann", searching = true).canSearch, "ищем — второй раз не зовём")
    }

    @Test
    fun пустая_выдача_не_то_же_что_молчание_сервера() {
        assertTrue(!NewContactState(found = null).nobodyFound, "не искали выдано за «никого нет»")
        assertTrue(NewContactState(found = emptyList()).nobodyFound)
    }

    @Test
    fun выбранный_по_нику_сохраняется_без_номера() {
        // Главное этой задачи: номера у него нет и не будет, а сохранить его надо.
        val state = NewContactState(found = listOf(аня), picked = "u-9")
        assertEquals(аня, state.pickedHit)
        assertTrue(state.canSave, "человек без номера не сохраняется")
        assertTrue(NewContactState(found = listOf(аня)).canSave.not(), "сохраняется никто")
    }

    @Test
    fun заблокированный_в_выдаче_помечен_а_не_спрятан() {
        // Спрятать нельзя: сервер о наших списках не знает и отбирать по ним не может.
        // Значит честнее показать и сказать, где он у нас, — иначе человек заведёт
        // второй раз того, кого сам же и убрал.
        val state = NewContactState(
            found = listOf(аня),
            inLists = mapOf("u-9" to BookList.Blocked),
        )
        assertEquals(listOf(аня), state.found)
        assertEquals(BookList.Blocked, state.inLists["u-9"])
    }
}
