package io.tima.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessageContentTest {

    @Test
    fun `обычное сообщение - один узел и никакой разметки`() {
        val c = MessageContent.text("привет, как дела")
        assertEquals(listOf("привет, как дела"), c.nodes)
        assertNull(c.markup)
        assertTrue(!c.hasMarkup)

        val body = MessageContentCodec.toBody(c)
        // Разметки нет — значит и строки разметки нет. Пустое сообщение не должно
        // стоить дороже, чем стоило до перехода на узлы: это 99% трафика.
        assertEquals("", body.markup)
        assertEquals(1, body.nodes.size)
    }

    @Test
    fun `плоский текст кладётся для старых клиентов`() {
        val c = MessageContent(nodes = listOf("Заголовок", " и текст"))
        val body = MessageContentCodec.toBody(c)
        // Клиент, не знающий про узлы, прочитает text и покажет сообщение без
        // оформления. Это хуже, чем с оформлением, и несравнимо лучше пустоты.
        assertEquals("Заголовок и текст", body.text)
    }

    @Test
    fun `сообщение от старого клиента читается как один узел`() {
        val old = io.tima.crypto.proto.MessageBody(text = "старый формат")
        val c = MessageContentCodec.fromBody(old)
        assertEquals(listOf("старый формат"), c.nodes)
        assertNull(c.markup)
    }

    @Test
    fun `круговой путь сохраняет узлы и разметку`() {
        val markup = Markup(
            n = listOf(7, 3),
            blocks = listOf(
                MarkupBlock(type = "heading", nodeIds = listOf(7), level = 1),
                MarkupBlock(type = "paragraph", nodeIds = listOf(3)),
            ),
            entities = listOf(MarkupEntity(type = "link", nodeId = 3, start = 0, length = 4, url = "https://tima")),
        )
        val c = MessageContent(nodes = listOf("Заголовок", "текст со ссылкой"), markup = markup)

        val back = MessageContentCodec.fromBody(MessageContentCodec.toBody(c))
        assertEquals(c.nodes, back.nodes)
        assertEquals(markup.n, back.markup?.n)
        assertEquals("heading", back.markup?.blocks?.first()?.type)
        assertEquals("https://tima", back.markup?.entities?.first()?.url)
    }

    @Test
    fun `идентификаторы узлов переживают вставку в середину`() {
        val markup = Markup(
            n = listOf(7, 3),
            blocks = listOf(MarkupBlock(type = "heading", nodeIds = listOf(7))),
        )
        // Вставляем узел В НАЧАЛО: позиции всех последующих сдвинулись.
        val afterInsert = markup.copy(n = listOf(Markup.nextId(markup)) + markup.n)

        // Заголовком по-прежнему остаётся ТОТ ЖЕ узел. При «идентификатор = позиция»
        // заголовком молча стал бы вставленный.
        assertEquals(1, afterInsert.indexOf(7))
        assertEquals(listOf(7), afterInsert.blocks.first().nodeIds)
    }

    @Test
    fun `испорченная разметка не теряет сообщение`() {
        val body = io.tima.crypto.proto.MessageBody(
            text = "текст",
            nodes = listOf("текст"),
            markup = "{это не json",
        )
        val c = MessageContentCodec.fromBody(body)
        // Оформление потеряно, текст на месте — правильный размен: показать без
        // украшений лучше, чем не показать вовсе.
        assertEquals(listOf("текст"), c.nodes)
        assertNull(c.markup)
    }

    @Test
    fun `незнакомые поля разметки не ломают разбор`() {
        val raw = """{"version":1,"n":[1],"чего-то_новое":{"a":1},"blocks":[]}"""
        val m = Markup.decode(raw)
        assertEquals(listOf(1), m?.n)
    }

    @Test
    fun `следующий идентификатор не повторяет выданные`() {
        val m = Markup(n = listOf(4, 9, 2))
        assertEquals(10, Markup.nextId(m))
        assertEquals(1, Markup.nextId(Markup.EMPTY))
    }
}
