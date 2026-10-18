package io.tima.domain.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Правка своего сообщения (ADR-0045): правила продукта до сети. */
class EditMessageTest {

    private class Queued(val chatId: String, val body: ByteArray, val level: Int, val editOf: Long)

    private val queued = mutableListOf<Queued>()
    private val queue = EditQueue { _, chatId, body, level, editOf -> queued += Queued(chatId, body, level, editOf); true }

    /** Кодек-подделка: правка — набранный текст, если он другой; «#» в начале — другое строение. */
    private val codec = object : MessageBodyCodec {
        override fun encodeText(text: String) = text.encodeToByteArray()
        override fun decodeText(body: ByteArray) = body.decodeToString()
        override fun encodeEdit(body: ByteArray, edits: List<ByteArray>, typed: String, marks: Boolean): EditBody {
            val current = (edits.lastOrNull() ?: body).decodeToString()
            return when {
                typed.startsWith("#") -> EditBody.Structure
                typed == current -> EditBody.Same
                else -> EditBody.Changes(typed.encodeToByteArray())
            }
        }
        override fun editSource(body: ByteArray, edits: List<ByteArray>) = (edits.lastOrNull() ?: body).decodeToString()
    }

    private fun editor(original: EditOriginal?) = EditMessage(queue, { _, _ -> original }, codec, { "k-1" })

    private fun mine(edits: Int = 0, level: Int = -1) =
        EditOriginal("было".encodeToByteArray(), List(edits) { "правка $it".encodeToByteArray() }, level, own = true)

    @Test
    fun правка_своего_уходит_с_номером_и_кругом_исходного() {
        val r = editor(mine(level = 2)).edit("chat", 42, "стало")
        assertIs<EditResult.Queued>(r)
        val q = queued.single()
        assertEquals(42L, q.editOf)
        assertEquals(2, q.level, "круг правки — круг исходного")
        assertEquals("стало", q.body.decodeToString())
    }

    @Test
    fun отказы_до_сети() {
        assertIs<EditResult.NotSent>(editor(mine()).edit("chat", 0, "x"))
        assertIs<EditResult.Missing>(editor(null).edit("chat", 1, "x"))
        assertIs<EditResult.NotMine>(editor(mine().let { EditOriginal(it.body, it.edits, it.level, own = false) }).edit("chat", 1, "x"))
        assertIs<EditResult.Limit>(editor(mine(edits = 3)).edit("chat", 1, "x"))
        assertIs<EditResult.Empty>(editor(mine()).edit("chat", 1, "  "))
        assertIs<EditResult.Same>(editor(mine()).edit("chat", 1, "было"))
        assertIs<EditResult.Structure>(editor(mine()).edit("chat", 1, "# заголовок"))
        assertEquals(0, queued.size, "ни один отказ не дошёл до очереди")
    }

    @Test
    fun строка_ввода_получает_видимый_текст_только_своего_и_до_трёх() {
        assertEquals("правка 1", editor(mine(edits = 2)).source("chat", 1))
        assertEquals(null, editor(mine(edits = 3)).source("chat", 1))
        assertEquals(null, editor(EditOriginal(ByteArray(1), emptyList(), -1, own = false)).source("chat", 1))
    }
}
