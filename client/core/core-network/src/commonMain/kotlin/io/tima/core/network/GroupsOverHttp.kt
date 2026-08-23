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

    override suspend fun create(title: String): GroupCreateStep = when (val ответ = api.create(title)) {
        is GroupCreateResult.Created -> GroupCreateStep.Created(ответ.groupId)
        is GroupCreateResult.NoConnection -> GroupCreateStep.Offline(ответ.link.retryDelayMs)
        is GroupCreateResult.Refused -> GroupCreateStep.Refused(ответ.code)
    }

    override suspend fun mine(): GroupsStep = when (val ответ = api.mine()) {
        is GroupsResult.Groups -> GroupsStep.Groups(
            ответ.groups.map { GroupInfo(it.groupId, it.title, GroupRole.из(it.myRole)) },
        )
        is GroupsResult.NoConnection -> GroupsStep.Offline(ответ.link.retryDelayMs)
        is GroupsResult.Refused -> GroupsStep.Refused(ответ.code)
    }

    override suspend fun members(groupId: String): MembersStep = when (val ответ = api.members(groupId)) {
        is MembersResult.Members -> MembersStep.Members(
            ответ.members.map { GroupMember(it.userId, GroupRole.из(it.role), it.bannedUntil) },
        )
        is MembersResult.NoConnection -> MembersStep.Offline(ответ.link.retryDelayMs)
        is MembersResult.Refused -> MembersStep.Refused(ответ.code)
    }

    override suspend fun addMember(groupId: String, userId: String): MemberStep =
        шаг(api.addMember(groupId, userId))

    override suspend fun removeMember(groupId: String, userId: String): MemberStep =
        шаг(api.removeMember(groupId, userId))

    private fun шаг(ответ: MemberResult): MemberStep = when (ответ) {
        MemberResult.Done -> MemberStep.Done
        MemberResult.NoSuchUser -> MemberStep.NoSuchUser
        MemberResult.Forbidden -> MemberStep.Forbidden
        is MemberResult.NoConnection -> MemberStep.Offline(ответ.link.retryDelayMs)
        is MemberResult.Refused -> MemberStep.Refused(ответ.code)
    }
}
