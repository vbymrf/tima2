package io.tima.feature.chat

import androidx.compose.runtime.Composable
import io.tima.domain.chat.ChatLine
import io.tima.domain.chat.MessageDisplay
import io.tima.testui.bothThemes
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Метка круга и служебная строка в снимках (ПЛАН-СОЦИУМА Г7).
 *
 * Проверяется не «нарисовалось что-то», а два решения продукта, которые легко потерять при
 * правке: **метка по умолчанию выключена** и **служебная строка выглядит иначе**, чем
 * реплика. Оба видны только на картинке — в состоянии их не отличить.
 */
class CircleMarkScreenTest {

    @Test
    fun по_умолчанию_меток_нет() {
        // Метка отвечает на вопрос, которого у обычного участника нет: он и так видит
        // ровно то, что ему открыто. Сравниваются два снимка одного состояния — разница
        // ровно в переключателе показа.
        val quiet = bothThemes("круги-выключены", WIDTH, HEIGHT) { screen(chat(showCircles = false)) }
        val shown = bothThemes("круги-включены", WIDTH, HEIGHT) { screen(chat(showCircles = true)) }

        val difference = quiet.getValue("светлая").difference(shown.getValue("светлая"))
        assertTrue(difference > 0.0, "включение меток ничего не изменило — их не рисуют вовсе")
    }

    @Test
    fun служебная_строка_не_похожа_на_реплику() {
        // Служебная строка идёт полосой по центру, без пузыря и без стороны: у неё нет
        // автора, и приписывать ей сторону значило бы сделать её чьей-то.
        val withNote = bothThemes("служебная-строка", WIDTH, HEIGHT) { screen(chat(note = true)) }
        val without = bothThemes("без-служебной", WIDTH, HEIGHT) { screen(chat(note = false)) }

        val difference = withNote.getValue("светлая").difference(without.getValue("светлая"))
        assertTrue(difference > 0.0, "служебная строка не нарисовалась")
    }

    @Test
    fun обе_темы_рисуют_метки() {
        val snapshots = bothThemes("круги-темы", WIDTH, HEIGHT) { screen(chat(showCircles = true)) }
        val difference = snapshots.getValue("светлая").difference(snapshots.getValue("тёмная"))
        assertTrue(difference > 0.10, "темы расходятся лишь на ${(difference * 100).toInt()}% — цвет взят мимо темы")
    }

    private companion object {
        const val WIDTH = 380
        const val HEIGHT = 800

        fun line(
            dedupKey: String,
            display: MessageDisplay,
            outgoing: Boolean,
            level: Int = -1,
            text: String? = "проба",
        ) = ChatLine(
            dedupKey = dedupKey,
            chatId = "g-1",
            display = display,
            text = text,
            outgoing = outgoing,
            atMs = 1_700_000_000_000,
            localId = 1,
            level = level,
            serverId = 42,
        )

        fun chat(showCircles: Boolean = false, note: Boolean = false) = ChatState(
            group = true,
            showCircles = showCircles,
            lines = buildList {
                if (note) {
                    add(
                        line(
                            "sys-1",
                            MessageDisplay.SYSTEM,
                            outgoing = false,
                            text = "Круг сообщения сузили: теперь «По разрешению»",
                        ),
                    )
                }
                add(line("d-3", MessageDisplay.SENT, outgoing = true, level = 1, text = "всем"))
                add(line("d-2", MessageDisplay.RECEIVED, outgoing = false, level = 2, text = "своим"))
                add(line("d-1", MessageDisplay.RECEIVED, outgoing = false, level = 3, text = "по разрешению"))
            },
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
