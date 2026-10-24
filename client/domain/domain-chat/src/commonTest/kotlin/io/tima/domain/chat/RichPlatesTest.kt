package io.tima.domain.chat

import io.tima.domain.chat.RichPlates.Piece
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Одна плашка на кусок со стилем (заказчик 2026-10-24, «2в»): абзац, заголовок, абзац — не три. */
class RichPlatesTest {
    private val purple = BlockStyle(background = "purple")
    private fun p(s: String, st: BlockStyle = purple) = RichBlock.Paragraph(RichText(s), st)

    @Test
    fun абзац_заголовок_абзац_одного_стиля_одной_плашкой_без_своих_стилей() {
        val pieces = RichPlates.of(listOf(p("a"), RichBlock.Heading(2, RichText("H"), purple), p("b")))
        val plate = assertIs<Piece.Plate>(pieces.single())
        assertEquals(purple, plate.style)
        assertEquals(3, plate.blocks.size)
        assertEquals(listOf(null, null, null), plate.blocks.map { RichPlates.styleOf(it)?.background })
    }

    @Test
    fun другой_стиль_без_стиля_и_виджет_делят_плашки() {
        val beige = BlockStyle(background = "beige")
        val pieces = RichPlates.of(listOf(p("a"), p("b"), p("c", beige), p("d", BlockStyle.NONE), RichBlock.Emoji("x"), p("e"), p("f")))
        assertEquals(listOf("Plate", "One", "One", "One", "Plate"), pieces.map { it::class.simpleName })
    }

    @Test
    fun разделитель_внутри_куска_остаётся_в_плашке_а_одиночный_блок_как_был() {
        val pieces = RichPlates.of(listOf(p("a"), RichBlock.Divider, p("b"), RichBlock.Divider))
        assertEquals(3, assertIs<Piece.Plate>(pieces[0]).blocks.size)
        assertIs<Piece.One>(pieces[1])
        val alone = RichPlates.of(listOf(p("a")))
        assertEquals(p("a"), assertIs<Piece.One>(alone.single()).block)
    }
}
