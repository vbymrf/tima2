package io.tima.feature.chat

import io.tima.domain.chat.ChatLine
import io.tima.domain.chat.MessageDisplay
import io.tima.testui.capture
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Окно групповой переписки в снимках.
 *
 * Проверяется одно, ради чего групповой экран и отличается от личного: **видно, кто
 * написал**. В личной переписке собеседник один и назван в шапке; в группе реплика без
 * автора теряет половину смысла — «кто это сказал» и есть половина сообщения.
 */
class GroupChatScreenTest {

    private val replies = listOf(
        line("m2", "u-3", "а я уже там"),
        line("m1", "u-2", "выходим в семь"),
    )

    @Test
    fun в_группе_автор_нарисован_а_в_личной_нет() {
        val personal = capture("группа-личная", WIDTH, HEIGHT, dark = false) {
            screen(ChatState(lines = replies, group = false))
        }
        val group = capture("группа-групповая", WIDTH, HEIGHT, dark = false) {
            screen(
                ChatState(
                    lines = replies,
                    group = true,
                    names = mapOf("u-2" to "Аня", "u-3" to "Петя"),
                ),
            )
        }
        assertTrue(
            group.difference(personal) > 0.0,
            "групповая переписка нарисована так же, как личная — автор не показан",
        )
    }

    @Test
    fun два_автора_подряд_не_слипаются() {
        // Смена автора обязана разрывать цепочку: иначе два человека подряд выглядят
        // одним, и имя второго не показывается вовсе.
        val different = capture("группа-разные", WIDTH, HEIGHT, dark = false) {
            screen(ChatState(lines = replies, group = true, names = names))
        }
        val same = capture("группа-одинаковые", WIDTH, HEIGHT, dark = false) {
            screen(
                ChatState(
                    lines = listOf(line("m2", "u-2", "а я уже там"), line("m1", "u-2", "выходим в семь")),
                    group = true,
                    names = names,
                ),
            )
        }
        assertTrue(different.difference(same) > 0.0, "смена автора не разорвала цепочку")
    }

    @Test
    fun неизвестное_имя_не_выдумывается() {
        // Показать чужое имя хуже, чем не показать никакого: человек решит, что писал
        // не тот, кто писал.
        val nameWithout = capture("группа-без-имени", WIDTH, HEIGHT, dark = false) {
            screen(ChatState(lines = replies, group = true, names = emptyMap()))
        }
        val withNames = capture("группа-с-именами", WIDTH, HEIGHT, dark = false) {
            screen(ChatState(lines = replies, group = true, names = names))
        }
        assertTrue(nameWithout.difference(withNames) > 0.0, "имена никак не влияют на экран")
    }

    private companion object {
        const val WIDTH = 380
        const val HEIGHT = 600

        val names = mapOf("u-2" to "Аня", "u-3" to "Петя")

        fun line(key: String, author: String, text: String) = ChatLine(
            dedupKey = key,
            chatId = "g-1",
            display = MessageDisplay.RECEIVED,
            text = text,
            outgoing = false,
            atMs = 1_750_000_000_000,
            localId = key.last().code.toLong(),
            senderId = author,
        )

        @androidx.compose.runtime.Composable
        fun screen(state: ChatState) = ChatScreen(
            state = state,
            peer = "Поход",
            onSet = {},
            onSend = {},
            onBack = {},
        )
    }
}
