package io.tima.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType

/**
 * Группы: создать, перечислить, участники.
 *
 * **Название группы сервер видит открытым** — так устроена его схема (`group_service.go`:
 * `title` до 200 байт, столбец обычный). Для публичной группы это неизбежно: её ищут по
 * названию. Для частной — расхождение с правилом «имя переписки это содержимое», по
 * которому имя личной переписки лежит у нас под шифром покоя и на сервер не уходит вовсе.
 * Решать это правкой клиента нельзя (сервер требует `title`), поэтому расхождение
 * **записано вопросом заказчику**, а клиент пока работает по контракту сервера.
 *
 * Ключей группы здесь нет: они отдельная ручка и отдельная забота (`GroupKeysApi`).
 */
class GroupsApi(
    private val route: ServerRoute,
    private val client: HttpClient,
    private val token: () -> String,
) {

    /**
     * `POST /api/v1/groups` — создать группу.
     *
     * @param kind `private` или `public`. Умолчание сервера — `private`, и мы его повторяем
     *   явно: вид группы решает, кто её видит, и полагаться на чужое умолчание в таком
     *   вопросе нельзя.
     */
    suspend fun create(title: String, kind: String = "private"): GroupCreateResult {
        val response = try {
            client.post(route.api("/api/v1/groups")) {
                header("Authorization", "Bearer ${token()}")
                contentType(ContentType.Application.Json)
                setBody("""{"kind":"$kind","title":${цитата(title)}}""")
            }
        } catch (e: Throwable) {
            return GroupCreateResult.NoConnection(classifyFailure(e))
        }
        val body = response.jsonBody()
        if (response.status != HttpStatusCode.Created) {
            return GroupCreateResult.Refused(response.status.value, body.codeOf())
        }
        return body?.str("group_id")?.let { GroupCreateResult.Created(it) }
            ?: GroupCreateResult.Refused(response.status.value, "201 без group_id")
    }

    /** `GET /api/v1/groups` — мои группы: те, где у меня действующее членство. */
    suspend fun mine(): GroupsResult {
        val response = try {
            client.get(route.api("/api/v1/groups")) {
                header("Authorization", "Bearer ${token()}")
            }
        } catch (e: Throwable) {
            return GroupsResult.NoConnection(classifyFailure(e))
        }
        val body = response.jsonBody()
        if (response.status != HttpStatusCode.OK) {
            return GroupsResult.Refused(response.status.value, body.codeOf())
        }
        val группы = body?.get("groups")?.jsonArrayOrNull()?.mapNotNull { элемент ->
            val объект = элемент.jsonObjectOrNull() ?: return@mapNotNull null
            val id = объект.str("group_id") ?: return@mapNotNull null
            RemoteGroup(
                groupId = id,
                title = объект.str("title").orEmpty(),
                kind = объект.str("kind").orEmpty(),
                ownerId = объект.str("owner_id").orEmpty(),
                myRole = объект.str("my_role").orEmpty(),
            )
        }
        return группы?.let { GroupsResult.Groups(it) }
            ?: GroupsResult.Refused(response.status.value, "ответ без groups")
    }

    /** `GET /api/v1/groups/{id}/members` — участники и роли. */
    suspend fun members(groupId: String): MembersResult {
        val response = try {
            client.get(route.api("/api/v1/groups/$groupId/members")) {
                header("Authorization", "Bearer ${token()}")
            }
        } catch (e: Throwable) {
            return MembersResult.NoConnection(classifyFailure(e))
        }
        val body = response.jsonBody()
        if (response.status != HttpStatusCode.OK) {
            return MembersResult.Refused(response.status.value, body.codeOf())
        }
        val участники = body?.get("members")?.jsonArrayOrNull()?.mapNotNull { элемент ->
            val объект = элемент.jsonObjectOrNull() ?: return@mapNotNull null
            val id = объект.str("user_id") ?: return@mapNotNull null
            RemoteMember(
                userId = id,
                role = объект.str("role").orEmpty(),
                joinedAt = объект.str("joined_at"),
                bannedUntil = объект.str("banned_until"),
            )
        }
        return участники?.let { MembersResult.Members(it) }
            ?: MembersResult.Refused(response.status.value, "ответ без members")
    }

    /**
     * `POST /api/v1/groups/{id}/members` — добавить участника.
     *
     * Роль строго ниже своей — это правило сервера, и отдельный исход [MemberResult.Forbidden]
     * нужен, чтобы человеку сказать про права, а не про сеть.
     */
    suspend fun addMember(groupId: String, userId: String, role: String = "member"): MemberResult {
        val response = try {
            client.post(route.api("/api/v1/groups/$groupId/members")) {
                header("Authorization", "Bearer ${token()}")
                contentType(ContentType.Application.Json)
                setBody("""{"user_id":"$userId","role":"$role"}""")
            }
        } catch (e: Throwable) {
            return MemberResult.NoConnection(classifyFailure(e))
        }
        return исход(response)
    }

    /** `DELETE /api/v1/groups/{id}/members/{userId}` — убрать участника или выйти самому. */
    suspend fun removeMember(groupId: String, userId: String): MemberResult {
        val response = try {
            client.delete(route.api("/api/v1/groups/$groupId/members/$userId")) {
                header("Authorization", "Bearer ${token()}")
            }
        } catch (e: Throwable) {
            return MemberResult.NoConnection(classifyFailure(e))
        }
        return исход(response)
    }

    private suspend fun исход(response: io.ktor.client.statement.HttpResponse): MemberResult {
        val body = response.jsonBody()
        val code = body.codeOf()
        return when {
            response.status.value in 200..299 -> MemberResult.Done
            code == "user_not_found" -> MemberResult.NoSuchUser
            code == "forbidden" -> MemberResult.Forbidden
            else -> MemberResult.Refused(response.status.value, code)
        }
    }

    /**
     * Название в JSON — со сбежавшими кавычками и переводами строк.
     *
     * Тело собирается строкой (как во всех ручках этого модуля), и название — единственное
     * место, куда попадает текст **от человека**. Без экранирования кавычка в названии
     * ломала бы JSON, и выглядело бы это как отказ сервера на пустом месте.
     */
    private fun цитата(текст: String): String {
        val sb = StringBuilder("\"")
        for (знак in текст) {
            when (знак) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (знак < ' ') sb.append("\\u").append(знак.code.toString(16).padStart(4, '0'))
                else sb.append(знак)
            }
        }
        return sb.append('"').toString()
    }
}

