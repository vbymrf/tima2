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
        val all = with(words) {
            listOf(
                common.back, common.cancel, common.ready, common.send, common.hide,
                common.noConnection, common.nothingChosen,
                settings.settings, settings.language, settings.languageAbout, settings.appLanguage,
                settings.country, settings.countryAbout, settings.countryHint, settings.whatToShow,
                settings.onlyMyCountry, settings.onlyMyLanguages, settings.filterNotForChats,
                settings.on, settings.off, settings.chosen, settings.soon,
                comments.comments, comments.thread, comments.hint, comments.reply,
                comments.closed, comments.closedButOldStay, comments.nobodyWroteYet,
                comments.postGone, comments.postGoneAbout, comments.loading,
                communities.community, communities.communities, communities.subscribe,
                communities.unsubscribe, communities.bring, communities.takeOut,
                communities.bringOwn, communities.bringingKeepsEverything,
                communities.noDescription, communities.emptyInside, communities.opening,
                communities.channel, communities.group, communities.personalGroup,
                communities.yourCommunity, communities.youSubscribed, communities.youOwner,
                communities.youAdmin, communities.youNotSubscribed,
                appearance.qrTooLong, appearance.qrTooLongAbout,
            )
        }
        assertTrue(all.none { it.isBlank() }, "в словаре есть пустая надпись")

        // Названия тем и цветовых мест — тоже словарь, и пустых среди них быть не должно.
        // Пояснение у трёх мест пусто намеренно, и потому проверяются только названия.
        assertTrue(
            ThemeChoice.entries.none { words.appearance.theme(it).isBlank() },
            "у темы нет названия",
        )
        assertTrue(
            ColorSlot.entries.none { words.appearance.slot(it).isBlank() },
            "у цветового места нет названия",
        )
        assertEquals("ru", words.tag)
    }
}
