package io.tima.domain.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Продолжение другого уровня (Решения §11.3): закрытая часть — отдельное сообщение своего уровня с
 * пометкой «только продолжение»; уходит первым, основное — потом, с подкладкой на него.
 */
class ContinuationTest {

    private class Queued(val key: String, val body: String, val level: Int)

    private val queued = mutableListOf<Queued>()
    private val marked = mutableListOf<String>()

    /** Тело-подделка: куски через «|», подкладка — «u:<номер>». */
    private val bodies = object : ComposedBodies {
        private fun flat(d: ComposedDoc) = d.split().first.items.joinToString("|") { item ->
            when (item) {
                is ComposedItem.Text -> item.source
                is ComposedItem.Widget -> (item.widget as? ComposedWidget.Underlay)?.let { "u:${it.message}" } ?: "w"
                else -> "?"
            }
        }
        override fun problems(doc: ComposedDoc) = emptyList<String>()
        override fun encode(doc: ComposedDoc, reply: ReplyTarget?) = flat(doc).encodeToByteArray()
        override fun preview(doc: ComposedDoc): RichContent? = null
        override fun save(doc: ComposedDoc) = ""
        override fun load(saved: String): ComposedDoc? = null
    }

    private var n = 0
    private val send = SendComposed(
        queue = { key, _, body, level, _, _ -> queued += Queued(key, body.decodeToString(), level); true },
        bodies = bodies,
        keys = { "k-${++n}" },
        continuations = object : ContinuationMarks {
            override fun mark(dedupKey: String) { marked += dedupKey }
            override fun marked(dedupKey: String) = dedupKey in marked
        },
    )

    private val doc = ComposedDoc(
        listOf(ComposedItem.Text("Сбор в субботу"), ComposedItem.Continuation(3), ComposedItem.Text("Адрес: Лесная, 5")),
        level = 1,
    )

    @Test
    fun разделение_на_основное_и_продолжение() {
        val (main, cont) = doc.split()
        assertEquals(listOf<ComposedItem>(ComposedItem.Text("Сбор в субботу")), main.items)
        assertEquals(3, cont!!.level)
        assertEquals(listOf<ComposedItem>(ComposedItem.Text("Адрес: Лесная, 5")), cont.items)
        assertNull(ComposedDoc(listOf(ComposedItem.Text("a"), ComposedItem.Continuation(3), ComposedItem.Text(" "))).split().second, "пустое продолжение — его нет")
        assertEquals(2, doc.rows, "метка — не ряд: два куска текста")
    }

    @Test
    fun сначала_продолжение_потом_основное_с_подкладкой() {
        val first = assertIs<ComposedResult.ContinuationFirst>(send.send("g-1", doc, 2))
        val cont = queued.single()
        assertEquals("Адрес: Лесная, 5", cont.body)
        assertEquals(3, cont.level, "уровень продолжения — у метки")
        assertEquals(listOf(cont.key), marked, "продолжение помечено «только продолжение»")

        assertIs<ComposedResult.Queued>(send.sendMain("g-1", first.main, "group", continuation = 77, level = 2))
        val main = queued.last()
        assertEquals("Сбор в субботу|u:77", main.body, "подкладка на номер продолжения — в конце")
        assertEquals(1, main.level, "уровень основного — свой")
        assertTrue(main.key !in marked)
    }

    @Test
    fun без_пометок_продолжения_нет() {
        val plain = SendComposed({ key, _, body, level, _, _ -> queued += Queued(key, body.decodeToString(), level); true }, bodies, { "p" })
        assertIs<ComposedResult.Queued>(plain.send("chat", doc, -1))
        assertEquals("Сбор в субботу", queued.single().body, "в личной продолжений нет: уходит основное")
    }
}
