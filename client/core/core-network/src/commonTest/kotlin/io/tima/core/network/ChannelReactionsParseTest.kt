package io.tima.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Запись канала с эмоциями и своим пальцем (ПЛАН-(СЦ) §1.18, сервер 0093): `reactions`, `my_emotion`, `my_vote` — в
 * той же записи, что лента хранит на устройстве; неизвестная эмоция пропускается, старая запись без полей — без них.
 */
class ChannelReactionsParseTest {

    @Test
    fun запись_с_эмоциями_и_пальцем() {
        val post = channelPostOf(
            """{"post_id":7,"author_id":"u-1","text":"маршрут","created_at_unix_ms":1,"comments":3,"comments_closed":false,""" +
                """"reactions":[{"emotion":"laughter","count":5},{"emotion":"surprise","count":2},{"emotion":"calm","count":1}],""" +
                """"my_emotion":"laughter","my_vote":-1}""",
        )!!
        assertEquals(listOf("laughter" to 5, "calm" to 1), post.reactions.map { it.emotion to it.count }, "удивления в шкале нет — мимо")
        assertEquals("laughter", post.myEmotion)
        assertEquals(-1, post.myVote)
    }

    @Test
    fun прежняя_запись_без_новых_полей() {
        val post = channelPostOf("""{"post_id":8,"author_id":"u-1","text":"было","created_at_unix_ms":1}""")!!
        assertEquals(emptyList(), post.reactions)
        assertNull(post.myEmotion)
        assertEquals(0, post.myVote)
    }

    @Test
    fun настройки_описания_по_умолчанию() {
        assertEquals(io.tima.domain.chat.ShowcaseSettings(), showcaseSettingsOf(null))
    }
}
