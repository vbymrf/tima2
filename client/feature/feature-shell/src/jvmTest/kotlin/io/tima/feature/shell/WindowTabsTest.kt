package io.tima.feature.shell

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Вкладки окон — одно место (беда 2026-10-08): окно, ушедшее при переходе, возвращается на ту же
 * вкладку; вкладку, которой больше нет, заменяет первая.
 */
class WindowTabsTest {

    private val tabs = listOf(WindowTab.Answers, WindowTab.Reactions, WindowTab.Collections)

    @Test
    fun вкладка_окна_помнится_и_не_мешает_другим() {
        val kept = WindowTabs()
        assertEquals(WindowTab.Answers, kept.of(Window.Activity, tabs), "без выбора — первая")

        kept.choose(Window.Activity, WindowTab.Reactions)
        kept.choose(Window.Page, WindowTab.Feed)

        assertEquals(WindowTab.Reactions, kept.of(Window.Activity, tabs))
    }

    @Test
    fun исчезнувшая_вкладка_заменяется_первой() {
        val kept = WindowTabs()
        kept.choose(Window.Activity, WindowTab.Reactions)

        assertEquals(WindowTab.Answers, kept.of(Window.Activity, tabs - WindowTab.Reactions))
    }

    @Test
    fun у_своего_ряда_вкладок_то_же_место() {
        // «Телефон» держит вкладку сам, через `state`, но в том же месте.
        val kept = WindowTabs()
        val phone = kept.state(Window.Phone, WindowTab.Chats)
        assertEquals(WindowTab.Chats, phone.value)
        phone.value = WindowTab.Calls
        assertEquals(WindowTab.Calls, kept.state(Window.Phone, WindowTab.Chats).value)
    }
}
