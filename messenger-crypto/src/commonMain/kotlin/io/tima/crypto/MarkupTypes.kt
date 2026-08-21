package io.tima.crypto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Закрытый каталог типов блоков и инлайн-сущностей (ADR-0011, раздел «Что осталось
 * за рамками»: «конкретный набор типов блоков и их атрибутов — отдельным документом
 * при реализации» — этим файлом и является реализация того документа).
 *
 * Раньше `MarkupEntity.type`/`MarkupBlock.type` были свободной строкой — опечатка
 * или будущий тип клиента, который её не знает, ничем не отличались бы от
 * настоящего типа. Список — тот же словарь, что был у старого формата entities
 * (schema/proto/message_body.proto → `EntityType`, module-boundaries.md §5), но
 * QUOTE/HEADING/LIST_ITEM здесь стали блоками (структура), а не инлайн-сущностями:
 * заголовок или цитата — это то, ЧЕМ является узел, а не оформление внутри него.
 *
 * UNKNOWN — не ошибка разбора. Разметка от более новой версии клиента с типом,
 * которого эта версия ещё не знает, не должна ронять сообщение целиком (тот же
 * принцип, что у [Markup.decode] для разметки в целом): неизвестный тип
 * отрисовывается как обычный текст без оформления.
 */
@Serializable(with = EntityTypeSerializer::class)
enum class EntityType(val wire: String) {
    BOLD("bold"),
    ITALIC("italic"),
    UNDERLINE("underline"),
    STRIKETHROUGH("strikethrough"),
    CODE("code"),
    LINK("link"),
    MENTION("mention"),
    HASHTAG("hashtag"),
    UNKNOWN("");

    companion object {
        fun fromWire(s: String): EntityType = entries.firstOrNull { it.wire == s } ?: UNKNOWN
    }
}

/** Блоки: заголовки, абзацы, списки, контейнеры, таблицы (ADR-0011 §8). */
@Serializable(with = BlockTypeSerializer::class)
enum class BlockType(val wire: String) {
    PARAGRAPH("paragraph"),
    HEADING("heading"),
    QUOTE("quote"),
    LIST("list"),
    LIST_ITEM("list_item"),
    CONTAINER("container"),
    TABLE("table"),
    ROW("row"),
    CELL("cell"),
    UNKNOWN("");

    companion object {
        fun fromWire(s: String): BlockType = entries.firstOrNull { it.wire == s } ?: UNKNOWN
    }
}

/**
 * Кодирует/декодирует по [EntityType.wire], а не по имени enum-константы: имена —
 * деталь Kotlin, `wire` — часть формата (schema/proto/README.md), они обязаны
 * расходиться свободно (например, если понадобится переименовать константу).
 * Неизвестное значение при разборе — [EntityType.UNKNOWN], не исключение.
 */
object EntityTypeSerializer : KSerializer<EntityType> {
    override val descriptor = PrimitiveSerialDescriptor("EntityType", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: EntityType) {
        encoder.encodeString(if (value == EntityType.UNKNOWN) "" else value.wire)
    }
    override fun deserialize(decoder: Decoder): EntityType = EntityType.fromWire(decoder.decodeString())
}

object BlockTypeSerializer : KSerializer<BlockType> {
    override val descriptor = PrimitiveSerialDescriptor("BlockType", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: BlockType) {
        encoder.encodeString(if (value == BlockType.UNKNOWN) "" else value.wire)
    }
    override fun deserialize(decoder: Decoder): BlockType = BlockType.fromWire(decoder.decodeString())
}
