package io.tima.core.database

import io.tima.domain.chat.MessagePicks
import io.tima.domain.chat.Pick
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Указатели на устройстве (ПЛАН-(УК) УК1–УК2): личная подборка — у каждой переписки своя, редактор берёт
 * все личные; раздел у указателя; убранный раздел возвращает указатели в «Общий»; сообщение-цель с
 * сервера лежит закрытым ключом устройства.
 */
class SqlPicksTest {

    private val db = testDatabase()
    private var now = 0L
    private val picks = MessagePicks(SqlPicks(db)) { ++now }
    private val sections = SqlPointerSections(db)

    @Test
    fun личные_по_переписке_редактор_берёт_все() {
        picks.add(MessagePicks.levelOf("p-1", group = false), "p-1", 5)
        picks.add(MessagePicks.levelOf("p-2", group = false), "p-2", 7)
        picks.add(MessagePicks.levelOf("g-1", group = true), "g-1", 9)
        assertEquals(listOf(Pick("p-2", 7), Pick("p-1", 5)), picks.list(MessagePicks.levelOf("p-1", group = false)))
        assertEquals(listOf(Pick("g-1", 9)), picks.list("group:g-1"))
    }

    @Test
    fun раздел_уборка_и_сущность() = runTest {
        picks.add("group:g-1", "g-1", 1)
        picks.add("group:g-1", "g-1", 2)
        picks.add("personal:p-1", "p-1", 3)
        val id = sections.add("Важное")
        val all = picks.pointers().first()
        picks.moveTo(all.filter { it.serverId == 1L }, id)
        assertEquals(id, picks.pointers().first().first { it.serverId == 1L }.sectionId)
        sections.remove(id)
        assertEquals("", picks.pointers().first().first { it.serverId == 1L }.sectionId, "убранный раздел — указатели в «Общий»")
        picks.remove(picks.pointers().first().filter { it.serverId == 2L })
        assertEquals(listOf(3L, 1L), picks.pointers().first().map { it.serverId })
        picks.removeLevel("group:g-1")
        assertEquals(listOf("personal:p-1"), picks.pointers().first().map { it.level })
    }

    @Test
    fun сообщение_цель_закрыто_ключом_устройства() {
        val cache = SqlRefCache(db, testCipher())
        val body = "кадр группы".encodeToByteArray()
        cache.put("g-1", 4, "frame", body)
        val stored = db.picksQueries.refCached("g-1", 4).executeAsOne().body_enc
        assertFalse(stored.contentEquals(body), "в базе — не открытый текст")
        val back = cache.get("g-1", 4)!!
        assertEquals("frame", back.first)
        assertContentEquals(body, back.second)
        cache.drop("g-1", 4)
        assertNull(cache.get("g-1", 4))
    }
}
