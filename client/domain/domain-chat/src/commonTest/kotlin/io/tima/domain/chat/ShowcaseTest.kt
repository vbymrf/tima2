package io.tima.domain.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Что показывает окно «Описание» (заказчик 2026-10-18): суперсообщение — его; до трёх — лентой
 * целиком; больше — списком по три строки. Черновик суперсообщения — подкладки всех списком.
 */
class ShowcaseTest {

    private fun line(id: Long, only: Boolean = false) = ShowcaseLine(id, "u-1", DecodedLine("t$id", null), onlyContinuation = only)

    @Test
    fun суперсообщение_важнее_списка() {
        val view = ShowcasePage(2, listOf(line(1), line(2), line(3), line(4), line(5))).view()
        assertEquals(2L, assertIs<ShowcaseView.Super>(view).line.serverId)
    }

    @Test
    fun до_трёх_лентой_целиком_продолжение_не_считается() {
        val view = ShowcasePage(0, listOf(line(1), line(2), line(3), line(4, only = true))).view()
        assertEquals(listOf(1L, 2L, 3L), assertIs<ShowcaseView.Feed>(view).lines.map { it.serverId })
    }

    @Test
    fun больше_трёх_списком() {
        val view = ShowcasePage(0, (1L..4L).map { line(it) }).view()
        assertEquals(4, assertIs<ShowcaseView.List>(view).lines.size)
    }

    @Test
    fun пусто_и_супер_удалён() {
        assertIs<ShowcaseView.Empty>(ShowcasePage(0, emptyList()).view())
        // Номер суперсообщения остался, а самого нет в выдаче (удалено) — показываем как без него.
        assertIs<ShowcaseView.Feed>(ShowcasePage(9, listOf(line(1))).view())
    }

    @Test
    fun черновик_суперсообщения_подкладки_всех_кроме_прежнего_супер_и_продолжений() {
        val doc = ShowcasePage(2, listOf(line(1), line(2), line(3, only = true), line(4))).superDraft("group", "g-1")
        val underlays = doc.items.map { ((it as ComposedItem.Widget).widget as ComposedWidget.Underlay) }
        assertEquals(listOf(1L, 4L), underlays.map { it.message })
        assertEquals(listOf(3, 3), underlays.map { it.lines })
        assertEquals(0, doc.level)
    }
}
