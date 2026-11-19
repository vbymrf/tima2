package io.tima.shared

import io.tima.domain.chat.ChannelInfo
import io.tima.domain.chat.CommunityItem
import io.tima.domain.chat.CommunityPage
import io.tima.domain.chat.GroupCard
import io.tima.domain.chat.GroupInfo
import io.tima.domain.chat.GroupKind
import io.tima.domain.chat.GroupRole
import io.tima.feature.group.SocialState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Снимок «Каталога» Социума на устройстве (заказчик 2026-11-19: «не хранит в БД, подгрузка по сети идёт, не как
 * чаты в Телефон»). Строки каталога приходили только с сервера и жили в памяти: после запуска приложения окно было
 * пустым, пока не ответят четыре запроса. Теперь последний удачный ответ лежит в настройках устройства, при запуске
 * окно сразу показывает его, а сеть потом обновляет.
 *
 * Хранится то, что нужно строкам: свои группы, карточки «Друзей», сообщества, каналы. Состояние запроса, просьбы и
 * беды — нет: они про текущий заход. Формат — JSON с номером версии; чужая версия или битый текст — снимка нет.
 */
internal object SocialSnapshot {
    private const val VERSION = 1

    fun key(userId: String): String = "social.snapshot.$userId"

    fun encode(s: SocialState): String = buildJsonObject {
        put("v", VERSION)
        put("mine", buildJsonArray {
            for (g in s.mine) add(buildJsonObject {
                put("id", g.groupId); put("title", g.title); put("role", g.myRole.name); put("owner", g.ownerId)
                g.kind?.let { put("kind", it.name) }
                g.callTtlUntilMs?.let { put("ttl", it) }
                g.communityId?.let { put("community", it) }
                put("secret", g.secret)
                g.deleteAtMs?.let { put("delete", it) }
            })
        })
        put("cards", buildJsonArray {
            for (c in s.cards) add(buildJsonObject {
                put("id", c.groupId); put("title", c.title); put("about", c.description); put("kind", c.kind.name)
            })
        })
        put("communities", buildJsonArray {
            for (c in s.communities) add(buildJsonObject {
                put("id", c.communityId); put("title", c.title); put("owner", c.owner); put("admin", c.admin)
                put("subscribed", c.subscribed)
                put("about", JsonArray(c.description.map(::JsonPrimitive)))
                put("items", buildJsonArray {
                    for (i in c.items) add(buildJsonObject {
                        put("kind", i.kind); put("id", i.id); put("title", i.title); put("personal", i.personal)
                    })
                })
            })
        })
        put("channels", buildJsonArray {
            for (c in s.channels) add(buildJsonObject {
                put("id", c.channelId); put("title", c.title); put("owner", c.owner); put("moderator", c.moderator)
                put("public", c.isPublic); put("secret", c.secret)
                c.communityId?.let { put("community", it) }
            })
        })
    }.toString()

    /** Снимок обратно; `null` — снимка нет, он чужой версии или испорчен. */
    fun decode(text: String?): SocialState? = runCatching {
        if (text.isNullOrBlank()) return null
        val o = Json.parseToJsonElement(text).jsonObject
        if (o.int("v") != VERSION) return null
        SocialState(
            mine = o.list("mine").map { g ->
                GroupInfo(
                    groupId = g.str("id")!!,
                    title = g.str("title").orEmpty(),
                    myRole = g.str("role")?.let { r -> GroupRole.entries.firstOrNull { it.name == r } } ?: GroupRole.Unknown,
                    ownerId = g.str("owner").orEmpty(),
                    kind = g.str("kind")?.let { k -> GroupKind.entries.firstOrNull { it.name == k } },
                    callTtlUntilMs = g.long("ttl"),
                    communityId = g.str("community"),
                    secret = g.bool("secret") ?: false,
                    deleteAtMs = g.long("delete"),
                )
            },
            cards = o.list("cards").mapNotNull { c ->
                val kind = c.str("kind")?.let { k -> GroupKind.entries.firstOrNull { it.name == k } } ?: return@mapNotNull null
                GroupCard(c.str("id")!!, c.str("title").orEmpty(), c.str("about").orEmpty(), kind)
            },
            communities = o.list("communities").map { c ->
                CommunityPage(
                    communityId = c.str("id")!!,
                    title = c.str("title").orEmpty(),
                    owner = c.bool("owner") ?: false,
                    admin = c.bool("admin") ?: false,
                    subscribed = c.bool("subscribed") ?: false,
                    description = (c["about"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
                    items = c.list("items").map { i ->
                        CommunityItem(i.str("kind").orEmpty(), i.str("id")!!, i.str("title").orEmpty(), i.bool("personal") ?: false)
                    },
                )
            },
            channels = o.list("channels").map { c ->
                ChannelInfo(
                    channelId = c.str("id")!!,
                    title = c.str("title").orEmpty(),
                    owner = c.bool("owner") ?: false,
                    moderator = c.bool("moderator") ?: false,
                    isPublic = c.bool("public") ?: false,
                    secret = c.bool("secret") ?: false,
                    communityId = c.str("community"),
                )
            },
            loaded = true,
        )
    }.getOrNull()

    private fun JsonObject.list(name: String): List<JsonObject> = (this[name] as? JsonArray)?.map { it.jsonObject }.orEmpty()
    private fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.bool(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.int(name: String): Int? = long(name)?.toInt()

    @Suppress("unused")
    private fun JsonElement.arr() = jsonArray
}
