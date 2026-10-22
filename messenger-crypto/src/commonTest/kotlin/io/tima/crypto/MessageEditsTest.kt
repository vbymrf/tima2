package io.tima.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Правка сообщения (ADR-0045): тело правки, наложение, разница, обратный ход знаков. */
class MessageEditsTest {

    @Test
    fun тело_правки_проходит_круг() {
        val changes = listOf(
            NodeChange(0, "новый заголовок"),
            NodeChange(2, "жирно тут", listOf(MarkupEntity(type = EntityType.BOLD, nodeId = 3, start = 0, length = 5))),
        )
        assertEquals(changes, MessageEdits.decode(MessageEdits.encode(changes)).getOrThrow())
    }

    @Test
    fun правка_меняет_только_свои_узлы_и_их_оформление() {
        val original = QuickMarkdown.parse("# Поход\nСбор **в 9:00**\n- вода\n- зонт")
        val edited = QuickMarkdown.parse("# Поход\nСбор *в 10:00*\n- вода\n- зонт")
        val changes = MessageEdits.diff(original, edited)!!
        assertEquals(1, changes.size, "изменился один узел — абзац")
        assertEquals(1, changes.single().index)
        val applied = MessageEdits.apply(original, listOf(changes))
        assertEquals(edited.nodes, applied.nodes)
        assertEquals(edited.markup, applied.markup)
    }

    @Test
    fun замена_фото_проходит_круг_и_ложится_на_своё_место() {
        fun ref(id: String, w: Int) = io.tima.crypto.proto.MediaRef(media_id = id, mime = "image/jpeg", width = w, height = 100)
        val original = MessageContent(nodes = listOf(""), media = listOf(ref("a", 10), ref("b", 20)))
        val body = MessageEdits.encodeMedia(listOf(MediaChange(1, ref("b2", 30))))
        val decoded = MessageSerializer.decodeBody(body).getOrThrow()
        assertTrue(MessageEdits.from(decoded).isEmpty(), "узлов замена фото не трогает")
        val second = MessageSerializer.decodeBody(MessageEdits.encodeMedia(listOf(MediaChange(1, ref("b3", 40)), MediaChange(7, ref("x", 1))))).getOrThrow()
        val applied = MessageEdits.applyMedia(original, listOf(MessageEdits.mediaFrom(decoded), MessageEdits.mediaFrom(second)))
        assertEquals(listOf("a", "b3"), applied.media.map { it.media_id }, "поздняя замена перекрывает раннюю, чужая позиция пропущена")
        assertEquals(40, applied.media[1].width)
        assertEquals(original.nodes, applied.nodes)
    }

    @Test
    fun поздняя_правка_того_же_узла_перекрывает_раннюю() {
        val original = MessageContent.text("было")
        val applied = MessageEdits.apply(original, listOf(listOf(NodeChange(0, "стало")), listOf(NodeChange(0, "стало окончательно"))))
        assertEquals("стало окончательно", applied.plainText())
    }

    @Test
    fun узел_вне_исходного_пропускается() {
        val original = MessageContent.text("один узел")
        assertEquals(original, MessageEdits.apply(original, listOf(listOf(NodeChange(5, "чужое")))))
    }

    @Test
    fun оформление_за_концом_текста_отбрасывается() {
        val original = QuickMarkdown.parse("**длинный текст**")
        val applied = MessageEdits.apply(
            original,
            listOf(listOf(NodeChange(0, "кор", listOf(MarkupEntity(type = EntityType.BOLD, nodeId = 1, start = 0, length = 14))))),
        )
        assertEquals("кор", applied.nodes.single())
        assertTrue(applied.markup!!.entities.isEmpty())
    }

    @Test
    fun другое_строение_правкой_не_передать() {
        val original = QuickMarkdown.parse("# Заголовок\nабзац")
        assertNull(MessageEdits.diff(original, QuickMarkdown.parse("## Заголовок\nабзац")), "уровень заголовка — строение")
        assertNull(MessageEdits.diff(original, QuickMarkdown.parse("# Заголовок\nабзац\n- пункт")), "новый блок")
        assertNull(MessageEdits.diff(MessageContent.text("просто"), QuickMarkdown.parse("# стало заголовком")))
    }

    @Test
    fun снятое_оформление_единственного_абзаца_это_правка_узла() {
        val original = QuickMarkdown.parse("было **жирно**")
        val changes = MessageEdits.diff(original, QuickMarkdown.parse("было просто"))!!
        assertEquals(listOf(NodeChange(0, "было просто")), changes)
    }

    @Test
    fun без_изменений_пусто() {
        val c = QuickMarkdown.parse("# A\nb")
        assertEquals(emptyList(), MessageEdits.diff(c, QuickMarkdown.parse("# A\nb")))
        assertEquals(emptyList(), MessageEdits.diff(MessageContent.text("x"), MessageContent.text("x")))
    }

    @Test
    fun набранное_восстанавливается_из_содержимого() {
        val typed = listOf(
            "# Поход\nСбор **в 9:00** и *зонт*\n- вода\n  - фляга\n- зонт\n--> цитата\n---\n```\nкод **как есть**\n```",
            "просто текст без знаков",
            "## Итог ++важно++ ==маркер== ~~нет~~ `код` #тег @ник",
        )
        for (t in typed) {
            val content = QuickMarkdown.parse(t)
            val source = assertNotNull(QuickMarkdown.source(content), t)
            val back = QuickMarkdown.parse(source)
            assertEquals(content.nodes, back.nodes, t)
            assertEquals(content.markup, back.markup, t)
        }
    }

    @Test
    fun знак_в_тексте_экранируется_только_когда_иначе_нельзя() {
        // В узле буквально «**слово**» — без экранирования разбор сделал бы его жирным.
        val content = QuickMarkdown.parse("# Заг\nбуквально \\*\\*слово\\*\\*")
        assertEquals("буквально **слово**", content.nodes[1])
        val source = QuickMarkdown.source(content)!!
        assertEquals(content.markup, QuickMarkdown.parse(source).markup)
        // А «C++» в отдельном сообщении знаков пары не образует — косых не нужно.
        val cpp = QuickMarkdown.parse("# C++\nи a == b")
        assertEquals("# C++\nи a == b", QuickMarkdown.source(cpp))
    }

    @Test
    fun таблицу_знаками_не_правят() {
        val table = QuickMarkdown.parse("| a | b |\n|---|---|\n| 1 | 2 |")
        assertNull(QuickMarkdown.source(table))
    }
}
