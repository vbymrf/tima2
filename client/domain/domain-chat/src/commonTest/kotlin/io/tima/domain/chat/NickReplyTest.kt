package io.tima.domain.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** `@Ник` набранным текстом — ответ на последнее сообщение человека (Решения §7). */
class NickReplyTest {

    private fun line(id: Long, who: String?, outgoing: Boolean = false, deleted: Boolean = false) = ChatLine(
        dedupKey = "d-$id", chatId = "g", display = MessageDisplay.SENT, text = "t$id",
        outgoing = outgoing, atMs = id, localId = id, serverId = id, senderId = who, deleted = deleted,
    )

    private val people = mapOf("u-anya" to ChatPerson(name = "Аня", nick = "anya"), "u-bob" to ChatPerson(name = "Боб", userName = "bob_77"))

    @Test
    fun последнее_сообщение_человека() {
        // Новые первыми, как отдаёт база.
        val lines = listOf(line(9, "u-bob"), line(8, "u-anya", deleted = true), line(7, "u-anya"), line(6, "u-anya"))
        assertEquals("u-anya" to 7L, NickReply.target("@Anya, как дела?", people, lines), "удалённое пропускается, регистр не важен")
        assertEquals("u-bob" to 9L, NickReply.target("ну, @bob_77.", people, lines), "точка в конце — не часть ника")
    }

    @Test
    fun нет_ответа() {
        val lines = listOf(line(5, "u-anya"))
        assertNull(NickReply.target("почта a@anya.ru", people, lines), "адрес почты — не ник")
        assertNull(NickReply.target("@кто-то", people, lines), "незнакомый ник")
        assertNull(NickReply.target("@bob_77", people, lines), "сообщений Боба нет — только упоминание")
        assertNull(NickReply.target("без ника", people, lines))
    }
}
