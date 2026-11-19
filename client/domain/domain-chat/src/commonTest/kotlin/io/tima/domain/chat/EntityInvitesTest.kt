package io.tima.domain.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Свои ссылки приложения (ПЛАН-(СЦ) СЦ2): карточка приглашения и ссылка на сущность. */
class EntityInvitesTest {

    @Test
    fun ссылка_приглашения_туда_и_обратно() {
        val url = EntityInvites.inviteLink("i-1")
        assertEquals("tima://invite/i-1", url)
        assertEquals("i-1", EntityInvites.inviteIdOf(url))
        assertNull(EntityInvites.inviteIdOf("https://tima.example/invite/i-1"), "чужая ссылка — не приглашение")
        assertNull(EntityInvites.inviteIdOf("tima://invite/"), "без номера — не приглашение")
        assertNull(EntityInvites.inviteIdOf("tima://invite/a/b"), "лишний путь — не приглашение")
    }

    @Test
    fun ссылка_на_сущность_туда_и_обратно() {
        val url = EntityInvites.entityLink(EntityKind.Community, "c-7")
        assertEquals("tima://entity/community/c-7", url)
        assertEquals(EntityKind.Community to "c-7", EntityInvites.entityOf(url))
        assertNull(EntityInvites.entityOf("tima://entity/planet/x"), "неизвестный вид")
        assertNull(EntityInvites.entityOf("tima://entity/group/"), "без номера")
    }

    @Test
    fun тип_группы_по_виду_и_признаку() {
        assertEquals(EntityType(EntityKind.Group, EntityReach.Personal, true), EntityType.group(GroupKind.Personal, true))
        assertEquals(EntityType(EntityKind.Group, EntityReach.Public, false), EntityType.group(GroupKind.Public, false))
        assertEquals(EntityReach.Personal, EntityType.group(null, false).reach, "вид неизвестен — как личная: строже")
    }
}
