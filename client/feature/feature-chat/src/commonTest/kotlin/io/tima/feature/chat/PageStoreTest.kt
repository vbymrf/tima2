package io.tima.feature.chat

import io.tima.domain.chat.CarryStep
import io.tima.domain.chat.PageEntry
import io.tima.domain.chat.PageStep
import io.tima.domain.chat.UserPages
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Страница глазами экрана (ПЛАН-СОЦИУМА Г8).
 *
 * Проверяется то, что человек различает: пустая страница против неудачной загрузки,
 * принесённое от лица источника, и главное — **невыносимое не уходит на сервер**. Правило
 * «уровни 3 и −1 не выносятся» живёт в домене, а экран обязан ему следовать, а не узнавать
 * о нём из отказа.
 */
class PageStoreTest {

    @Test
    fun невыносимое_до_сервера_не_доходит() = runTest {
        val pages = CountingPages()
        val store = PageStore(pages, backgroundScope)

        store.carry(groupId = "g-1", messageId = 7, was = 3)
        runCurrent()

        assertEquals(0, pages.carries, "уровень «по разрешению» ушёл на сервер")
        assertNotNull(store.state.value.trouble, "отказ обязан быть назван словами")
    }

    @Test
    fun шифр_не_уносится() = runTest {
        val pages = CountingPages()
        val store = PageStore(pages, backgroundScope)

        store.carry(groupId = "g-1", messageId = 7, was = -1)
        runCurrent()

        assertEquals(0, pages.carries, "зашифрованное ушло на сервер")
    }

    @Test
    fun выносимое_уходит_и_страница_перечитывается() = runTest {
        val pages = CountingPages()
        val store = PageStore(pages, backgroundScope)

        store.carry(groupId = "g-1", messageId = 7, was = 1)
        runCurrent()

        assertEquals(1, pages.carries)
        assertTrue(7L in store.state.value.carried, "унесённое не отмечено")
        // Список обязан перечитаться: принесённое должно появиться на странице сразу, а не
        // «когда-нибудь потом».
        assertTrue(pages.reads >= 1, "страница не перечитана после переноса")
        assertNull(store.state.value.trouble)
    }

    @Test
    fun второе_нажатие_не_шлёт_второй_запрос() = runTest {
        val pages = CountingPages(slow = true)
        val store = PageStore(pages, backgroundScope)

        store.carry("g-1", 7, was = 1)
        store.carry("g-1", 7, was = 1)
        runCurrent()

        assertEquals(1, pages.carries, "второе нажатие послало второй запрос")
    }

    @Test
    fun пустая_страница_и_незагруженная_различаются() = runTest {
        val store = PageStore(CountingPages(), backgroundScope)

        assertTrue(!store.state.value.loaded, "до ответа сервера страница не может считаться пустой")
        store.refresh()
        runCurrent()
        assertTrue(store.state.value.loaded)
    }

    @Test
    fun ленты_ещё_нет_это_не_беда() = runTest {
        // «Страницу не завели» и «сервер отказал» — разные вещи, и жаловаться на первое
        // значило бы пугать человека тем, что работает как задумано.
        val store = PageStore(CountingPages(page = { PageStep.NoPage }), backgroundScope)

        store.refresh()
        runCurrent()

        assertTrue(store.state.value.entries.isEmpty())
        assertNull(store.state.value.trouble, "отсутствие ленты названо бедой")
        assertTrue(store.state.value.loaded)
    }

    private class CountingPages(
        private val slow: Boolean = false,
        private val page: () -> PageStep = {
            PageStep.Page(
                listOf(
                    PageEntry(
                        postId = 1,
                        level = 1,
                        atMs = 1_700_000_000_000,
                        authorId = "u-author",
                        text = "чужая запись",
                        carriedBy = "u-me",
                        sourceTitle = "Ядро",
                        refGroupId = "g-1",
                        refMessageId = 7,
                    ),
                ),
            )
        },
    ) : UserPages {
        var carries = 0
            private set
        var reads = 0
            private set

        override suspend fun carry(groupId: String, messageId: Long, level: Int): CarryStep {
            carries++
            // «Медленный» ответ нужен, чтобы второе нажатие пришло до исхода первого.
            return if (slow) CarryStep.Offline(1_000) else CarryStep.Carried(carries.toLong())
        }

        override suspend fun page(userId: String): PageStep {
            reads++
            return page.invoke()
        }

        override suspend fun remove(postId: Long): CarryStep = CarryStep.Carried(postId)
    }
}
