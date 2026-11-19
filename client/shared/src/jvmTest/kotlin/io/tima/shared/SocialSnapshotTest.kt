package io.tima.shared

import io.tima.domain.chat.ChannelInfo
import io.tima.domain.chat.CommunityItem
import io.tima.domain.chat.CommunityPage
import io.tima.domain.chat.GroupCard
import io.tima.domain.chat.GroupInfo
import io.tima.domain.chat.GroupKind
import io.tima.domain.chat.GroupRole
import io.tima.feature.group.SocialState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Снимок «Каталога» на устройстве (заказчик 2026-11-19): туда и обратно без потерь, чужое — не снимок. */
class SocialSnapshotTest {
    @Test
    fun туда_и_обратно() {
        val s = SocialState(
            mine = listOf(GroupInfo("g-1", "Ядро", GroupRole.Admin, "u-1", GroupKind.Public, null, "c-1", secret = true, deleteAtMs = 77)),
            cards = listOf(GroupCard("g-2", "Соседи", "Дом 12", GroupKind.Personal)),
            communities = listOf(CommunityPage("c-1", "Клуб", owner = true, admin = false, subscribed = true, listOf("о нас"), listOf(CommunityItem("group", "g-1", "Ядро")))),
            channels = listOf(ChannelInfo("ch-1", "Лента", owner = false, moderator = true, isPublic = true, secret = false, communityId = null)),
        )
        val back = SocialSnapshot.decode(SocialSnapshot.encode(s))!!
        val g = back.mine.single()
        assertEquals(listOf("g-1", "Ядро", "Admin", "u-1", "Public", "c-1"), listOf(g.groupId, g.title, g.myRole.name, g.ownerId, g.kind?.name, g.communityId))
        assertTrue(g.secret)
        assertEquals(77L, g.deleteAtMs)
        assertEquals(s.cards.map { it.groupId to it.kind }, back.cards.map { it.groupId to it.kind })
        assertEquals(s.communities, back.communities)
        assertEquals(s.channels, back.channels)
        assertTrue(back.loaded)
    }

    @Test
    fun чужое_и_битое_не_снимок() {
        assertNull(SocialSnapshot.decode(null))
        assertNull(SocialSnapshot.decode("не json"))
        assertNull(SocialSnapshot.decode("""{"v":99,"mine":[]}"""))
    }
}
