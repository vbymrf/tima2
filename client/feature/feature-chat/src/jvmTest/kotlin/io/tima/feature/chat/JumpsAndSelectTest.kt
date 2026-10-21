package io.tima.feature.chat

import androidx.compose.foundation.lazy.LazyListState
import io.tima.domain.chat.JumpKind
import io.tima.domain.chat.JumpPoint
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
        val some = FeedJumps().apply { buffer.jumped(JumpPoint("", 5, JumpKind.FROM), JumpPoint("", 7, JumpKind.TO)) }
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
        j.buffer.jumped(JumpPoint("", 1, JumpKind.FROM), JumpPoint("", 2, JumpKind.TO))
        assertTrue(j.canBack)
        j.clear()
        assertTrue(!j.canBack && !j.canForward, "долгое нажатие на «назад» очищает буфер")
        j.flash(9)
        assertNull(j.highlight, "подсветка коротка и гаснет сама")
    }

    /** ПЛАН-(БП): буфер общий у переписок одного ключа и пишется на устройство при каждом изменении. */
    @Test
    fun буфер_общий_и_пишется_на_устройство() {
        val written = mutableMapOf<String, String>()
        val buffers = JumpBuffers(emptyMap()) { k, v -> written[k] = v }
        val went = mutableListOf<Pair<String, Long>>()
        val a = FeedJumps(JumpPlace(buffers, "a", "community:s", sameBuffer = { it == "b" }, elsewhere = { c, n -> went += c to n }))
        a.from = 3
        a.elsewhere("b", 9)
        assertEquals(listOf("b" to 9L), went, "ссылка в группу того же сообщества — туда")
        val b = FeedJumps(JumpPlace(buffers, "b", "community:s", elsewhere = { c, n -> went += c to n }))
        assertTrue(b.canBack, "в другой переписке сообщества — тот же буфер")
        b.step(b.buffer.back()) { }
        assertEquals("a" to 3L, went.last(), "«назад» — в прежнюю переписку, к сообщению со ссылкой")
        val restored = JumpBuffers(written) { _, _ -> }.of("community:s")
        assertEquals(listOf(3L, 9L), restored.points.map { it.serverId }, "после перезапуска буфер на месте")
    }
}
