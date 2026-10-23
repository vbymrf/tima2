package io.tima.crypto

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Контракт значений на проводе (ADR-0011, «Что осталось за рамками» — закрытый
 * каталог типов). `.wire` — часть формата, а не деталь Kotlin: переименование
 * enum-константы не должно тихо переименовать и то, что уходит в JSON. Здесь же
 * фиксируется полный список — случайно удалённый тип будет замечен сразу, а не
 * когда где-то перестанет парситься старое сообщение.
 */
class MarkupTypesTest {

    @Test
    fun `wire-значения EntityType зафиксированы`() {
        assertEquals(
            setOf("bold", "italic", "underline", "strikethrough", "code", "link", "mention", "hashtag", "highlight", ""),
            EntityType.entries.map { it.wire }.toSet(),
        )
    }

    @Test
    fun `wire-значения BlockType зафиксированы`() {
        assertEquals(
            setOf(
                "paragraph", "heading", "quote", "list", "list_item", "container", "table", "row", "cell",
                // ADR-0044: ряд, разделитель, блок кода, виджеты, подкладка
                "flex", "divider", "code_block", "image", "link", "cloud", "map", "contact", "reply",
                "quoted", "emoji", "underlay",
                // ПЛАН-(ВМ) §3.6–§3.8: видео, звук, эфир и метки по ссылке
                "video", "audio", "stream", "marks", "",
            ),
            BlockType.entries.map { it.wire }.toSet(),
        )
    }

    @Test
    fun `fromWire - известное значение возвращает свою константу`() {
        for (t in EntityType.entries - EntityType.UNKNOWN) {
            assertEquals(t, EntityType.fromWire(t.wire))
        }
        for (t in BlockType.entries - BlockType.UNKNOWN) {
            assertEquals(t, BlockType.fromWire(t.wire))
        }
    }

    @Test
    fun `fromWire - неизвестное значение и пустая строка дают UNKNOWN`() {
        assertEquals(EntityType.UNKNOWN, EntityType.fromWire("совсем не тот тип"))
        assertEquals(EntityType.UNKNOWN, EntityType.fromWire(""))
        assertEquals(BlockType.UNKNOWN, BlockType.fromWire("совсем не тот тип"))
        assertEquals(BlockType.UNKNOWN, BlockType.fromWire(""))
    }
}
