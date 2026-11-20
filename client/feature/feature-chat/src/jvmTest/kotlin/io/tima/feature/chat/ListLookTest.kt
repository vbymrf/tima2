package io.tima.feature.chat

import io.tima.core.ui.AvatarSize
import io.tima.core.ui.ListLook
import io.tima.domain.chat.ChatPerson
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
    fun по_умолчанию_две_строки_слева_70_пауза_10() {
        // Заказчик 2026-11-20 (ПЛАН-(ВС) 3, 6): отступ слева по умолчанию 70% у всех окон, пауза порядка 10 с.
        val view = BookView.from(emptyMap(), BookView.BOOK)
        assertEquals(ListLook(2, 70, 100, 10), view.listLook())
        assertEquals(false, view.mergeCalls, "объединение звонков по умолчанию выключено")
        assertEquals(AvatarSize.Normal, view.listLook().avatar)
    }

    @Test
    fun у_каждого_окна_своё() {
        val saved = mapOf(
            "book.list_lines" to "3", "book.list_pad_start" to "80", "book.list_pad_end" to "100",
            "community.list_lines" to "2", "community.list_pad_start" to "50", "community.list_pad_end" to "70",
        )
        assertEquals(ListLook(3, 80, 100, 10), BookView.from(saved, BookView.BOOK).listLook())
        assertEquals(ListLook(2, 50, 70, 10), BookView.from(saved, BookView.COMMUNITY).listLook())
        assertEquals(AvatarSize.Row, BookView.from(saved, BookView.BOOK).listLook().avatar)
    }

    @Test
    fun чужое_значение_становится_прежним() {
        // Отступ только уменьшается: больше 100% или мимо шага — умолчание; строк не 2 и не 3 — две; пауза вне ряда — 10.
        val view = BookView.from(
            mapOf("book.list_lines" to "5", "book.list_pad_start" to "120", "book.list_pad_end" to "85", "book.list_rank_pause" to "7"),
        )
        assertEquals(ListLook(2, 70, 100, 10), view.listLook())
    }

    @Test
    fun образец_меняется_от_строк_и_отступов() {
        fun shot(name: String, view: BookView) =
            capture(name, 360, 900, dark = false) { SectionsLookPage(view = view, onChange = {}, withList = true) }
        val two = shot("список-две", BookView())
        val three = shot("список-три", BookView(listLines = 3))
        val narrow = shot("список-отступ-50", BookView(padStart = 50, padEnd = 50))
        // «Звонки»: разделов нет — страница только со «Списком» (ПЛАН-(ВС) ВС1).
        val calls = capture("список-звонки", 360, 900, dark = false) {
            SectionsLookPage(view = BookView(listLines = 3), onChange = {}, withList = true, withSectionsPart = false)
        }
        assertTrue(calls.difference(three) > 0.0, "у «Звонков» страница та же, что с разделами")
        assertTrue(two.difference(three) > 0.0, "три строки выглядят как две")
        assertTrue(two.difference(narrow) > 0.0, "отступ 50% выглядит как 100%")
    }

    @Test
    fun третья_строка_контакта_то_чего_нет_выше() {
        val anna = ChatPerson(name = "Анна", userName = "Anna P.", nick = "anna_p", phone = "+7 999")
        assertEquals("@anna_p", thirdLine(anna, first = "Анна", second = "+7 999"))
        assertEquals("Anna P.", thirdLine(anna, first = "Анна, @anna_p", second = "+7 999"), "ник уже в первой строке")
        assertEquals(null, thirdLine(ChatPerson(name = "Борис", phone = "+7 1"), first = "Борис", second = "+7 1"), "нечего показать")
        assertEquals(null, thirdLine(ChatPerson(name = "Вера", nick = "vera"), first = "Вера", second = "@vera"), "ник уже второй строкой")
    }
}
