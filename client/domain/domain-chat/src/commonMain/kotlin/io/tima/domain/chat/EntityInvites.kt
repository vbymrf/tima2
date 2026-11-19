package io.tima.domain.chat

/**
 * «Секретная» у сущности (doc_mig/ПЛАН-(СЦ)-СОЦИУМА.md, СЦ2): поставить или снять. Отдельным портом, а не полем
 * создания: признак меняется и после — владельцем и админом, — а мастер ставит его сразу после рождения.
 */
fun interface EntitySecrecy {
    /** `true` — сервер принял. */
    suspend fun set(kind: EntityKind, id: String, secret: Boolean): Boolean
}

/** Приглашение в группу, канал, сообщество (СЦ2). */
data class EntityInvite(
    val inviteId: String,
    val kind: EntityKind,
    val entityId: String,
    val inviterId: String,
    val title: String,
    val secret: Boolean,
    /** Личная группа: вход со сменой ключа, как при приёме заявки (Г3). */
    val personal: Boolean,
    val expiresAt: String,
)

/** Что вышло из приглашения или ответа на него. */
sealed interface InviteStep {
    data class Sent(val invite: EntityInvite) : InviteStep
    data object Answered : InviteStep

    /** Человек уже внутри. */
    data object Already : InviteStep

    /** Нет права приглашать. */
    data object Forbidden : InviteStep

    /** Приглашения или сущности нет, срок прошёл, на него уже ответили. */
    data object Gone : InviteStep
    data object Offline : InviteStep
    data class Refused(val reason: String) : InviteStep
}

/**
 * Приглашения (СЦ2). Сервер держит состояние и по нему пускает; карточку человеку шлёт клиент пригласившего —
 * сообщением-ссылкой [inviteLink] в их личной переписке.
 */
interface EntityInvites {
    suspend fun invite(kind: EntityKind, entityId: String, userId: String): InviteStep
    suspend fun mine(): List<EntityInvite>?
    suspend fun answer(inviteId: String, accept: Boolean): InviteStep

    companion object {
        /** Ссылка карточки приглашения: нажатие открывает «Принять / Отказаться». */
        fun inviteLink(inviteId: String): String = "$INVITE_SCHEME$inviteId"

        /** Номер приглашения из ссылки карточки; не наша ссылка — `null`. */
        fun inviteIdOf(url: String): String? = url.removePrefix(INVITE_SCHEME).takeIf { url.startsWith(INVITE_SCHEME) && it.isNotBlank() && '/' !in it }

        /** Ссылка на сущность (§1.6, 2): нажатие открывает её страницу. */
        fun entityLink(kind: EntityKind, id: String): String = "$ENTITY_SCHEME${kind.wire}/$id"

        /** Вид и номер сущности из ссылки; не наша — `null`. */
        fun entityOf(url: String): Pair<EntityKind, String>? {
            if (!url.startsWith(ENTITY_SCHEME)) return null
            val parts = url.removePrefix(ENTITY_SCHEME).split('/')
            if (parts.size != 2 || parts[1].isBlank()) return null
            return EntityKind.fromWire(parts[0])?.let { it to parts[1] }
        }

        const val INVITE_SCHEME: String = "tima://invite/"
        const val ENTITY_SCHEME: String = "tima://entity/"
    }
}