/** Группа, как её видит сервер. */
class RemoteGroup(
    val groupId: String,
    val title: String,
    val kind: String,
    val ownerId: String,
    /** Моя роль: `owner`, `admin`, `moderator`, `member`. От неё зависит, что мне можно. */
    val myRole: String,
)

/** Участник группы. */
class RemoteMember(
    val userId: String,
    val role: String,
    val joinedAt: String?,
    /** Забанен до этого времени. `null` — не забанен. */
    val bannedUntil: String?,
)

sealed interface GroupCreateResult {
    data class Created(val groupId: String) : GroupCreateResult
    data class Refused(val status: Int, val code: String) : GroupCreateResult
    data class NoConnection(val link: LinkState) : GroupCreateResult
}

sealed interface GroupsResult {
    data class Groups(val groups: List<RemoteGroup>) : GroupsResult
    data class Refused(val status: Int, val code: String) : GroupsResult
    data class NoConnection(val link: LinkState) : GroupsResult
}

sealed interface MembersResult {
    data class Members(val members: List<RemoteMember>) : MembersResult
    data class Refused(val status: Int, val code: String) : MembersResult
    data class NoConnection(val link: LinkState) : MembersResult
}

/** Исход правки состава. */
sealed interface MemberResult {
    data object Done : MemberResult

    /** Такого пользователя нет: приглашать некого. */
    data object NoSuchUser : MemberResult

    /** Прав не хватает: добавляют owner и admin, роль — строго ниже своей. */
    data object Forbidden : MemberResult
    data class Refused(val status: Int, val code: String) : MemberResult
    data class NoConnection(val link: LinkState) : MemberResult
}
