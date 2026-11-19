package io.tima.domain.chat

/**
 * Запись журнала ролей сущности (doc_mig/ПЛАН-(СЦ)-СОЦИУМА.md, СЦ6): кто, кому, что, когда.
 *
 * [action] — `add`, `remove`, `role`, `ban`; [detail] — новая роль или срок бана в секундах.
 */
data class RoleLogEntry(
    val actorId: String,
    val targetId: String,
    val action: String,
    val detail: String,
    val atMs: Long,
)
