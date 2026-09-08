package io.tima.feature.chat

import io.tima.domain.chat.CarryStep
import io.tima.domain.chat.CommentSwitches
import io.tima.domain.chat.PageEntry
import io.tima.domain.chat.PageStep
import io.tima.domain.chat.SwitchStep
import io.tima.domain.chat.UserPages
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Выключатели обсуждения на своей странице (ПЛАН-КАНАЛОВ К7, ADR-0024 §6).
 *
 * Проверяется то, что отличает выключатель от галки: он не меняет состояние на месте, а
 * перечитывает страницу — состояние приходит оттуда же, откуда записи.
 */
class CommentSwitchesStoreTest {

    private class Switches(var answer: SwitchStep = SwitchStep.Switched) : CommentSwitches {
        var channelCalls = 0
        var postCalls = 0
        var lastEnabled: Boolean? = null
        var lastClosed: Boolean? = null

        override suspend fun channel(channelId: String, enabled: Boolean): SwitchStep {
            channelCalls++
            lastEnabled = enabled
            return answer
        }

        override suspend fun post(channelId: String, postId: Long, closed: Boolean): SwitchStep {
            postCalls++
            lastClosed = closed
            return answer
        }
    }

    private class Pages(var enabled: Boolean = true) : UserPages {
        var reads = 0

        override suspend fun carry(kind: String, containerId: String, messageId: Long, level: Int) =
            CarryStep.Carried(1)

        override suspend fun page(userId: String): PageStep {
            reads++
            return PageStep.Page(
                entries = listOf(
                    PageEntry(postId = 7, level = 1, atMs = 0, authorId = "me", text = "запись"),
                ),
                channelId = "ch-1",
                commentsEnabled = enabled,
            )
        }

        override suspend fun remove(postId: Long) = CarryStep.Carried(postId)
    }

    @Test
    fun выключатель_страницы_перечитывает_её_а_не_меняет_на_месте() = runTest {
        val pages = Pages(enabled = true)
        val switches = Switches()
        val store = PageStore(pages, this, switches = switches)
        store.refresh()
        runCurrent()
        assertTrue(store.state.value.commentsEnabled)

        pages.enabled = false
        store.commentsOnPage(false)
        runCurrent()

        assertEquals(1, switches.channelCalls)
        assertEquals(false, switches.lastEnabled)
        assertEquals(2, pages.reads, "после переключения страница перечитывается")
        assertEquals(false, store.state.value.commentsEnabled)
    }

    @Test
    fun закрытие_обсуждения_записи_уходит_с_её_номером() = runTest {
        val switches = Switches()
        val store = PageStore(Pages(), this, switches = switches)
        store.refresh()
        runCurrent()

        store.commentsOnPost(7, closed = true)
        runCurrent()

        assertEquals(1, switches.postCalls)
        assertEquals(true, switches.lastClosed)
        assertNull(store.state.value.trouble)
    }

    @Test
    fun отказ_сервера_назван_словами() = runTest {
        val switches = Switches(answer = SwitchStep.NotAllowed)
        val store = PageStore(Pages(), this, switches = switches)
        store.refresh()
        runCurrent()

        store.commentsOnPage(false)
        runCurrent()

        assertEquals("Обсуждения выключает владелец страницы", store.state.value.trouble)
    }

    @Test
    fun без_выключателей_нажимать_нечего() = runTest {
        val pages = Pages()
        // Чужая страница: выключателей нет вовсе, и вызов не должен ни падать, ни ходить
        // в сеть — кнопки на экране тоже нет.
        val store = PageStore(pages, this)
        store.refresh()
        runCurrent()

        store.commentsOnPage(false)
        store.commentsOnPost(7, closed = true)
        runCurrent()

        assertEquals(1, pages.reads, "лишних перечитываний быть не должно")
        assertNull(store.state.value.trouble)
    }
}
