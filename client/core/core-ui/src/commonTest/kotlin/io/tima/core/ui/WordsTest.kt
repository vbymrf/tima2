package io.tima.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Словарь надписей (ПЛАН-ЯЗЫКА Я1, Я9).
 *
 * Проверяется не текст — он меняется, — а устройство: язык без словаря не выбирается,
 * незнакомый тег не оставляет приложение без надписей, названия языков написаны на них
 * самих, и **ни один словарь не отвечает пустотой**.
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
        assertTrue(Language.English.available, "английский словарь заведён в Я9")
        // Испанский заводится вместе со своим словарём (Я10). До тех пор список знает о
        // нём, но выбрать его нельзя: это обещало бы надписи, которых нет.
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
    fun ни_один_словарь_не_отвечает_пустотой() {
        // Пустая надпись — это пропущенный перевод, который прошёл компилятор: имя есть,
        // значения нет. Здесь он ловится, и сразу во всех заведённых словарях.
        for (language in Language.entries) {
            val words = language.words ?: continue
            assertTrue(
                sample(words).none { it.isBlank() },
                "в словаре «${language.ownName}» есть пустая надпись",
            )
            assertTrue(
                ThemeChoice.entries.none { words.appearance.theme(it).isBlank() },
                "в словаре «${language.ownName}» у темы нет названия",
            )
            assertTrue(
                ColorSlot.entries.none { words.appearance.slot(it).isBlank() },
                "в словаре «${language.ownName}» у цветового места нет названия",
            )
            assertTrue(
                WindowTab.entries.none { words.tabs.label(it).isBlank() },
                "в словаре «${language.ownName}» у вкладки нет названия",
            )
            assertTrue(
                SettingsItem.entries.none { words.settings2.item(it).isBlank() },
                "в словаре «${language.ownName}» у пункта настроек нет названия",
            )
            assertTrue(
                Window.entries.none { words.windows.full(it).isBlank() },
                "в словаре «${language.ownName}» у окна нет имени",
            )
        }
    }

    @Test
    fun у_словарей_свои_теги() {
        assertEquals("ru", RussianWords.tag)
        assertEquals("en", EnglishWords.tag)
    }

    /**
     * Срез словаря для проверки на пустоту.
     *
     * Не весь словарь: пятьсот имён руками не выписать, и список разошёлся бы с ним при
     * первой правке. Взято по нескольку из каждой группы — этого хватает, чтобы поймать
     * забытую группу целиком, а забытое одиночное имя ловится компилятором.
     */
    private fun sample(words: Words): List<String> = with(words) {
        listOf(
            tag, ownName,
            common.back, common.cancel, common.ready, common.send, common.hide,
            common.noConnection, common.nothingChosen,
            settings.settings, settings.language, settings.languageAbout, settings.appLanguage,
            settings.country, settings.countryAbout, settings.countryHint, settings.whatToShow,
            settings.onlyMyCountry, settings.onlyMyLanguages, settings.filterNotForChats,
            settings.on, settings.off, settings.chosen, settings.soon,
            settings.localeNotRead, settings.localeNotSaved,
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
            appearance.qrTooLong, appearance.qrTooLongAbout, appearance.theme,
            appearance.colors, appearance.palette, appearance.apply, appearance.merged,
            trouble.offline, trouble.didNotReach, trouble.noConnection,
            problem.whatHappened, problem.whenBegan, problem.today, problem.send,
            switching.accounts, switching.notSent, switching.leaveNow,
            settings2.settings,
            update.installed, update.install, update.notNow, update.tryAgain,
            storage.weeks, storage.months, storage.diary, storage.keep,
            social.noGroupsYet, social.members, social.access, social.owner, social.ask,
            book.everyone, book.search, book.view, book.name, book.nickname,
            page.emptyHere, page.remove, page.loading,
            chat.write, chat.find, chat.profile, chat.save, chat.narrow,
            auth.welcome, auth.enter, auth.secretPhrase, auth.trust, auth.keep,
            wizard.create, wizard.next, wizard.gotIt, wizard.personal, wizard.public,
        )
    }
}
