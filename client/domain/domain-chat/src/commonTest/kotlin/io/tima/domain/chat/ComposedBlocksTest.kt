package io.tima.domain.chat

import kotlin.test.Test
import kotlin.test.assertEquals

/** Стиль — блоку своего вида, как в сообщении (заказчик 2026-10-24): кусок режется на месте блока. */
class ComposedBlocksTest {
    /** Абзац из двух строк, заголовок, абзац. */
    private val t = Typed(
        "раз\nдва\nЗаглавие\nтри",
        listOf(LineKind.PARAGRAPH, LineKind.PARAGRAPH, LineKind.HEADING2, LineKind.PARAGRAPH),
    )

    /** Знаки — строкой «##»: так тест видит разрез без настоящего разбора. */
    private val source = object : TypedSource by PlainTyped {
        override fun read(source: String): Typed {
            val lines = source.split('\n')
            return Typed(
                lines.joinToString("\n") { it.removePrefix("## ") },
                lines.map { if (it.startsWith("## ")) LineKind.HEADING2 else LineKind.PARAGRAPH },
            )
        }

        override fun write(typed: Typed): String =
            typed.text.split('\n').mapIndexed { i, l -> if (typed.lines[i] == LineKind.HEADING2) "## $l" else l }.joinToString("\n")
    }

    @Test
    fun блоки_абзац_строками_заголовок_строкой() {
        assertEquals(0..1, ComposedBlocks.blockOf(t, 1))
        assertEquals(2..2, ComposedBlocks.blockOf(t, 2))
        assertEquals(listOf(2, 3), ComposedBlocks.starts(t))
    }

    @Test
    fun стиль_заголовку_режет_кусок_на_три_со_стилем_куска() {
        val doc = ComposedDoc(listOf(ComposedItem.Text(source.write(t), mapOf("color" to "red"))))
        val caret = t.lineStart(2) + 3
        val cut = ComposedBlocks.cut(doc, 0, caret, source)
        assertEquals(listOf("раз\nдва", "## Заглавие", "три"), cut.doc.items.map { (it as ComposedItem.Text).source })
        assertEquals(List(3) { mapOf("color" to "red") }, cut.doc.items.map { it.style })
        assertEquals(1, cut.item)
        assertEquals(3, cut.caret)
        // Кусок из одного блока — без разреза.
        val one = ComposedDoc(listOf(ComposedItem.Text("раз\nдва")))
        assertEquals(one, ComposedBlocks.cut(one, 0, 2, source).doc)
    }
}
