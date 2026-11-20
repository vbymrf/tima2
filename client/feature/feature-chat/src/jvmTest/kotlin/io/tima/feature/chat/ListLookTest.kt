package io.tima.feature.chat

import io.tima.core.ui.AvatarSize
import io.tima.core.ui.ListLook
import io.tima.testui.capture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * «Вид разделов и списка» — раздел «Список» (заказчик 2026-11-20): две или три строки, отступ слева и справа от 100% до
 * 50% шагом 10. По умолчанию — как было: две строки, отступы прежние.
 */
class ListLookTest {

    @Test
    fun по_умолчанию_как_было() {
        val view = BookView.from(emptyMap(), BookView.BOOK)
        assertEquals(ListLook(2, 100, 100), view.listLook())
        assertEquals(AvatarSize.Normal, view.listLook().avatar)
    }

    @Test
    fun у_каждого_окна_своё() {
        val saved = mapOf(
            "book.list_lines" to "3", "book.list_pad_start" to "80", "book.list_pad_end" to "100",
            "community.list_lines" to "2", "community.list_pad_start" to "50", "community.list_pad_end" to "70",
        )
        assertEquals(ListLook(3, 80, 100), BookView.from(saved, BookView.BOOK).listLook())
        assertEquals(ListLook(2, 50, 70), BookView.from(saved, BookView.COMMUNITY).listLook())
        assertEquals(AvatarSize.Row, BookView.from(saved, BookView.BOOK).listLook().avatar)
    }

    @Test
    fun чужое_значение_становится_прежним() {
        // Отступ только уменьшается: больше 100% или мимо шага — прежний; строк не 2 и не 3 — две.
        val view = BookView.from(mapOf("book.list_lines" to "5", "book.list_pad_start" to "120", "book.list_pad_end" to "85"))
        assertEquals(ListLook(2, 100, 100), view.listLook())
    }

    @Test
    fun образец_меняется_от_строк_и_отступов() {
        fun shot(name: String, view: BookView) =
            capture(name, 360, 900, dark = false) { SectionsLookPage(view = view, onChange = {}, withList = true) }
        val two = shot("список-две", BookView())
        val three = shot("список-три", BookView(listLines = 3))
        val narrow = shot("список-отступ-50", BookView(padStart = 50, padEnd = 50))
        assertTrue(two.difference(three) > 0.0, "три строки выглядят как две")
        assertTrue(two.difference(narrow) > 0.0, "отступ 50% выглядит как 100%")
    }
}
