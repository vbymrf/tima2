package io.tima.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.tima.domain.chat.ChatPerson
import io.tima.domain.chat.NicknameDirectory
import io.tima.domain.chat.NicknameHit
import io.tima.domain.chat.MIN_NICKNAME_QUERY
import io.tima.domain.chat.UserDirectory
import io.tima.domain.chat.UserLookup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.putJsonArray

/**
 * Справочник: кто скрывается за номером телефона — переходник к порту `domain-chat`.
 *
 * Контракт снят с `internal/api/auth.go`:
 * `GET /api/v1/users/lookup?phone=+7…` → `{user_id}`, либо `404 user_not_found`, либо
 * `400 bad_phone`. Имя спрашивается отдельно — `POST /api/v1/users/names {ids}` →
 * `{names:{id:name}, phones:{id:phone}}`, и **только для собеседников по перепискам**:
 * сервер не отдаёт имена посторонних, и это его решение, а не наше упущение.
 *
 * **«Не найден» — не отказ.** Человека, которого нет в TIMA, надо позвать, а не сообщать
 * ему об ошибке. Поэтому [UserLookup.NotFound] отдельный исход, а не `Refused`.
 */
class UsersApi(
    private val route: ServerRoute,
    private val client: HttpClient,
    private val token: () -> String,
) : UserDirectory, NicknameDirectory {

    /** Чей это ник — `GET /nicknames/{nick}`; 404 — ничей. */
    override suspend fun byNickname(nick: String): UserLookup {
        val response = try {
            client.get(route.api("/api/v1/nicknames/$nick")) {
                header("Authorization", "Bearer ${token()}")
            }
        } catch (e: Throwable) {
            return UserLookup.Offline(classifyFailure(e).retryDelayMs)
        }
        val body = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
        val code = body?.get("code")?.jsonPrimitive?.content
        return when (response.status) {
            HttpStatusCode.OK -> body?.get("user_id")?.jsonPrimitive?.content
                ?.takeIf { it.isNotBlank() }
                ?.let { UserLookup.Found(it) }
                ?: UserLookup.Refused("в ответе нет user_id")
            HttpStatusCode.NotFound -> UserLookup.NotFound
            HttpStatusCode.BadRequest -> UserLookup.Refused(code ?: "ник не по правилам")
            else -> UserLookup.Refused(code ?: "сервер отказал: ${response.status.value}")
        }
    }

    /**
     * Похожие ники — `GET /nicknames?q=` (Л10).
     *
     * Правило «точное, иначе похожие» на сервере: здесь только запрос и разбор. Три
     * знака — предел сервера, и здесь он повторён, чтобы не ходить заведомо зря: за
     * короткий запрос сервер ответит отказом, а круг уже сделан.
     */
    override suspend fun searchNicknames(query: String): List<NicknameHit>? {
        val q = query.trim()
        if (q.length < MIN_NICKNAME_QUERY) return emptyList()
        val response = try {
            client.get(route.api("/api/v1/nicknames")) {
                header("Authorization", "Bearer ${token()}")
                parameter("q", q)
            }
        } catch (_: Throwable) {
            return null
        }
        if (response.status != HttpStatusCode.OK) return null
        val body = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull() ?: return null
        val found = body["found"] as? JsonArray ?: return emptyList()
        return found.mapNotNull { row ->
            val obj = row as? JsonObject ?: return@mapNotNull null
            val id = obj["user_id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            NicknameHit(id, obj["nickname"]?.jsonPrimitive?.content.orEmpty())
        }
    }

    /**
     * Люди по идентификаторам — одним походом: имя, ник, номер (номер — только по
     * собеседникам своих переписок). Кого сервер не знает — того в карте нет.
     * `null` — сеть или отказ: вызывающий отличит «не знаем» от «не спросили».
     */
    suspend fun cards(ids: Collection<String>): Map<String, ChatPerson>? {
        if (ids.isEmpty()) return emptyMap()
        val response = try {
            client.post(route.api("/api/v1/users/names")) {
                header("Authorization", "Bearer ${token()}")
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { putJsonArray("ids") { ids.forEach { add(it) } } }.toString())
            }
        } catch (e: Throwable) {
            return null
        }
        if (response.status != HttpStatusCode.OK) return null
        val body = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull() ?: return null
        fun map(key: String): Map<String, String> =
            (body[key] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content }?.filterValues { it.isNotBlank() } ?: emptyMap()
        val names = map("names")
        val nicks = map("nicknames")
        val phones = map("phones")
        val avatars = map("avatars")
        val revs = (body["profile_revs"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.intOrNull } ?: emptyMap()
        return ids.associateWith { id ->
            ChatPerson(userName = names[id], nick = nicks[id], phone = phones[id], avatar = avatars[id], rev = revs[id])
        }
    }

    override suspend fun byPhone(phone: String): UserLookup {
        val response = try {
            client.get(route.api("/api/v1/users/lookup")) {
                header("Authorization", "Bearer ${token()}")
                parameter("phone", phone)
            }
        } catch (e: Throwable) {
            return UserLookup.Offline(classifyFailure(e).retryDelayMs)
        }

        val body = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
        val code = body?.get("code")?.jsonPrimitive?.content
        when (response.status) {
            HttpStatusCode.OK -> Unit
            HttpStatusCode.NotFound -> return UserLookup.NotFound
            HttpStatusCode.BadRequest -> return UserLookup.BadPhone(code ?: "номер не в формате E.164")
            else -> return UserLookup.Refused(code ?: "сервер отказал: ${response.status.value}")
        }

        val userId = body?.get("user_id")?.jsonPrimitive?.content
        if (userId.isNullOrBlank()) return UserLookup.Refused("в ответе нет user_id")

        // Имя — вторым запросом и **без права уронить первый**: не пришло, значит его нет,
        // и переписка заведётся с номером вместо имени. Отказаться начинать переписку
        // из-за неизвестного имени было бы хуже.
        return UserLookup.Found(userId = userId, name = name(userId))
    }

    /**
     * Имя или номер собеседника — чем назвать переписку.
     *
     * Сервер отдаёт номера **только по собеседникам своих переписок** (`PhonesOfChatPeers`),
     * и это его решение, а не наше упущение: справочник целиком он не раскрывает. Поэтому
     * ответ бывает пустым, и это не отказ — переписка называется тем, что известно.
     */
    suspend fun nameOrNumber(userId: String): String? = name(userId) ?: number(userId)

    /** Отображаемое имя, если сервер его знает и вправе отдать. */
    private suspend fun name(userId: String): String? {
        val response = try {
            client.post(route.api("/api/v1/users/names")) {
                header("Authorization", "Bearer ${token()}")
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        putJsonArray("ids") { add(userId) }
                    }.toString(),
                )
            }
        } catch (e: Throwable) {
            return null
        }
        if (response.status != HttpStatusCode.OK) return null
        val body = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
        return body?.get("names")?.let { (it as? JsonObject) }
            ?.get(userId)?.jsonPrimitive?.content
            ?.takeIf { it.isNotBlank() }
    }

    /** Медиа аватара, если человек его поставил. Из того же ответа имён (`avatars`). */
    suspend fun avatarOf(userId: String): String? = field("avatars", userId)

    private suspend fun field(map: String, userId: String): String? {
        val response = try {
            client.post(route.api("/api/v1/users/names")) {
                header("Authorization", "Bearer ${token()}")
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { putJsonArray("ids") { add(userId) } }.toString())
            }
        } catch (e: Throwable) {
            return null
        }
        if (response.status != HttpStatusCode.OK) return null
        val body = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
        return body?.get(map)?.let { (it as? JsonObject) }
            ?.get(userId)?.jsonPrimitive?.content
            ?.takeIf { it.isNotBlank() }
    }

    /** Номер — вторым выбором: он известен только по собеседникам своих переписок. */
    private suspend fun number(userId: String): String? {
        val response = try {
            client.post(route.api("/api/v1/users/names")) {
                header("Authorization", "Bearer ${token()}")
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { putJsonArray("ids") { add(userId) } }.toString())
            }
        } catch (e: Throwable) {
            return null
        }
        if (response.status != HttpStatusCode.OK) return null
        val body = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
        return body?.get("phones")?.let { (it as? JsonObject) }
            ?.get(userId)?.jsonPrimitive?.content
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * Чьи это личности и какие из них текущие или отменены — `POST /users/identities`
     * (ДУ6). `null` — сеть или отказ.
     */
    suspend fun identities(ids: Collection<String>): Map<String, IdentityStatus>? {
        if (ids.isEmpty()) return emptyMap()
        val response = try {
            client.post(route.api("/api/v1/users/identities")) {
                header("Authorization", "Bearer ${token()}")
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { putJsonArray("ids") { ids.forEach { add(it) } } }.toString())
            }
        } catch (e: Throwable) {
            return null
        }
        if (response.status != HttpStatusCode.OK) return null
        val body = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull() ?: return null
        val map = body["identities"] as? JsonObject ?: return emptyMap()
        return map.mapNotNull { (id, el) ->
            val o = el as? JsonObject ?: return@mapNotNull null
            id to IdentityStatus(
                personId = o["person_id"]?.jsonPrimitive?.content.orEmpty(),
                current = o["current"]?.jsonPrimitive?.content == "true",
                cancelled = o["cancelled"]?.jsonPrimitive?.content == "true",
                currentId = o["current_id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
                reregistered = o["reregistered"]?.jsonPrimitive?.content == "true",
                disputedUntil = o.msOf("disputed_until"),
                deleteAt = o.msOf("delete_at"),
            )
        }.toMap()
    }

    /** Вызов для подписи фразой — `POST /users/me/reidentify/challenge`. `null` — не дали. */
    suspend fun identityChallenge(): String? {
        val response = try {
            client.post(route.api("/api/v1/users/me/reidentify/challenge")) {
                header("Authorization", "Bearer ${token()}")
            }
        } catch (e: Throwable) {
            return null
        }
        if (response.status != HttpStatusCode.OK) return null
        return runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject["challenge_token"]?.jsonPrimitive?.content }.getOrNull()
    }

    /**
     * Отменить новые личности своего аккаунта — `POST /users/me/identity/cancel` (ДУ6, Р27):
     * прежнее устройство подтверждает фразой (подпись вызова ключом личности).
     */
    suspend fun cancelNewIdentity(challenge: String, signature: ByteArray): TrustCallResult {
        val response = try {
            client.post(route.api("/api/v1/users/me/identity/cancel")) {
                header("Authorization", "Bearer ${token()}")
                contentType(ContentType.Application.Json)
                setBody("""{"challenge_token":"$challenge","signature":"${encodeBase64Url(signature)}"}""")
            }
        } catch (e: Throwable) {
            return TrustCallResult.Offline(classifyFailure(e))
        }
        if (response.status.value in 200..299) return TrustCallResult.Done
        val code = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject["code"]?.jsonPrimitive?.content }.getOrNull() ?: "без кода"
        return TrustCallResult.Refused(response.status.value, code)
    }

    /**
     * Запрет «Начать заново» на своём аккаунте (ДУ10, Р41) и номер аккаунта — на него идёт
     * SMS при постановке запрета. `null` — не узнали.
     */
    suspend fun startAnewState(): StartAnewState? {
        val response = try {
            client.get(route.api("/api/v1/users/me")) { header("Authorization", "Bearer ${token()}") }
        } catch (e: Throwable) {
            return null
        }
        if (response.status != HttpStatusCode.OK) return null
        val body = response.jsonBody() ?: return null
        return StartAnewState(phone = body.str("phone").orEmpty(), banned = body.bool("start_anew_banned") == true)
    }

    /** `GET /users/me/rereg` — идёт ли перерегистрация (ДУ9). `null` — не узнали. */
    suspend fun reregState(): ReregState? {
        val response = try {
            client.get(route.api("/api/v1/users/me/rereg")) { header("Authorization", "Bearer ${token()}") }
        } catch (e: Throwable) {
            return null
        }
        if (response.status != HttpStatusCode.OK) return null
        val body = response.jsonBody() ?: return null
        if (body.bool("active") != true) return ReregState(active = false)
        return ReregState(
            active = true,
            role = body.str("role").orEmpty(),
            round = body.int("round") ?: 0,
            windowFrom = body.msOf("window_from") ?: 0,
            windowTo = body.msOf("window_to") ?: 0,
            disputed = body.bool("disputed") == true,
            confirmed = body.bool("confirmed") == true,
            oldUserId = body.str("old_user_id").orEmpty(),
        )
    }

    /**
     * «Аккаунт украден» (`claim`) или подтверждение в окне (`confirm`) — ДУ9, Р34: SMS на номер
     * аккаунта и подпись вызова ключом личности; у Н при подтверждении — ещё ключом прежней.
     */
    suspend fun rereg(action: String, registrationToken: String, challenge: String, signature: ByteArray, oldSignature: ByteArray? = null): TrustCallResult {
        val old = oldSignature?.let { ",\"old_signature\":\"${encodeBase64Url(it)}\"" }.orEmpty()
        val response = try {
            client.post(route.api("/api/v1/users/me/rereg/$action")) {
                header("Authorization", "Bearer ${token()}")
                contentType(ContentType.Application.Json)
                setBody("""{"registration_token":"$registrationToken","challenge_token":"$challenge","signature":"${encodeBase64Url(signature)}"$old}""")
            }
        } catch (e: Throwable) {
            return TrustCallResult.Offline(classifyFailure(e))
        }
        if (response.status.value in 200..299) return TrustCallResult.Done
        return TrustCallResult.Refused(response.status.value, response.jsonBody().codeOf())
    }

    /**
     * `POST /users/me/start-anew-ban` — закрыть «Начать заново» навсегда: SMS на номер
     * аккаунта (`registrationToken`) и подпись вызова ключом личности из фразы.
     */
    suspend fun banStartAnew(registrationToken: String, challenge: String, signature: ByteArray): TrustCallResult {
        val response = try {
            client.post(route.api("/api/v1/users/me/start-anew-ban")) {
                header("Authorization", "Bearer ${token()}")
                contentType(ContentType.Application.Json)
                setBody("""{"registration_token":"$registrationToken","challenge_token":"$challenge","signature":"${encodeBase64Url(signature)}"}""")
            }
        } catch (e: Throwable) {
            return TrustCallResult.Offline(classifyFailure(e))
        }
        if (response.status.value in 200..299) return TrustCallResult.Done
        return TrustCallResult.Refused(response.status.value, response.jsonBody().codeOf())
    }

}

