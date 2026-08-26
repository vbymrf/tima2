package io.tima.domain.chat

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Правка состава и её главное следствие — ротация ключа.
 *
 * Проверяется то, из-за чего исключение вообще работает: без смены ключа исключённый
 * продолжает читать новые сообщения, поэтому «состав правлен» и «доступ закрыт» — разные
 * исходы, и путать их нельзя.
 */
class ManageGroupMembersTest {

    private val group = "gggggggg-0000-0000-0000-000000000001"

    @Test
    fun исключение_влечёт_ротацию() = runTest {
        val rotator = FakeRotation(RotateStep.Rotated)
        val step = case(rotator = rotator).remove(group, "u-2")

        assertIs<MembershipStep.Done>(step)
        assertTrue(step.switchedKey)
        assertEquals(listOf(group), rotator.rotated)
    }

    @Test
    fun приглашение_тоже_влечёт_ротацию() = runTest {
        // Вход участника — тоже смена доступа: пришедший не должен читать то, что было до
        // него, а это обеспечивается новой версией ключа, а не запретом на сервере.
        val rotator = FakeRotation(RotateStep.Rotated)
        val step = case(rotator = rotator).invite(group, "+79990000002")

        assertIs<MembershipStep.Done>(step)
        assertEquals(listOf(group), rotator.rotated)
    }

    @Test
    fun несменившийся_ключ_называется_отдельно_а_не_успехом() = runTest {
        // Худший из возможных исходов на этом месте — молчаливый «готово»: человек уверен,
        // что закрыл доступ, а исключённый читает переписку дальше.
        val step = case(rotator = FakeRotation(RotateStep.Offline(5_000)))
            .remove(group, "u-2")

        val without = assertIs<MembershipStep.DoneWithoutRotation>(step)
        assertTrue("ключ не сменился" in without.warning)
    }

    @Test
    fun чужая_ротация_считается_успехом() = runTest {
        // Версия уже другая — значит, цель достигнута, пусть и не нами. Показывать это
        // отказом значило бы пугать человека тем, что всё в порядке.
        val step = case(rotator = FakeRotation(RotateStep.VersionConflict))
            .remove(group, "u-2")
        assertIs<MembershipStep.Done>(step)
    }

    @Test
    fun не_админ_правит_состав_но_не_ключ() = runTest {
        val step = case(rotator = FakeRotation(RotateStep.NotAdmin)).remove(group, "u-2")
        val without = assertIs<MembershipStep.DoneWithoutRotation>(step)
        assertTrue("владелец или админ" in without.warning)
    }

    @Test
    fun незарегистрированный_номер_не_доходит_до_сервера() = runTest {
        val groups = FakeGroupMembers()
        val rotator = FakeRotation(RotateStep.Rotated)
        val step = case(groups = groups, rotator = rotator).invite(group, "+70000000000")

        assertIs<MembershipStep.NoSuchUser>(step)
        assertTrue(groups.added.isEmpty(), "запрос ушёл на сервер впустую")
        assertTrue(rotator.rotated.isEmpty(), "ключ сменили без изменения состава")
    }

    @Test
    fun отказ_состава_не_ротирует() = runTest {
        // Ротация без смены состава — это лишняя версия ключа у всех устройств и лишняя
        // выдача обёрток. Бесплатной она не бывает.
        val rotator = FakeRotation(RotateStep.Rotated)
        val case = case(
            groups = FakeGroupMembers(answer = MemberStep.Forbidden),
            rotator = rotator,
        )
        assertIs<MembershipStep.Forbidden>(case.remove(group, "u-2"))
        assertTrue(rotator.rotated.isEmpty())
    }

    // ── подделки ────────────────────────────────────────────────────────────

    private fun case(
        groups: GroupRegistry = FakeGroupMembers(),
        rotator: GroupKeyRotator = FakeRotation(RotateStep.Rotated),
    ) = ManageGroupMembers(groups, FakeDirectoryMembers, rotator)

    private class FakeRotation(private val answer: RotateStep) : GroupKeyRotator {
        val rotated = mutableListOf<String>()
        override suspend fun rotate(groupId: String): RotateStep {
            rotated += groupId
            return answer
        }
    }

    private class FakeGroupMembers(
        private val answer: MemberStep = MemberStep.Done,
    ) : GroupRegistry {
        val added = mutableListOf<String>()

        override suspend fun create(title: String) = GroupCreateStep.Created("g-1")
        override suspend fun mine() = GroupsStep.Groups(emptyList())
        override suspend fun members(groupId: String) = MembersStep.Members(emptyList())

        override suspend fun addMember(groupId: String, userId: String): MemberStep {
            added += userId
            return answer
        }

        override suspend fun removeMember(groupId: String, userId: String) = answer
    }

    private object FakeDirectoryMembers : UserDirectory {
        override suspend fun byPhone(phone: String): UserLookup =
            if (phone == "+79990000002") UserLookup.Found("u-2") else UserLookup.NotFound
    }
}
