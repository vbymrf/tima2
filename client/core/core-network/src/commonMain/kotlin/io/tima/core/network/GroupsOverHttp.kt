package io.tima.core.network

import io.tima.domain.chat.GroupCreateStep
import io.tima.domain.chat.GroupInfo
import io.tima.domain.chat.GroupMember
import io.tima.domain.chat.GroupRegistry
import io.tima.domain.chat.GroupRole
import io.tima.domain.chat.GroupsStep
import io.tima.domain.chat.MemberStep
import io.tima.domain.chat.MembersStep

/**
 * Группы по HTTP — переходник к порту домена.
 *
 * Здесь же роль превращается из строки сервера в перечень домена: незнакомая строка
 * становится [GroupRole.Неизвестная], а не «участником». Сервер может оказаться новее нас, и
 * тогда выдать неизвестной роли права участника значит выдать их по ошибке.
 */
class GroupsOverHttp(private val api: GroupsApi) : GroupRegistry {

    override suspend fun create(title: String): GroupCreateStep = when (val answer = api.create(title)) {
        is GroupCreateResult.Created -> GroupCreateStep.Created(answer.groupId)
        is GroupCreateResult.NoConnection -> GroupCreateStep.Offline(answer.link.retryDelayMs)
        is GroupCreateResult.Refused -> GroupCreateStep.Refused(answer.code)
    }

    override suspend fun mine(): GroupsStep = when (val answer = api.mine()) {
        is GroupsResult.Groups -> GroupsStep.Groups(
            answer.groups.map { GroupInfo(it.groupId, it.title, GroupRole.from(it.myRole)) },
        )
        is GroupsResult.NoConnection -> GroupsStep.Offline(answer.link.retryDelayMs)
        is GroupsResult.Refused -> GroupsStep.Refused(answer.code)
    }

    override suspend fun members(groupId: String): MembersStep = when (val answer = api.members(groupId)) {
        is MembersResult.Members -> MembersStep.Members(
            answer.members.map { GroupMember(it.userId, GroupRole.from(it.role), it.bannedUntil) },
        )
        is MembersResult.NoConnection -> MembersStep.Offline(answer.link.retryDelayMs)
        is MembersResult.Refused -> MembersStep.Refused(answer.code)
    }

    override suspend fun addMember(groupId: String, userId: String): MemberStep =
        step(api.addMember(groupId, userId))

    override suspend fun removeMember(groupId: String, userId: String): MemberStep =
        step(api.removeMember(groupId, userId))

    private fun step(answer: MemberResult): MemberStep = when (answer) {
        MemberResult.Done -> MemberStep.Done
        MemberResult.NoSuchUser -> MemberStep.NoSuchUser
        MemberResult.Forbidden -> MemberStep.Forbidden
        is MemberResult.NoConnection -> MemberStep.Offline(answer.link.retryDelayMs)
        is MemberResult.Refused -> MemberStep.Refused(answer.code)
    }
}
