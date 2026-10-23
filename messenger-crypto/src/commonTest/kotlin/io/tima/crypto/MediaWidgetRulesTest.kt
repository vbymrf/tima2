package io.tima.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Видео, звук и метки по ссылке (ПЛАН-(ВМ) Р8–Р13, Р24, Р32): описание обязательно, метки — к
 * описанию видео или аудио этого сообщения, к одному — одни, меток до 30, всего таких виджетов до 30.
 */
class MediaWidgetRulesTest {

    private fun row(vararg widgets: MarkupBlock) = MarkupBlock(
        type = BlockType.FLEX,
        children = listOf(MarkupBlock(type = BlockType.CELL, style = mapOf("width" to "100"), children = widgets.toList())),
    )

    private fun content(vararg rows: MarkupBlock) = MessageContent(nodes = emptyList(), markup = Markup(blocks = rows.toList()))

    private fun video(desc: String, url: String = "https://x.ru/a.mp4") =
        MarkupBlock(type = BlockType.VIDEO, widget = MarkupWidget(url = url, description = desc, look = "picture"))

    private fun audio(desc: String) = MarkupBlock(type = BlockType.AUDIO, widget = MarkupWidget(url = "https://x.ru/a.mp3", description = desc, durationMs = 61_000))

    private fun stream(desc: String) = MarkupBlock(type = BlockType.STREAM, widget = MarkupWidget(url = "https://r.ru/live.m3u8", description = desc))

    private fun marks(bind: String, n: Int = 2) =
        MarkupBlock(type = BlockType.MARKS, widget = MarkupWidget(bind = bind, marks = List(n) { MarkupMark(at = it * 10_000L, text = "метка $it") }))

    private fun rules(c: MessageContent, strict: Boolean = true) = MarkupRules.check(c, strict).map { it.rule }

    @Test
    fun видео_аудио_поток_и_метки_проходят() {
        val c = content(row(video("Обзор пляжа"), marks("Обзор пляжа")), row(audio("Подкаст"), marks("Подкаст", 30)), row(stream("Радио")))
        assertEquals(emptyList(), rules(c))
    }

    @Test
    fun без_описания_и_адреса_не_проходит_строго() {
        assertTrue("widget" in rules(content(row(video("")))))
        assertTrue("widget" in rules(content(row(video("Обзор", url = "ftp://x/a.mp4")))))
        // Принятое мягко — показывается без заглавия.
        assertEquals(emptyList(), rules(content(row(video(""))), strict = false))
    }

    @Test
    fun метки_только_к_своему_видео_и_одни_на_видео() {
        assertTrue("marks-bind" in rules(content(row(video("А"), marks("Б")))))
        assertTrue("marks-bind" in rules(content(row(stream("Радио"), marks("Радио")))), "у потока меток нет")
        assertTrue("marks-bind" in rules(content(row(video("А"), marks("А"), marks("А")))))
        assertTrue("widget" in rules(content(row(video("А"), marks("А", 31)))))
        assertTrue("widget" in rules(content(row(video("А"), marks("А", 0)))))
    }

    @Test
    fun всего_не_больше_тридцати() {
        val many = List(16) { row(video("в$it"), marks("в$it")) }
        assertTrue("media-widgets" in rules(content(*many.toTypedArray())))
        val ok = List(15) { row(video("в$it"), marks("в$it")) }
        assertTrue("media-widgets" !in rules(content(*ok.toTypedArray())))
    }

    @Test
    fun поля_доезжают_через_разметку_v2() {
        val c = content(row(video("Обзор"), marks("Обзор")))
        val text = MarkupV2.encodeBody(c.markup!!, c.nodes)
        val back = MarkupV2.decodeBody(text, c.nodes)!!
        val cell = back.blocks.single().children.single()
        assertEquals("picture", cell.children[0].widget!!.look)
        assertEquals("Обзор", cell.children[1].widget!!.bind)
        assertEquals(10_000L, cell.children[1].widget!!.marks[1].at)
        assertEquals(BlockType.MARKS, cell.children[1].type)
    }
}