/** Запрет «Начать заново» на аккаунте (ДУ10) и номер, на который идёт SMS. */
class StartAnewState(val phone: String, val banned: Boolean)

private fun kotlinx.serialization.json.JsonArrayBuilder.add(value: String) {
    add(kotlinx.serialization.json.JsonPrimitive(value))
}

/** Состояние личности (ДУ6): чей аккаунт, текущая ли, отменена ли хозяином. */
class IdentityStatus(
    val personId: String,
    val current: Boolean,
    val cancelled: Boolean,
    /** Текущая личность того же аккаунта; `null` — сервер старый. */
    val currentId: String? = null,
    /** Заведена перерегистрацией (ДУ9): собеседнику — «перерегистрировал аккаунт». */
    val reregistered: Boolean = false,
    /** Идёт спор за аккаунт — до этого момента, мс; `null` — спора нет. */
    val disputedUntil: Long? = null,
    /** Личность на удалении (ДУ11), мс; её сообщения помечаются, как у отменённой (Р49). */
    val deleteAt: Long? = null,
)

private fun JsonObject.msOf(key: String): Long? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content
        ?.let { runCatching { kotlinx.datetime.Instant.parse(it).toEpochMilliseconds() }.getOrNull() }

/** Перерегистрация глазами стороны (ДУ9): что показать в «Секретная фраза и устройства». */
class ReregState(
    val active: Boolean,
    /** `new` — эта личность заведена перерегистрацией (Н), `old` — прежняя (С). */
    val role: String = "",
    val round: Int = 0,
    val windowFrom: Long = 0,
    val windowTo: Long = 0,
    val disputed: Boolean = false,
    val confirmed: Boolean = false,
    /** Прежняя личность (С) — новой нужна, чтобы перенести её копию к себе (М6). */
    val oldUserId: String = "",
)
