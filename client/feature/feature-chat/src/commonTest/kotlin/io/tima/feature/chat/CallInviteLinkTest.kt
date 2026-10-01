package io.tima.feature.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Приглашение в групповой звонок — ссылкой в тексте (ПЛАН-ГРУППОВЫХ-ЗВОНКОВ, решение 3а). */
class CallInviteLinkTest {

    @Test
    fun ссылка_туда_и_обратно() {
        val g = "3f2c9a1e-7b4d-4c1a-9e2f-0a1b2c3d4e5f"
        assertEquals(g, CallInviteLink.groupOf(CallInviteLink.of(g)))
        assertEquals(g, CallInviteLink.groupOf("  " + CallInviteLink.of(g) + "\n"))
    }

    @Test
    fun обычный_текст_не_приглашение() {
        assertNull(CallInviteLink.groupOf("привет"))
        assertNull(CallInviteLink.groupOf("tima://call/"))
        assertNull(CallInviteLink.groupOf("tima://call/<script>"))
        assertNull(CallInviteLink.groupOf("смотри tima://call/3f2c9a1e-7b4d-4c1a-9e2f-0a1b2c3d4e5f"))
        assertNull(CallInviteLink.groupOf(null))
    }
}
