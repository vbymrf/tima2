package io.tima.crypto

import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Вектор `markup_json` (ADR-0011 «открыто», ADR-0044): разметка образцового сообщения редактора
 * в JSON — ровно те байты, что уходят в `MessageBody.markup`. Заморожен на первом зелёном прогоне
 * Kotlin-реализации 2026-10-18, как `message_body`; Go-стороне — референс, когда у неё появится
 * читатель разметки.
 */
class MarkupVectorTest {

    @Test
    fun разметка_совпадает_с_замороженным_вектором() {
        val sample = MarkupSample.content()
        val encoded = Markup.encode(sample.markup!!)
        println("markup_json=$encoded")
        val frozen = Vectors["markup_json"]["json"]!!.jsonPrimitive.content
        assertEquals(frozen, encoded, "JSON разметки разошёлся с замороженным вектором")
        // Обратный ход нормативен: разбор вектора даёт ту же разметку.
        assertEquals(sample.markup, Markup.decode(frozen))
        // С v2 (ADR-0048) v1 не пишется, но читается — через тот же вход, что тело сообщения.
        assertEquals(sample.markup, MarkupV2.decodeBody(frozen, sample.nodes))
    }

    /** Вектор `markup_json_v2` (ADR-0048): то, что клиент пишет в тело с 2026-10-19. */
    @Test
    fun разметка_v2_совпадает_с_замороженным_вектором() {
        val sample = MarkupSample.content()
        val encoded = MarkupV2.encodeBody(sample.markup!!, sample.nodes)
        val frozen = Vectors["markup_json_v2"]["json"]!!.jsonPrimitive.content
        assertEquals(frozen, encoded, "JSON разметки v2 разошёлся с замороженным вектором")
        assertEquals(sample.markup, MarkupV2.decodeBody(frozen, sample.nodes))
    }
}
