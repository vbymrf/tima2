package io.tima.domain.chat

/**
 * Тип сущности Социума (doc_mig/ПЛАН-(СЦ)-СОЦИУМА.md, СЦ2): вид × доступ × «секретная».
 *
 * Вид и доступ после создания не меняются (Г5): от доступа группы зависит шифрование. «Секретная» — меняется
 * владельцем и админом: сущность не находится нигде, войти можно только по приглашению. Для группы секретная —
 * тот же тип, что личная или публичная, только скрытая (заказчик 2026-11-18).
 */
data class EntityType(val kind: EntityKind, val reach: EntityReach, val secret: Boolean = false) {
    companion object {
        /** Тип группы по виду и признаку. */
        fun group(kind: GroupKind?, secret: Boolean) =
            EntityType(EntityKind.Group, if (kind == GroupKind.Public) EntityReach.Public else EntityReach.Personal, secret)
    }
}

/** Вид сущности: от него — значок (две головы, рупор, дом, микрофон). */
enum class EntityKind(val wire: String) {
    Group("group"),
    Channel("channel"),
    Community("community"),
    Voice("voice"),
    ;

    companion object {
        fun fromWire(wire: String): EntityKind? = entries.firstOrNull { it.wire == wire }
    }
}

/** Доступ: личная (по составу) или публичная. Метка значка — глобус у публичной, замок у секретной. */
enum class EntityReach { Personal, Public }
