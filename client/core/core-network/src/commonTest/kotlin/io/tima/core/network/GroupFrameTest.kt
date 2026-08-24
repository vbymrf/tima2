package io.tima.core.network

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Кадр сообщения группы: хранение и разбор.
 *
 * Главная проверка — различение двух видов байт в одном столбце. Личный конверт
 * (protobuf) и групповой кадр (JSON) лежат вместе, и спутать их значит однажды прочесть
 * личное сообщение как групповое.
 */
class GroupFrameTest {

    private val protocol = EventStreamProtocol()

    private val кадр = """{"event":"message.group","event_id":5,"group_id":"g-1",""" +
        """"message_id":77,"sender_id":"u-2","sender_device":"dev-2","kind":1,""" +
        """"gk_version":3,"payload":"AQID","signature":"BAUG","created_at_unix_ms":1750000000000,""" +
        """"thread_root":0,"reply_to":0}"""

    @Test
    fun сообщение_группы_кладётся_тем_же_путём_что_личное() {
        // Хранилище принимает непрозрачные байты, и канал не обязан знать, что внутри:
        // «сначала записать, потом разбирать» одинаково для обоих видов.
        val решение = assertIs<EventStreamProtocol.Decision.Deliver>(protocol.decide(кадр))
        assertEquals("g-1", решение.event.chatId)
        assertEquals(77L, решение.event.messageId)
        assertTrue(GroupFrame.isGroupFrame(решение.event.envelope))
    }

    @Test
    fun сохранённый_кадр_разбирается_обратно() {
        val решение = assertIs<EventStreamProtocol.Decision.Deliver>(protocol.decide(кадр))
        val разобранный = assertNotNull(GroupFrame.parse(решение.event.envelope))

        assertEquals("u-2", разобранный.senderId)
        assertEquals("dev-2", разобранный.senderDevice)
        assertEquals(3, разобранный.gkVersion)
        assertEquals(1_750_000_000_000, разобранный.createdAtUnixMs)
        assertContentEquals(byteArrayOf(1, 2, 3), разобранный.payload)
        assertContentEquals(byteArrayOf(4, 5, 6), разобранный.signature)
    }

    @Test
    fun protobuf_конверт_не_читается_как_групповой() {
        // Ноль в начале protobuf невозможен: первый байт — тег поля, а поля с номером
        // ноль не бывает. На этом и держится различение.
        val конверт = byteArrayOf(0x0A, 0x05, 0x7B, 0x22, 0x67, 0x22)
        assertFalse(GroupFrame.isGroupFrame(конверт))
        assertNull(GroupFrame.parse(конверт))
    }

    @Test
    fun кадр_без_обязательных_полей_пропускается_но_подтверждается() {
        // Курсор обязан двигаться: застрянь он на испорченном кадре — всё, что после,
        // не приедет никогда.
        val решение = protocol.decide("""{"event":"message.group","event_id":6,"group_id":"g-1"}""")
        assertEquals(6L, assertIs<EventStreamProtocol.Decision.Skip>(решение).eventId)
    }

    @Test
    fun хранится_исходный_json_а_не_пересобранный() {
        // Подпись считается по тем значениям, что пришли. Пересборка из полей могла бы
        // незаметно нормализовать их — и подпись перестала бы сходиться.
        val решение = assertIs<EventStreamProtocol.Decision.Deliver>(protocol.decide(кадр))
        val текст = решение.event.envelope.decodeToString(1, решение.event.envelope.size)
        assertTrue("\"sender_device\":\"dev-2\"" in текст)
    }
}
