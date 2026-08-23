package io.tima.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType

/**
 * Групповой ключ: ротация и получение своих обёрток.
 *
 * **Сам ключ на сервер не уходит никогда.** Уходят обёртки — по одной на устройство
 * участника, — и escrow-блоб. Сервер раскладывает обёртки по устройствам и рассылает
 * событие `key.rotated`; прочитать ключ он не может ни из одной из этих частей.
 *
 * Ротирует **owner или admin** private-группы: ключ порождает клиент-инициатор, не сервер
 * (`crypto-protocol §4.2`). Версия строго `current + 1` — иначе сервер отвечает
 * `version_conflict`, и это нормальный исход гонки двух админов, а не поломка.
 */
class GroupKeysApi(
    private val route: ServerRoute,
    private val client: HttpClient,
    private val token: () -> String,
) {

    /**
     * `POST /api/v1/groups/{id}/keys` — новая версия ключа.
     *
     * @param wrappedKeys обёртки по `device_id`. Сервер отвергнет обёртку для устройства
     *   не-участника: список получателей — единственный источник того, кто ключ получит,
     *   и лишний в нём означал бы выданный доступ.
     * @param reason `periodic`, `member_join`, `member_leave`, `compromise` — сервер это
     *   пишет в журнал ротаций. Причина не влияет на права, но по ней потом видно, почему
     *   ключ сменился.
     */
    suspend fun rotate(
        groupId: String,
        gkVersion: Int,
        senderEphemeralPub: ByteArray,
        escrowMlkemCt: ByteArray,
        escrowWrappedKey: ByteArray,
        escrowKeyVersion: Int,
        wrappedKeys: Map<String, ByteArray>,
        reason: String = "periodic",
    ): RotateResult {
        require(wrappedKeys.isNotEmpty()) { "ротация без получателей бессмысленна" }
        val обёртки = wrappedKeys.entries.joinToString(",") { (устройство, байты) ->
            """{"recipient":"$устройство","wrapped":"${encodeBase64Url(байты)}"}"""
        }
        val тело = """{"gk_version":$gkVersion,"reason":"$reason",""" +
            """"sender_ephemeral_pub":"${encodeBase64Url(senderEphemeralPub)}",""" +
            """"escrow":{"mlkem_ct":"${encodeBase64Url(escrowMlkemCt)}",""" +
            """"wrapped_message_key":"${encodeBase64Url(escrowWrappedKey)}",""" +
            """"escrow_key_version":$escrowKeyVersion},""" +
            """"wrapped_keys":[$обёртки]}"""

        val response = try {
            client.post(route.api("/api/v1/groups/$groupId/keys")) {
                header("Authorization", "Bearer ${token()}")
                contentType(ContentType.Application.Json)
                setBody(тело)
            }
        } catch (e: Throwable) {
            return RotateResult.NoConnection(classifyFailure(e))
        }
        val body = response.jsonBody()
        val code = body.codeOf()
        return when {
            response.status.value in 200..299 -> RotateResult.Rotated

            // Кто-то успел раньше: не поломка, а гонка двух админов. Лечится тем, что мы
            // перечитываем текущую версию и решаем заново, нужна ли ротация вообще.
            code == "version_conflict" -> RotateResult.VersionConflict

            code == "not_group_admin" -> RotateResult.NotAdmin

            // Обёртка для устройства не-участника: наш список получателей устарел, надо
            // перечитать состав. Это единственный отказ, который означает «данные у нас
            // старые», а не «нам нельзя».
            code == "recipient_not_member" -> RotateResult.StaleMembers
            else -> RotateResult.Refused(response.status.value, code)
        }
    }

    /**
     * `GET /api/v1/groups/{id}/keys?since_version=N` — обёртки, адресованные ЭТОМУ устройству.
     *
     * @param sinceVersion какие версии у нас уже есть. Ноль — «дайте всё, что есть».
     * @return вместе с обёртками приходит `current_version` группы: она может быть больше
     *   всего, что выдано нам, — например, когда наше устройство добавили после ротации.
     *   Админу это нужно, чтобы ротировать `current + 1`, а не «наша + 1».
     */
    suspend fun mine(groupId: String, sinceVersion: Int = 0): GroupKeysResult {
        val response = try {
            client.get(route.api("/api/v1/groups/$groupId/keys?since_version=$sinceVersion")) {
                header("Authorization", "Bearer ${token()}")
            }
        } catch (e: Throwable) {
            return GroupKeysResult.NoConnection(classifyFailure(e))
        }
        val body = response.jsonBody()
        if (response.status != HttpStatusCode.OK) {
            return GroupKeysResult.Refused(response.status.value, body.codeOf())
        }
        val ключи = body?.get("keys")?.jsonArrayOrNull()?.mapNotNull { элемент ->
            val объект = элемент.jsonObjectOrNull() ?: return@mapNotNull null
            val версия = объект.int("gk_version") ?: return@mapNotNull null
            val эфемерный = объект.str("sender_ephemeral_pub")?.let { decodeBase64Url(it) }
                ?: return@mapNotNull null
            val обёртка = объект.str("wrapped")?.let { decodeBase64Url(it) } ?: return@mapNotNull null
            WrappedGroupKey(gkVersion = версия, senderEphemeralPub = эфемерный, wrapped = обёртка)
        } ?: return GroupKeysResult.Refused(response.status.value, "ответ без keys")

        return GroupKeysResult.Keys(keys = ключи, currentVersion = body.int("current_version") ?: 0)
    }
}

/** Обёртка группового ключа для этого устройства. */
class WrappedGroupKey(
    val gkVersion: Int,
    /** Эфемерный публичный ключ ротации: без него обёртку не развернуть. */
    val senderEphemeralPub: ByteArray,
    val wrapped: ByteArray,
)

sealed interface RotateResult {
    data object Rotated : RotateResult

    /** Версия занята: кто-то ротировал раньше. Перечитать и решить заново. */
    data object VersionConflict : RotateResult

    /** Ротируют owner и admin. */
    data object NotAdmin : RotateResult

    /** В получателях оказалось устройство не-участника: состав у нас устарел. */
    data object StaleMembers : RotateResult
    data class Refused(val status: Int, val code: String) : RotateResult
    data class NoConnection(val link: LinkState) : RotateResult
}

sealed interface GroupKeysResult {
    data class Keys(val keys: List<WrappedGroupKey>, val currentVersion: Int) : GroupKeysResult
    data class Refused(val status: Int, val code: String) : GroupKeysResult
    data class NoConnection(val link: LinkState) : GroupKeysResult
}
