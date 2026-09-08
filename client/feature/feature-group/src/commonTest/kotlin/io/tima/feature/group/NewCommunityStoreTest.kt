package io.tima.feature.group

import io.tima.domain.chat.ChannelStep
import io.tima.domain.chat.Channels
import io.tima.domain.chat.Communities
import io.tima.domain.chat.CommunityItem
import io.tima.domain.chat.CommunityKinds
import io.tima.domain.chat.CommunityPage
import io.tima.domain.chat.CommunityStep
import io.tima.domain.chat.CreateChannel
import io.tima.domain.chat.CreateCommunity
import io.tima.domain.chat.ChatBook
import io.tima.domain.chat.ChatKind
import io.tima.domain.chat.CreateGroupChat
import io.tima.domain.chat.GroupCreateStep
import io.tima.domain.chat.GroupKind
import io.tima.domain.chat.GroupRegistry
import io.tima.domain.chat.GroupsStep
import io.tima.domain.chat.MemberStep
import io.tima.domain.chat.MembersStep
import io.tima.domain.chat.UserDirectory
import io.tima.domain.chat.UserLookup
import io.tima.domain.chat.LinkStep
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Мастер создания: разделы «Канал» и «Сообщество» (ПЛАН-СООБЩЕСТВ С5).
 *
 * Проверяется главное обещание раздела «Сообщество»: оно **связывает готовое**, а не
 * создаёт новое. Список на шаге «что вносим» — это уже существующие свои группы и каналы,
 * и внесение идёт по одному, чтобы отказ по одному элементу не отменял остальные.
 */
class NewCommunityStoreTest {

    private class FakeChannels(var answer: ChannelStep = ChannelStep.Created("ch-1")) : Channels {
        var created = 0
        var inCatalogue: Boolean? = null
        var comments: Boolean? = null

        override suspend fun create(
            title: String,
            description: String,
            inCatalogue: Boolean,
            comments: Boolean,
        ): ChannelStep {
            created++
            this.inCatalogue = inCatalogue
            this.comments = comments
            return answer
        }
    }

    private class FakeCommunities(
        private val items: List<CommunityItem> = emptyList(),
        private val busy: Set<String> = emptySet(),
    ) : Communities {
        var describedWith: String? = null
        val linked = mutableListOf<String>()

        override suspend fun create(title: String) = CommunityStep.Created("c-1")

        override suspend fun describe(communityId: String, text: String): Boolean {
            describedWith = text
            return true
        }

        override suspend fun link(communityId: String, kind: String, itemId: String): LinkStep {
            linked += itemId
            return if (itemId in busy) LinkStep.Busy else LinkStep.Linked
        }

        override suspend fun unlink(communityId: String, kind: String, itemId: String) = LinkStep.Linked
        override suspend fun page(communityId: String): CommunityPage? = null
        override suspend fun mine(): List<CommunityPage> = emptyList()
        override suspend fun linkable(): List<CommunityItem> = items
        override suspend fun subscribe(communityId: String, on: Boolean) = true
    }

    /**
     * Создание группы, которым здесь не пользуются: у мастера три раздела, и проверяются
     * два других. Подделка молчаливая, а не падающая: падение здесь означало бы, что
     * разделы связаны между собой, а они не связаны.
     */
    private val groupsUnused = CreateGroupChat(
        object : GroupRegistry {
            override suspend fun create(title: String, kind: GroupKind, description: String) =
                GroupCreateStep.Created("gggggggg-0000-0000-0000-000000000009")

            override suspend fun mine() = GroupsStep.Groups(emptyList())
            override suspend fun members(groupId: String) = MembersStep.Members(emptyList())
            override suspend fun addMember(groupId: String, userId: String) = MemberStep.Done
            override suspend fun removeMember(groupId: String, userId: String) = MemberStep.Done
        },
        object : UserDirectory {
            override suspend fun byPhone(phone: String) = UserLookup.NotFound
        },
        object : ChatBook {
            override fun remember(chatId: String, kind: ChatKind, title: String?, peerId: String?) = Unit
        },
    )

