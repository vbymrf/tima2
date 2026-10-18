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
    }
}
