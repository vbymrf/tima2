package io.tima.domain.chat

/**
 * Переписка «сущность — человек» (doc_mig/ПЛАН-(СЦ)-СОЦИУМА.md §1.9, СЦ15): одна лента на пару «сущность верхнего
 * уровня — человек». Автор стороны сущности человеку не виден; команде — виден.
 */
interface EntityThreads {
    /** Мои ленты — «Общение → Ответы». `null` — нет связи. */
    suspend fun mine(): List<ThreadRow>?

    /** Моя лента с сущностью (у элемента сообщества — лента сообщества). `null` — нет связи. */
    suspend fun mineWith(kind: EntityKind, id: String): ThreadPage?

    /** Написать сущности. */
    suspend fun write(kind: EntityKind, id: String, body: ByteArray, preview: String): ThreadStep

    /** Включено, выключено, заблокировано — у меня. */
    suspend fun setState(kind: EntityKind, id: String, state: ThreadState): Boolean

    suspend fun read(kind: EntityKind, id: String, upTo: Long): Boolean

    /** «Обращения» сущности — команде. `null` — нет связи или права. */
    suspend fun appeals(kind: EntityKind, id: String): Appeals?

    /** Лента сущности с человеком — команде, с авторами. */
    suspend fun withPerson(kind: EntityKind, id: String, userId: String): ThreadPage?

    /** Написать за сущность. */
    suspend fun writeAs(kind: EntityKind, id: String, userId: String, body: ByteArray, preview: String): ThreadStep

    suspend fun readAs(kind: EntityKind, id: String, userId: String, upTo: Long): Boolean
    suspend fun block(kind: EntityKind, id: String, userId: String, blocked: Boolean): Boolean
    suspend fun setAccepts(kind: EntityKind, id: String, accept: Boolean): Boolean
}

enum class ThreadState(val wire: String) {
    On("on"),
    Off("off"),
    Blocked("blocked"),
    ;

    companion object {
        fun fromWire(wire: String): ThreadState = entries.firstOrNull { it.wire == wire } ?: On
    }
}

/** Строка ленты — у человека «Ответы», у команды «Обращения». */
data class ThreadRow(
    val kind: EntityKind,
    val entityId: String,
    val userId: String,
    val title: String,
    val preview: String,
    val lastAtMs: Long,
    val unread: Int,
    val state: ThreadState,
    val blocked: Boolean,
)

/** Сообщение ленты; [authorId] — только у команды. */
data class ThreadMessage(
    val messageId: Long,
    val fromEntity: Boolean,
    val body: ByteArray,
    val preview: String,
    val atMs: Long,
    val authorId: String? = null,
) {
    override fun equals(other: Any?) = other is ThreadMessage && other.messageId == messageId
    override fun hashCode() = messageId.hashCode()
}

data class ThreadPage(
    val kind: EntityKind,
    val entityId: String,
    val accepts: Boolean,
    val state: ThreadState,
    val blocked: Boolean,
    val messages: List<ThreadMessage>,
)

data class Appeals(val rows: List<ThreadRow>, val accepts: Boolean, val peopleToday: Int, val peopleLimit: Int)

sealed interface ThreadStep {
    data object Sent : ThreadStep
    data object NotAccepting : ThreadStep

    /** Сущность заблокировала меня — или человек заблокировал сущность. */
    data object Blocked : ThreadStep

    /** Пять людей в сутки — предел (§1.9, 1). */
    data object PeopleLimit : ThreadStep
    data object Offline : ThreadStep
    data class Refused(val reason: String) : ThreadStep
}
