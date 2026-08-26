package io.tima.domain.chat

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Группы: создание и сверка с сервером.
 *
 * Проверяются три решения продукта:
 *
 * 1. **`group_id` выдаёт сервер**, поэтому группа сначала появляется там, а потом у нас.
 *    Обратный порядок оставил бы местную строку без группы — переписку, которой нигде нет.
 * 2. **Незарегистрированный номер не отменяет создание группы.** Из десяти приглашённых один
 *    может не пользоваться TIMA, и терять из-за него всю группу человек не согласится.
 * 3. **Про группу, куда позвали, надо узнавать сверкой.** Добавляет кто-то другой, местного
 *    следа это не оставляет; без сверки человек узнал бы о группе с первым сообщением, то
 *    есть последним.
 */
class GroupChatsTest {

    private val network = FakeGroups()
    private val directory = FakeDirectory()
    private val entries = EntryMemorable()

    private fun creation() = CreateGroupChat(network, directory, entries)

    @Test
    fun группа_сначала_на_сервере_потом_у_нас() = runTest {
        network.onCreation = {
            assertTrue(entries.all.isEmpty(), "местная строка не должна появляться до сервера")
            GroupCreateStep.Created("g-1")
        }

        val step = creation().create("Поход")

        assertIs<CreateGroupStep.Created>(step)
        assertEquals("g-1", step.groupId)
        assertEquals(1, entries.all.size)
        assertEquals(ChatKind.Group, entries.all.single().kind)
        assertEquals("Поход", entries.all.single().title)
    }

    /** Пустое название — до сети: без имени группу не найти в списке. */
    @Test
    fun пустое_название_до_сети_не_доходит() = runTest {
        val step = creation().create("   ")

        assertIs<CreateGroupStep.BadTitle>(step)
        assertEquals(0, network.creations, "сеть не тревожим: ответ известен на месте")
    }

    /**
     * Предел названия считается в **байтах**, как на сервере.
     *
     * Считай в знаках — и кириллическое название из 150 букв прошло бы проверку у нас и
     * получило `bad_title` от сервера.
     */
    @Test
    fun слишком_длинное_название_отсекается_по_байтам() = runTest {
        val step = creation().create("я".repeat(101)) // 202 байта в UTF-8

        assertIs<CreateGroupStep.BadTitle>(step)
        assertEquals(0, network.creations)
    }

    @Test
    fun приглашённые_добавляются_по_номерам() = runTest {
        directory.known = mapOf("+79990000001" to "u-1", "+79990000002" to "u-2")

        val step = creation().create("Поход", listOf("+79990000001", "+79990000002"))

        assertIs<CreateGroupStep.Created>(step)
        assertEquals(listOf("u-1", "u-2"), network.added)
        assertTrue(step.notInvited.isEmpty())
    }

    /** Номера, которого нет в TIMA, достаточно, чтобы сказать про него — но не чтобы всё отменить. */
    @Test
    fun незарегистрированный_номер_не_отменяет_группу() = runTest {
        directory.known = mapOf("+79990000001" to "u-1")

        val step = creation().create("Поход", listOf("+79990000001", "+79990000009"))

        assertIs<CreateGroupStep.Created>(step)
        assertEquals("g-1", step.groupId, "группа создана")
        assertEquals(listOf("u-1"), network.added)
        assertEquals(listOf("+79990000009"), step.notInvited, "про него надо сказать человеку")
    }

    /** Повторы в списке номеров не превращаются в повторные приглашения. */
    @Test
    fun повторяющиеся_номера_приглашаются_один_раз() = runTest {
        directory.known = mapOf("+79990000001" to "u-1")

        creation().create("Поход", listOf("+79990000001", " +79990000001 "))

        assertEquals(listOf("u-1"), network.added)
    }

    /** Отказ создания — и местной строки нет: переписки, о которой знаем только мы, не бывает. */
    @Test
    fun отказ_сервера_не_оставляет_местной_строки() = runTest {
        network.onCreation = { GroupCreateStep.Refused("forbidden") }

        val step = creation().create("Поход")

        assertIs<CreateGroupStep.Refused>(step)
        assertTrue(entries.all.isEmpty())
    }

    @Test
    fun сверка_запоминает_группы_куда_позвали() = runTest {
        network.onList = {
            GroupsStep.Groups(
                listOf(
                    GroupInfo("g-1", "Поход", GroupRole.Owner),
                    GroupInfo("g-2", "Работа", GroupRole.Member),
                ),
            )
        }

        val step = SyncGroupChats(network, entries).refresh()

        assertIs<SyncGroupsStep.Synced>(step)
        assertEquals(2, step.count)
        assertEquals(listOf("g-1", "g-2"), entries.all.map { it.chatId })
        assertTrue(entries.all.all { it.kind == ChatKind.Group })
    }

    /** Группа без названия на сервере всё равно получает имя: пустая строка в списке — поломка. */
    @Test
    fun группа_без_названия_получает_имя() = runTest {
        network.onList = { GroupsStep.Groups(listOf(GroupInfo("g-1", "  ", GroupRole.Member))) }

        SyncGroupChats(network, entries).refresh()

        assertEquals("Группа", entries.all.single().title)
    }

    /**
     * Незнакомая роль не становится участником.
     *
     * Сервер может оказаться новее клиента. Выдать неизвестной роли права участника — значит
     * выдать их по ошибке; проверка на самой границе разбора.
     */
    @Test
    fun незнакомая_роль_остаётся_незнакомой() {
        assertEquals(GroupRole.Unknown, GroupRole.from("archivist"))
        assertEquals(GroupRole.Owner, GroupRole.from("owner"))
        assertTrue(GroupRole.Admin.deliveryEdits)
        assertTrue(!GroupRole.Moderator.deliveryEdits, "модератор состав не правит")
        assertTrue(!GroupRole.Unknown.deliveryEdits, "неизвестной роли прав не даём")
    }

    // ── подделки ────────────────────────────────────────────────────────────

    private class FakeGroups : GroupRegistry {
        var creations = 0
        val added = mutableListOf<String>()
        var onCreation: suspend () -> GroupCreateStep = { GroupCreateStep.Created("g-1") }
        var onList: suspend () -> GroupsStep = { GroupsStep.Groups(emptyList()) }

        override suspend fun create(title: String): GroupCreateStep {
            creations++
            return onCreation()
        }

        override suspend fun mine(): GroupsStep = onList()

        override suspend fun members(groupId: String): MembersStep = MembersStep.Members(emptyList())

        override suspend fun addMember(groupId: String, userId: String): MemberStep {
            added += userId
            return MemberStep.Done
        }

        override suspend fun removeMember(groupId: String, userId: String): MemberStep = MemberStep.Done
    }

    private class FakeDirectory : UserDirectory {
        var known: Map<String, String> = emptyMap()
        override suspend fun byPhone(phone: String): UserLookup =
            known[phone.trim()]?.let { UserLookup.Found(it) } ?: UserLookup.NotFound
    }

    private class EntryMemorable : ChatBook {
        class Entry(val chatId: String, val kind: ChatKind, val title: String?, val peerId: String?)

        val all = mutableListOf<Entry>()

        override fun remember(chatId: String, kind: ChatKind, title: String?, peerId: String?) {
            all += Entry(chatId, kind, title, peerId)
        }
    }
}
