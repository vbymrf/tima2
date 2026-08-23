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

    private val группа = "gggggggg-0000-0000-0000-000000000001"

    @Test
    fun исключение_влечёт_ротацию() = runTest {
        val ротатор = ПоддельнаяРотация(RotateStep.Rotated)
        val шаг = случай(ротатор = ротатор).исключить(группа, "u-2")

        assertIs<MembershipStep.Done>(шаг)
        assertTrue(шаг.ключСменён)
        assertEquals(listOf(группа), ротатор.ротировали)
    }

    @Test
    fun приглашение_тоже_влечёт_ротацию() = runTest {
        // Вход участника — тоже смена доступа: пришедший не должен читать то, что было до
        // него, а это обеспечивается новой версией ключа, а не запретом на сервере.
        val ротатор = ПоддельнаяРотация(RotateStep.Rotated)
        val шаг = случай(ротатор = ротатор).позвать(группа, "+79990000002")

        assertIs<MembershipStep.Done>(шаг)
        assertEquals(listOf(группа), ротатор.ротировали)
    }

    @Test
    fun несменившийся_ключ_называется_отдельно_а_не_успехом() = runTest {
        // Худший из возможных исходов на этом месте — молчаливый «готово»: человек уверен,
        // что закрыл доступ, а исключённый читает переписку дальше.
        val шаг = случай(ротатор = ПоддельнаяРотация(RotateStep.Offline(5_000)))
            .исключить(группа, "u-2")

        val без = assertIs<MembershipStep.DoneWithoutRotation>(шаг)
        assertTrue("ключ не сменился" in без.предупреждение)
    }

    @Test
    fun чужая_ротация_считается_успехом() = runTest {
        // Версия уже другая — значит, цель достигнута, пусть и не нами. Показывать это
        // отказом значило бы пугать человека тем, что всё в порядке.
        val шаг = случай(ротатор = ПоддельнаяРотация(RotateStep.VersionConflict))
            .исключить(группа, "u-2")
        assertIs<MembershipStep.Done>(шаг)
    }

    @Test
    fun не_админ_правит_состав_но_не_ключ() = runTest {
        val шаг = случай(ротатор = ПоддельнаяРотация(RotateStep.NotAdmin)).исключить(группа, "u-2")
        val без = assertIs<MembershipStep.DoneWithoutRotation>(шаг)
        assertTrue("владелец или админ" in без.предупреждение)
    }

    @Test
    fun незарегистрированный_номер_не_доходит_до_сервера() = runTest {
        val группы = ПоддельныеГруппыСостава()
        val ротатор = ПоддельнаяРотация(RotateStep.Rotated)
        val шаг = случай(группы = группы, ротатор = ротатор).позвать(группа, "+70000000000")

        assertIs<MembershipStep.NoSuchUser>(шаг)
        assertTrue(группы.добавляли.isEmpty(), "запрос ушёл на сервер впустую")
        assertTrue(ротатор.ротировали.isEmpty(), "ключ сменили без изменения состава")
    }

    @Test
    fun отказ_состава_не_ротирует() = runTest {
        // Ротация без смены состава — это лишняя версия ключа у всех устройств и лишняя
        // выдача обёрток. Бесплатной она не бывает.
        val ротатор = ПоддельнаяРотация(RotateStep.Rotated)
        val случай = случай(
            группы = ПоддельныеГруппыСостава(ответ = MemberStep.Forbidden),
            ротатор = ротатор,
        )
        assertIs<MembershipStep.Forbidden>(случай.исключить(группа, "u-2"))
        assertTrue(ротатор.ротировали.isEmpty())
    }

    // ── подделки ────────────────────────────────────────────────────────────

    private fun случай(
        группы: GroupRegistry = ПоддельныеГруппыСостава(),
        ротатор: GroupKeyRotator = ПоддельнаяРотация(RotateStep.Rotated),
    ) = ManageGroupMembers(группы, ПоддельныйСправочникСостава, ротатор)

    private class ПоддельнаяРотация(private val ответ: RotateStep) : GroupKeyRotator {
        val ротировали = mutableListOf<String>()
        override suspend fun ротировать(groupId: String): RotateStep {
            ротировали += groupId
            return ответ
        }
    }

    private class ПоддельныеГруппыСостава(
        private val ответ: MemberStep = MemberStep.Done,
    ) : GroupRegistry {
        val добавляли = mutableListOf<String>()

        override suspend fun create(title: String) = GroupCreateStep.Created("g-1")
        override suspend fun mine() = GroupsStep.Groups(emptyList())
        override suspend fun members(groupId: String) = MembersStep.Members(emptyList())

        override suspend fun addMember(groupId: String, userId: String): MemberStep {
            добавляли += userId
            return ответ
        }

        override suspend fun removeMember(groupId: String, userId: String) = ответ
    }

    private object ПоддельныйСправочникСостава : UserDirectory {
        override suspend fun byPhone(phone: String): UserLookup =
            if (phone == "+79990000002") UserLookup.Found("u-2") else UserLookup.NotFound
    }
}