    @Test
    fun звуковой_чат_остаётся_серым_даже_когда_остальные_готовы() = runTest {
        val store = NewGroupStore(
            creation = groupsUnused,
            scope = this,
            channels = CreateChannel(FakeChannels()),
            communities = CreateCommunity(FakeCommunities()),
            linkable = { emptyList() },
        )
        assertTrue(store.ready(Section.Group))
        assertTrue(store.ready(Section.Channel))
        assertTrue(store.ready(Section.Community))
        // Не «не дошли руки», а решение: у звукового чата нет ни сервера, ни ответа о
        // хранении, и до реализации сообщество о нём ничего не знает.
        assertFalse(store.ready(Section.VoiceRoom))
    }

    @Test
    fun у_канала_свои_шаги_а_не_группины() = runTest {
        val channels = FakeChannels()
        val store = NewGroupStore(
            creation = groupsUnused,
            scope = this,
            channels = CreateChannel(channels),
        )
        store.choseSection(Section.Channel)
        store.forward()
        assertEquals(Step.Catalogue, store.state.value.step, "у канала спрашивают каталог, а не вид группы")
        store.choseCatalogue(false)
        store.forward()
        assertEquals(Step.Comments, store.state.value.step)
        store.choseComments(false)
        store.forward()
        assertEquals(Step.Naming, store.state.value.step)

        store.changedTitle("Ядро")
        store.create()
        runCurrent()

        assertEquals(1, channels.created)
        assertEquals(false, channels.inCatalogue, "«по подписке» значит «не в каталоге»")
        assertEquals(false, channels.comments)
        assertEquals("ch-1", store.state.value.created)
    }

    @Test
    fun сообщество_связывает_готовое_а_не_создаёт_новое() = runTest {
        val group = CommunityItem(CommunityKinds.GROUP, "g-1", "Своя группа")
        val channel = CommunityItem(CommunityKinds.CHANNEL, "ch-1", "Свой канал")
        val communities = FakeCommunities(items = listOf(group, channel))
        val store = NewGroupStore(
            creation = groupsUnused,
            scope = this,
            communities = CreateCommunity(communities),
            linkable = { communities.linkable() },
        )

        store.choseSection(Section.Community)
        runCurrent()
        assertEquals(2, store.state.value.linkable.size, "список — из уже существующего")

        store.forward()
        assertEquals(Step.Naming, store.state.value.step, "у сообщества вида нет вовсе")
        store.changedTitle("Ядро")
        store.changedDescription("мы делаем мессенджер")
        store.forward()
        assertEquals(Step.Bringing, store.state.value.step)

        store.choseItem(group)
        store.choseItem(channel)
        store.choseItem(channel) // второе нажатие снимает отметку
        store.create()
        runCurrent()

        assertEquals(listOf("g-1"), communities.linked, "внесено ровно отмеченное")
        assertEquals("мы делаем мессенджер", communities.describedWith, "описание — сообщение уровня 0")
        assertEquals("c-1", store.state.value.created)
    }

    @Test
    fun занятый_элемент_назван_а_не_проглочен() = runTest {
        val group = CommunityItem(CommunityKinds.GROUP, "g-1", "Уже в другом")
        val free = CommunityItem(CommunityKinds.CHANNEL, "ch-1", "Свободный канал")
        val communities = FakeCommunities(items = listOf(group, free), busy = setOf("g-1"))
        val store = NewGroupStore(
            creation = groupsUnused,
            scope = this,
            communities = CreateCommunity(communities),
            linkable = { communities.linkable() },
        )
        store.choseSection(Section.Community)
        runCurrent()
        store.forward()
        store.changedTitle("Ядро")
        store.forward()
        store.choseItem(group)
        store.choseItem(free)
        store.create()
        runCurrent()

        // Отказ по одному элементу не отменяет остальные, и о нём говорят поимённо:
        // молчание означало бы, что человек считает связанным то, чего в сообществе нет.
        assertEquals(listOf("Уже в другом"), store.state.value.notLinked)
        assertEquals(listOf("g-1", "ch-1"), communities.linked)
    }
}
