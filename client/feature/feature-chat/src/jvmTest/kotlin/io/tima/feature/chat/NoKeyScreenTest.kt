package io.tima.feature.chat

import androidx.compose.runtime.Composable
import io.tima.domain.chat.ChatLine
import io.tima.domain.chat.MessageDisplay
import io.tima.testui.bothThemes
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Группа без ключа говорит об этом словами (ПЛАН-СОЦИУМА Г10).
 *
 * **Пустая группа и группа без ключа выглядели одинаково**, и это не мелочь оформления:
 * молчание экрана человек читает как «здесь ничего нет», тогда как здесь всё есть и ничего
 * не открывается. Проверяется ровно это различие — на картинке, потому что в состоянии обе
 * выглядят пустым списком.
 */
class NoKeyScreenTest {

    @Test
    fun группа_без_ключа_отличается_от_пустой() {
        val noKey = bothThemes("группа-без-ключа", WIDTH, HEIGHT) {
            screen(ChatState(group = true, keyAskMay = true, noGroupKey = true))
        }
        val empty = bothThemes("группа-пустая", WIDTH, HEIGHT) {
            screen(ChatState(group = true, keyAskMay = true))
        }

        val difference = noKey.getValue("светлая").difference(empty.getValue("светлая"))
        assertTrue(difference > 0.0, "группа без ключа выглядит как пустая — про ключ не сказано")
    }

    @Test
    fun полоса_остаётся_и_когда_сообщения_есть() {
        // Сообщения приехали, но не открываются: полоса нужна и здесь — иначе человек
        // решит, что сломался разбор одного сообщения, а не отсутствует ключ.
        val withLines = bothThemes("без-ключа-и-строки", WIDTH, HEIGHT) {
            screen(
                ChatState(
                    group = true,
                    keyAskMay = true,
                    noGroupKey = true,
                    lines = listOf(unreadable()),
                ),
            )
        }
        val onlyLines = bothThemes("только-строки", WIDTH, HEIGHT) {
            screen(ChatState(group = true, keyAskMay = true, lines = listOf(unreadable())))
        }

        val difference = withLines.getValue("светлая").difference(onlyLines.getValue("светлая"))
        assertTrue(difference > 0.0, "полоса пропала, когда в списке есть строки")
    }

    private companion object {
        const val WIDTH = 380
        const val HEIGHT = 800

        fun unreadable() = ChatLine(
            dedupKey = "d-1",
            chatId = "g-1",
            display = MessageDisplay.UNREADABLE,
            text = null,
            outgoing = false,
            atMs = 1_700_000_000_000,
            localId = 1,
        )

        @Composable
        fun screen(state: ChatState) = ChatScreen(
            state = state,
            peer = "Ядро",
            caption = "12 участников",
            onSet = {},
            onSend = {},
            onBack = {},
        )
    }
}
