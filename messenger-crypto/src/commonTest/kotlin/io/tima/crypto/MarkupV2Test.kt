package io.tima.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Разметка v2 (ADR-0048): блоки по порядку забирают узлы по порядку, отрезки — внутри блока.
 * Пишется v2, читаются v2 и прежняя v1 — в одну и ту же модель.
 */
class MarkupV2Test {

    private fun roundTrip(content: MessageContent): String {
        val raw = MarkupV2.encodeBody(content.markup!!, content.nodes)
        assertTrue(raw.startsWith("{\"v\":2,"), "пишется v2: $raw")
        assertEquals(content.markup, MarkupV2.decodeBody(raw, content.nodes), "обратный ход дал другую разметку: $raw")
        return raw
    }

    @Test
    fun образцовое_сообщение_туда_и_обратно() {
        val raw = roundTrip(MarkupSample.content())
        println("markup_json_v2=$raw")
    }

    @Test
    fun набранное_знаками_туда_и_обратно() {
        val typed = QuickMarkdown.parse(
            "# Заголовок\n" +
                "Абзац: **жирный** *курсив* ++подчёркнутый++ ~~зачёркнутый~~ ==маркер== `код` @anna #тег\n" +
                "--> первая строка цитаты\n--> вторая **жирная** строка\n" +
                "- пункт\n  - вложенный\n```\nкод\n```\n---\nконец",
        )
        val raw = roundTrip(typed)
        assertTrue("\"n\"" !in raw && "\"node\"" !in raw, "номеров узлов в v2 нет: $raw")
    }

    @Test
    fun прежняя_v1_читается() {
        val sample = MarkupSample.content()
        val v1 = Markup.encode(sample.markup!!)
        assertEquals(sample.markup, MarkupV2.decodeBody(v1, sample.nodes))
    }

    @Test
    fun строго_по_числу_узлов() {
        val sample = MarkupSample.content()
        val raw = MarkupV2.encodeBody(sample.markup!!, sample.nodes)
        assertNull(MarkupV2.decodeBody(raw, sample.nodes + "лишний"), "узлов больше, чем забрали блоки")
        assertNull(MarkupV2.decodeBody(raw, sample.nodes.dropLast(1)), "узлов меньше")
        assertNull(MarkupV2.decodeBody("{\"v\":3,\"blocks\":[]}", emptyList()), "незнакомая версия")
    }

    /** Сообщение из одного виджета: узлов нет, тело читается с одним пустым — разметка не теряется. */
    @Test
    fun только_виджет_без_узлов() {
        val markup = Markup(blocks = listOf(MarkupBlock(type = BlockType.EMOJI, widget = MarkupWidget(text = "🎉"))))
        val raw = MarkupV2.encodeBody(markup, emptyList())
        assertEquals(markup, MarkupV2.decodeBody(raw, listOf("")))
        val body = MessageContentCodec.toBody(MessageContent(emptyList(), markup))
        assertEquals(markup, MessageContentCodec.fromBody(body).markup)
    }

    @Test
    fun не_по_порядку_пишется_v1() {
        val nodes = listOf("a", "b")
        val markup = Markup(n = listOf(1, 2), blocks = listOf(
            MarkupBlock(type = BlockType.PARAGRAPH, nodeIds = listOf(2)),
            MarkupBlock(type = BlockType.PARAGRAPH, nodeIds = listOf(1)),
        ))
        assertNull(MarkupV2.encode(markup, nodes))
        val raw = MarkupV2.encodeBody(markup, nodes)
        assertEquals(Markup.encode(markup), raw, "не выражается в v2 — v1")
        assertEquals(markup, MarkupV2.decodeBody(raw, nodes))
    }
}
