package io.tima.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Словарь надписей (ПЛАН-ЯЗЫКА Я1).
 *
 * Проверяется не текст — он меняется, — а устройство: язык без словаря не выбирается,
 * незнакомый тег не оставляет приложение без надписей, а названия языков написаны на них
 * самих.
 */
class WordsTest {

    @Test
    fun незнакомый_тег_не_оставляет_приложение_без_надписей() {
        assertEquals(Language.Russian, Language.of("qq"))
        assertEquals(Language.Russian, Language.of(""))
        assertEquals(Language.English, Language.of("en"))
    }

    @Test
    fun язык_без_словаря_не_выбирается() {
        assertTrue(Language.Russian.available, "русский словарь есть с первого дня")
        // Английский и испанский заводятся вместе со своими словарями (Я9, Я10). До тех
        // пор список знает о них, но выбрать их нельзя: это обещало бы надписи, которых
        // нет.
        assertFalse(Language.English.available)
        assertFalse(Language.Spanish.available)
    }

    @Test
    fun название_языка_написано_на_нём_самом() {
        // Человек, открывший список на незнакомом языке, обязан узнать свой.
        assertEquals("Русский", Language.Russian.ownName)
        assertEquals("English", Language.English.ownName)
        assertEquals("Español", Language.Spanish.ownName)
    }

    @Test
    fun русский_словарь_отвечает_на_все_имена() {
        // Пустая надпись — это пропущенный перевод, который прошёл компилятор: имя есть,
        // значения нет. Здесь он ловится.
        val words = RussianWords
        val all = listOf(
            words.back, words.cancel, words.ready, words.send, words.hide, words.noConnection,
            words.settings, words.language, words.languageAbout,
            words.comments, words.thread, words.commentHint, words.reply,
            words.commentsClosed, words.commentsClosedOld, words.nobodyWroteYet, words.postGone,
            words.community, words.communities, words.subscribe, words.unsubscribe,
            words.bringHere, words.takeOut, words.bringingKeepsEverything,
        )
        assertTrue(all.none { it.isBlank() }, "в словаре есть пустая надпись")
        assertEquals("ru", words.tag)
    }
}
