package io.tima.feature.chat

import io.tima.domain.chat.CommentEntry
import io.tima.domain.chat.CommentStep
import io.tima.domain.chat.CommentsStep
import io.tima.domain.chat.PostComments
import io.tima.domain.chat.WriteComment
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Подокно комментариев (ADR-0024, ПЛАН-КАНАЛОВ К5).
 *
 * Проверяется то, что отличает разговор от списка: закрытое и пустое различаются,
 * пустой текст до сервера не доходит, «Ответить» подставляет обращение, а не вкладывает
 * третий уровень.
 */
class CommentsStoreTest {

    private class FakeComments(
        var conversation: CommentsStep = CommentsStep.Conversation(emptyList(), level = 1),
        var write: CommentStep = CommentStep.Written(7),
    ) : PostComments {
        var writes = 0
        var lastText: String = ""

        override suspend fun under(channelId: String, postId: Long, after: Long) = conversation

        override suspend fun write(channelId: String, postId: Long, text: String): CommentStep {
            writes++
            lastText = text
            return write
        }

        override suspend fun remove(channelId: String, postId: Long) = CommentStep.Written(postId)
    }

    @Test
    fun пустой_разговор_и_незагруженный_различаются() = runTest {
        val comments = FakeComments()
        val store = CommentsStore(comments, this, "ch", 5)

        assertTrue(!store.state.value.loaded, "до запроса состояние не загружено")

        store.refresh()
        runCurrent()
        assertTrue(store.state.value.loaded, "после ответа — загружено")
        assertTrue(store.state.value.entries.isEmpty())
        assertNull(store.state.value.trouble, "пустой разговор — не беда")
    }

    @Test
    fun закрытое_обсуждение_названо_словом_а_не_пустотой() = runTest {
        val comments = FakeComments(
            conversation = CommentsStep.Conversation(
                listOf(CommentEntry(1, "u1", "было сказано раньше", 0, 5)),
                level = 1,
            ),
            write = CommentStep.Closed,
        )
        val store = CommentsStore(comments, this, "ch", 5)
        store.refresh()
        runCurrent()

        store.draft("а я вот что думаю")
        store.send()
        runCurrent()

        assertTrue(store.state.value.closed, "закрытое обсуждение обязано быть в состоянии")
        // Старые видны: выключение закрывает новые, а не стирает чужие слова.
        assertEquals(1, store.state.value.entries.size)
        assertNull(store.state.value.trouble, "закрытое обсуждение — не ошибка")
    }

    @Test
    fun пустой_комментарий_до_сервера_не_доходит() = runTest {
        val comments = FakeComments()
        val store = CommentsStore(comments, this, "ch", 5)

        store.draft("   ")
        store.send()
        runCurrent()

        assertEquals(0, comments.writes, "пробелы — не текст")
        assertNull(store.state.value.trouble, "и не беда: человек просто ещё не написал")
    }

    @Test
    fun отправленное_очищает_поле_и_перечитывает_разговор() = runTest {
        val comments = FakeComments()
        val store = CommentsStore(comments, this, "ch", 5)

        store.draft("вот мой ответ")
        store.send()
        runCurrent()

        assertEquals(1, comments.writes)
        assertEquals("вот мой ответ", comments.lastText)
        assertEquals("", store.state.value.draft, "поле очищается — иначе текст уйдёт дважды")
        assertTrue(store.state.value.loaded, "разговор перечитан после отправки")
    }

    @Test
    fun ответить_подставляет_имя_а_не_вкладывает_третий_уровень() {
        assertEquals("@Борис ", WriteComment.mention("Борис", ""))
        assertEquals("@Борис уже написанное", WriteComment.mention("Борис", "уже написанное"))
        // Второе нажатие на том же имени не удваивает обращение.
        assertEquals("@Борис ", WriteComment.mention("Борис", "@Борис "))
    }

    @Test
    fun корня_нет_говорится_словами() = runTest {
        val store = CommentsStore(FakeComments(conversation = CommentsStep.NoRoot), this, "ch", 5)
        store.refresh()
        runCurrent()

        assertTrue(store.state.value.gone, "запись убрали — разговора нет")
        assertNull(store.state.value.trouble, "это не поломка и не отказ")
    }
}
