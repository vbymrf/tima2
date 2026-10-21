package io.tima.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Палитра сообщений знает каждое имя, которое пропускает проверка разметки (`MarkupRules.PALETTE`
 * в messenger-crypto): имя, прошедшее проверку, но без цвета, пропало бы с экрана молча.
 */
class MessagePaletteTest {
    private val markupNames = setOf(
        "text", "surface", "accent", "accent-soft",
        "amber", "amber-soft", "green", "green-soft",
        "blue", "blue-soft", "red", "red-soft",
        "grey", "grey-soft",
        "yellow", "lilac", "pink",
    )

    @Test
    fun у_каждого_имени_цвет_в_обеих_темах() {
        assertEquals(markupNames, MessagePalette.names)
        for (name in markupNames) {
            assertNotNull(MessagePalette.color(name, darkTheme = false), name)
            assertNotNull(MessagePalette.color(name, darkTheme = true), name)
        }
        assertEquals(null, MessagePalette.color("purple", darkTheme = false), "незнакомое имя — свойства нет")
    }
}
