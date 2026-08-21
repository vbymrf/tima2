package io.tima.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Профили возможностей (ADR-0011 §8) — структурная часть, без визуального рендера. */
class ContentProfileTest {

    private fun row(cells: Int) = MarkupBlock(type = BlockType.ROW, children = List(cells) { MarkupBlock(type = BlockType.CELL) })
    private fun table(columns: Int, rows: Int = 2) = MarkupBlock(type = BlockType.TABLE, children = List(rows) { row(columns) })

    @Test
    fun `узкая таблица не прокручивается ни в одном профиле`() {
        val markup = Markup(blocks = listOf(table(columns = 2)))
        for (profile in ContentProfile.entries) {
            val hint = ContentProfiles.apply(markup, profile).first { it.block.type == BlockType.TABLE }
            assertFalse(hint.horizontalScroll, "профиль $profile: узкая таблица не должна прокручиваться")
        }
    }

    @Test
    fun `широкая таблица прокручивается в пузыре, но не в публикации`() {
        val markup = Markup(blocks = listOf(table(columns = 5)))
        val bubble = ContentProfiles.apply(markup, ContentProfile.BUBBLE).first { it.block.type == BlockType.TABLE }
        val publication = ContentProfiles.apply(markup, ContentProfile.PUBLICATION).first { it.block.type == BlockType.TABLE }
        assertTrue(bubble.horizontalScroll, "5 столбцов в пузыре — ADR-0011 §8 требует прокрутку")
        assertFalse(publication.horizontalScroll, "публикация — полный набор, прокрутка не нужна")
    }

    @Test
    fun `глубокий контейнер упрощается в пузыре, дети не разворачиваются`() {
        // container(0) → container(1) → container(2, за пределом BUBBLE.maxContainerDepth=2) → paragraph
        val deep = MarkupBlock(
            type = BlockType.CONTAINER,
            children = listOf(
                MarkupBlock(
                    type = BlockType.CONTAINER,
                    children = listOf(
                        MarkupBlock(type = BlockType.CONTAINER, children = listOf(MarkupBlock(type = BlockType.PARAGRAPH))),
                    ),
                ),
            ),
        )
        val markup = Markup(blocks = listOf(deep))
        val hints = ContentProfiles.apply(markup, ContentProfile.BUBBLE)

        val simplified = hints.filter { it.simplified }
        assertEquals(1, simplified.size, "упрощаться должен ровно тот контейнер, что достиг предела глубины")
        assertEquals(2, simplified.first().depth)
        // Параграф внутри упрощённого контейнера рендерер профиля видеть не должен.
        assertTrue(hints.none { it.block.type == BlockType.PARAGRAPH })
    }

    @Test
    fun `та же глубина не упрощается в публикации - полный набор`() {
        val deep = MarkupBlock(
            type = BlockType.CONTAINER,
            children = listOf(
                MarkupBlock(
                    type = BlockType.CONTAINER,
                    children = listOf(
                        MarkupBlock(type = BlockType.CONTAINER, children = listOf(MarkupBlock(type = BlockType.PARAGRAPH))),
                    ),
                ),
            ),
        )
        val markup = Markup(blocks = listOf(deep))
        val hints = ContentProfiles.apply(markup, ContentProfile.PUBLICATION)

        assertTrue(hints.none { it.simplified })
        assertTrue(hints.any { it.block.type == BlockType.PARAGRAPH }, "публикация обязана дойти до листового блока")
    }

    @Test
    fun `плоский документ без вложенности - все блоки на глубине 0`() {
        val markup = Markup(
            blocks = listOf(
                MarkupBlock(type = BlockType.HEADING, level = 1),
                MarkupBlock(type = BlockType.PARAGRAPH),
            ),
        )
        val hints = ContentProfiles.apply(markup, ContentProfile.BUBBLE)
        assertEquals(2, hints.size)
        assertTrue(hints.all { it.depth == 0 })
    }
}
