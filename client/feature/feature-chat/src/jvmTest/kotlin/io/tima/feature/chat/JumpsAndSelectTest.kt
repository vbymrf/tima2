package io.tima.feature.chat

import androidx.compose.foundation.lazy.LazyListState
import io.tima.testui.capture
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Выделение части долгим нажатием и переходы «назад», «вперёд», «вниз» (Решения §11.1, §11.4). */
class JumpsAndSelectTest {

    @Test
    fun лист_выделения_рисуется() {
        val a = capture("выделение", 360, 400, dark = false) { SelectSheet("Сбор в 9:00 у входа", onCopyAll = {}, onClose = {}) }
        val b = capture("выделение-тёмное", 360, 400, dark = true) { SelectSheet("Сбор в 9:00 у входа", onCopyAll = {}, onClose = {}) }
        assertTrue(a.difference(b) > 0.0)
    }

    @Test
    fun кнопки_назад_и_вперёд_появляются_когда_есть_куда() {
        val none = FeedJumps()
        val some = FeedJumps().apply { back.add(5); forward.add(7) }
        val a = capture("переходы-нет", 120, 200, dark = false) { JumpButtons(none, emptyList(), LazyListState()) }
        val b = capture("переходы-есть", 120, 200, dark = false) { JumpButtons(some, emptyList(), LazyListState()) }
        assertTrue(a.difference(b) > 0.0)
    }

    @Test
    fun просьба_перехода_и_подсветка() = runTest {
        val j = FeedJumps()
        j.go(42)
        assertEquals(42L, j.request)
        j.taken()
        assertNull(j.request)
        j.back.add(1)
        j.forward.add(2)
        j.clear()
        assertTrue(j.back.isEmpty() && j.forward.isEmpty(), "долгое нажатие на «назад» забывает цепочку")
        j.flash(9)
        assertNull(j.highlight, "подсветка коротка и гаснет сама")
    }
}
