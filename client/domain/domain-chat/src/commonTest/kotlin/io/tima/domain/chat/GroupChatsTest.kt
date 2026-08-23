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

    private val сеть = ПоддельныеГруппы()
    private val справочник = ПоддельныйСправочник()
    private val записи = ПамятныеЗаписи()

    private fun создание() = CreateGroupChat(сеть, справочник, записи)

    @Test
    fun группа_сначала_на_сервере_потом_у_нас() = runTest {
        сеть.наСоздание = {
            assertTrue(записи.всё.isEmpty(), "местная строка не должна появляться до сервера")
            GroupCreateStep.Created("g-1")
        }

        val шаг = создание().создать("Поход")

        assertIs<CreateGroupStep.Created>(шаг)
        assertEquals("g-1", шаг.groupId)
        assertEquals(1, записи.всё.size)
        assertEquals(ChatKind.Group, записи.всё.single().kind)
        assertEquals("Поход", записи.всё.single().title)
    }

    /** Пустое название — до сети: без имени группу не найти в списке. */
    @Test
    fun пустое_название_до_сети_не_доходит() = runTest {
        val шаг = создание().создать("   ")

        assertIs<CreateGroupStep.BadTitle>(шаг)
        assertEquals(0, сеть.созданий, "сеть не тревожим: ответ известен на месте")
    }

    /**
     * Предел названия считается в **байтах**, как на сервере.
     *
     * Считай в знаках — и кириллическое название из 150 букв прошло бы проверку у нас и
     * получило `bad_title` от сервера.
     */
    @Test
    fun слишком_длинное_название_отсекается_по_байтам() = runTest {
        val шаг = создание().создать("я".repeat(101)) // 202 байта в UTF-8

        assertIs<CreateGroupStep.BadTitle>(шаг)
        assertEquals(0, сеть.созданий)
    }

    @Test
    fun приглашённые_добавляются_по_номерам() = runTest {
        справочник.известные = mapOf("+79990000001" to "u-1", "+79990000002" to "u-2")

        val шаг = создание().создать("Поход", listOf("+79990000001", "+79990000002"))

        assertIs<CreateGroupStep.Created>(шаг)
        assertEquals(listOf("u-1", "u-2"), сеть.добавленные)
        assertTrue(шаг.непозванные.isEmpty())
    }

    /** Номера, которого нет в TIMA, достаточно, чтобы сказать про него — но не чтобы всё отменить. */
    @Test
    fun незарегистрированный_номер_не_отменяет_группу() = runTest {
        справочник.известные = mapOf("+79990000001" to "u-1")

        val шаг = создание().создать("Поход", listOf("+79990000001", "+79990000009"))

        assertIs<CreateGroupStep.Created>(шаг)
        assertEquals("g-1", шаг.groupId, "группа создана")
        assertEquals(listOf("u-1"), сеть.добавленные)
        assertEquals(listOf("+79990000009"), шаг.непозванные, "про него надо сказать человеку")
    }

    /** Повторы в списке номеров не превращаются в повторные приглашения. */
    @Test
    fun повторяющиеся_номера_приглашаются_один_раз() = runTest {
        справочник.известные = mapOf("+79990000001" to "u-1")

        создание().создать("Поход", listOf("+79990000001", " +79990000001 "))

        assertEquals(listOf("u-1"), сеть.добавленные)
    }

    /** Отказ создания — и местной строки нет: переписки, о которой знаем только мы, не бывает. */
    @Test
    fun отказ_сервера_не_оставляет_местной_строки() = runTest {
        сеть.наСоздание = { GroupCreateStep.Refused("forbidden") }

        val шаг = создание().создать("Поход")

        assertIs<CreateGroupStep.Refused>(шаг)
        assertTrue(записи.всё.isEmpty())
    }

    @Test
    fun сверка_запоминает_группы_куда_позвали() = runTest {
        сеть.наСписок = {
            GroupsStep.Groups(
                listOf(
                    GroupInfo("g-1", "Поход", GroupRole.Владелец),
                    GroupInfo("g-2", "Работа", GroupRole.Участник),
                ),
            )
        }

        val шаг = SyncGroupChats(сеть, записи).обновить()

        assertIs<SyncGroupsStep.Synced>(шаг)
        assertEquals(2, шаг.count)
        assertEquals(listOf("g-1", "g-2"), записи.всё.map { it.chatId })
        assertTrue(записи.всё.all { it.kind == ChatKind.Group })
    }

    /** Группа без названия на сервере всё равно получает имя: пустая строка в списке — поломка. */
    @Test
    fun группа_без_названия_получает_имя() = runTest {
        сеть.наСписок = { GroupsStep.Groups(listOf(GroupInfo("g-1", "  ", GroupRole.Участник))) }

        SyncGroupChats(сеть, записи).обновить()

        assertEquals("Группа", записи.всё.single().title)
    }

    /**
     * Незнакомая роль не становится участником.
     *
     * Сервер может оказаться новее клиента. Выдать неизвестной роли права участника — значит
     * выдать их по ошибке; проверка на самой границе разбора.
     */
    @Test
    fun незнакомая_роль_остаётся_незнакомой() {
        assertEquals(GroupRole.Неизвестная, GroupRole.из("archivist"))
        assertEquals(GroupRole.Владелец, GroupRole.из("owner"))
        assertTrue(GroupRole.Админ.правитПоставом)
        assertTrue(!GroupRole.Модератор.правитПоставом, "модератор состав не правит")
        assertTrue(!GroupRole.Неизвестная.правитПоставом, "неизвестной роли прав не даём")
    }

    // ── подделки ────────────────────────────────────────────────────────────

    private class ПоддельныеГруппы : GroupRegistry {
        var созданий = 0
        val добавленные = mutableListOf<String>()
        var наСоздание: suspend () -> GroupCreateStep = { GroupCreateStep.Created("g-1") }
        var наСписок: suspend () -> GroupsStep = { GroupsStep.Groups(emptyList()) }

        override suspend fun create(title: String): GroupCreateStep {
            созданий++
            return наСоздание()
        }

        override suspend fun mine(): GroupsStep = наСписок()

        override suspend fun members(groupId: String): MembersStep = MembersStep.Members(emptyList())

        override suspend fun addMember(groupId: String, userId: String): MemberStep {
            добавленные += userId
            return MemberStep.Done
        }

        override suspend fun removeMember(groupId: String, userId: String): MemberStep = MemberStep.Done
    }

    private class ПоддельныйСправочник : UserDirectory {
        var известные: Map<String, String> = emptyMap()
        override suspend fun byPhone(phone: String): UserLookup =
            известные[phone.trim()]?.let { UserLookup.Found(it) } ?: UserLookup.NotFound
    }

    private class ПамятныеЗаписи : ChatBook {
        class Запись(val chatId: String, val kind: ChatKind, val title: String?, val peerId: String?)

        val всё = mutableListOf<Запись>()

        override fun remember(chatId: String, kind: ChatKind, title: String?, peerId: String?) {
            всё += Запись(chatId, kind, title, peerId)
        }
    }
}
