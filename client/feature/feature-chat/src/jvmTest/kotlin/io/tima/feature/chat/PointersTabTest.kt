package io.tima.feature.chat

import io.tima.domain.chat.FoundMessage
import io.tima.domain.chat.MessageDisplay
import io.tima.domain.chat.Pointer
import io.tima.testui.capture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Вкладка «Указатели» (ПЛАН-(УК) УК3–УК5): сущности, разделы, сообщения, журнал. */
class PointersTabTest {

    private val a = Pointer("group:g-1", "g-1", 1, atMs = 10, sectionId = "s-1")
    private val b = Pointer("group:g-1", "g-1", 2, atMs = 20)
    private val c = Pointer("personal:p-1", "p-1", 3, atMs = 30)
    private val entities = listOf(
        PointerEntity("group:g-1", "Соседи", "группа закрытая", null, listOf(a, b)),
        PointerEntity("personal:p-1", "Аня", "чат", null, listOf(c)),
    )

    @Test
    fun раздел_оставляет_сущности_со_своими_указателями() {
        assertEquals(2, entities.inSection("").size, "«Все» — все")
        val s1 = entities.inSection("s-1")
        assertEquals(listOf("group:g-1"), s1.map { it.level })
        assertEquals(listOf(a), s1.single().pointers, "внутри — только указатели раздела")
        assertEquals(listOf("group:g-1", "personal:p-1"), entities.inSection(COMMON_SECTION).map { it.level }, "пустой раздел указателя — «Общий»")
    }

    @Test
    fun указатель_строкой_ленты() {
        assertEquals("загрузка", c.asLine(null, "загрузка", "нет").text)
        assertEquals(MessageDisplay.SYSTEM, c.asLine(FoundMessage.Unavailable("missing"), "загрузка", "нет").display)
        assertEquals("нет", c.asLine(FoundMessage.Unavailable("missing"), "загрузка", "нет").text)
    }

    @Test
    fun вкладка_рисуется() {
        fun tab(list: List<PointerEntity>, opened: String?) = capture("указатели-${list.size}-${opened.orEmpty()}", 360, 640, dark = false) {
            PointersTab(
                entities = list, sections = emptyList(), common = null, icons = false, section = "", onSection = {},
                opened = opened, onOpenEntity = {}, lineOf = { it.asLine(null, "загрузка", "нет") }, authorOf = { null },
                onJump = {}, onRemove = {}, onRemoveEntity = {}, onMove = { _, _ -> }, onView = {}, onRepeat = {},
            )
        }
        val empty = tab(emptyList(), null)
        val list = tab(entities, null)
        val inside = tab(entities, "group:g-1")
        assertTrue(empty.difference(list) > 0.0)
        assertTrue(list.difference(inside) > 0.0)
        val ledger = capture("журнал-указателей", 360, 640, dark = false) {
            PointersLedgerPage(entities, emptyList(), null, { it.asLine(null, "загрузка", "нет") }, { _, _ -> }, {}, {})
        }
        assertTrue(ledger.difference(list) > 0.0)
    }
}
